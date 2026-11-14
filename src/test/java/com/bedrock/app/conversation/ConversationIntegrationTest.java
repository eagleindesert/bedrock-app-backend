package com.bedrock.app.conversation;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.persistence.AiRequestStore;
import com.bedrock.app.ai.service.AiValidation;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.port.ItemBatchWritePort;
import com.bedrock.app.conversation.dto.ConversationDtos;
import com.bedrock.app.conversation.persistence.ConversationStore;
import com.bedrock.app.conversation.service.ConversationService;
import com.bedrock.app.crypto.key.UserDataKeyProvider;
import com.bedrock.app.crypto.service.EnvelopeUserCrypto;
import com.bedrock.app.support.PostgresIntegrationSupport;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConversationIntegrationTest extends PostgresIntegrationSupport {
    private final UUID user = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private ConversationService service;
    private JdbcTemplate jdbc;
    private AiRequestStore requests;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM users");
        var manager = new DataSourceTransactionManager(source);
        var accounts = new AuthStore(jdbc, manager);
        accounts.create(user, "conversation@example.com", "test", null);
        accounts.create(other, "other-conversation@example.com", "other", null);
        UUID workspaceId = UUID.randomUUID();
        jdbc.update("INSERT INTO workspaces (id, owner_user_id) VALUES (?, ?)", workspaceId, user);
        accounts.bindWorkspace(user, workspaceId);
        var keys = new MemoryKeys(); keys.createIfAbsent(user); keys.createIfAbsent(other);
        var mapper = JsonMapper.builder().build();
        requests = new AiRequestStore(jdbc, manager);
        service = new ConversationService(new ConversationStore(jdbc), accounts, requests, new EnvelopeUserCrypto(keys),
                mock(ItemBatchWritePort.class), new AiValidation(mapper, Validation.buildDefaultValidatorFactory().getValidator()), mapper);
    }

    @Test void encryptsAndMakesPutsIdempotent() {
        UUID id = UUID.randomUUID(); var write = put(null, "비공개 대화", null);
        var first = service.put(user, id, write);
        var repeated = service.put(user, id, write);
        assertThat(repeated).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT content_enc FROM conversations WHERE id = ?", String.class, id)).doesNotContain("비공개 대화");
        assertThatThrownBy(() -> service.put(user, id, new ConversationDtos.PutRequest(write.writeId(), null, record("다른 본문", null))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(other, id)).isInstanceOf(BusinessException.class);
    }

    @Test void deletionErasesSnapshotsAndPreventsLateRecreation() {
        UUID id = UUID.randomUUID(); var write = put(null, "지울 대화", null); service.put(user, id, write);
        UUID deletion = UUID.randomUUID(); service.delete(user, id, deletion); service.delete(user, id, deletion);
        assertThatThrownBy(() -> service.put(user, id, write)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.put(user, id, put(null, "늦은 쓰기", null))).isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversation_writes WHERE user_id = ? AND response_enc IS NOT NULL", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sync_tombstones WHERE user_id = ? AND resource_type = 'CONVERSATION' AND resource_id = ?",
                Integer.class, user, id.toString())).isEqualTo(1);
        assertThat(service.wasDeleted(user, id)).isTrue();
    }

    @Test void deleteAllReplayDoesNotDeleteNewConversations() {
        UUID old = UUID.randomUUID(); service.put(user, old, put(null, "옛 기록", null));
        UUID writeId = UUID.randomUUID(); service.deleteAll(user, writeId, true);
        UUID fresh = UUID.randomUUID(); service.put(user, fresh, put(null, "새 기록", null));
        service.deleteAll(user, writeId, true);
        assertThat(service.get(user, fresh).record().sentence()).isEqualTo("새 기록");
        assertThatThrownBy(() -> service.deleteAll(user, UUID.randomUUID(), false)).isInstanceOf(BusinessException.class);
    }

    @Test void paginationHasNoDuplicatesWhenTimestampsMatch() {
        for (int i = 0; i < 5; i++) service.put(user, UUID.randomUUID(), put(null, "record " + i, null));
        var first = service.page(user, null, 2); var second = service.page(user, first.nextCursor(), 2); var third = service.page(user, second.nextCursor(), 2);
        var ids = new HashSet<UUID>(); first.items().forEach(row -> ids.add(row.id())); second.items().forEach(row -> ids.add(row.id())); third.items().forEach(row -> ids.add(row.id()));
        assertThat(ids).hasSize(5); assertThat(third.hasMore()).isFalse();
        assertThatThrownBy(() -> service.page(other, first.nextCursor(), 2)).isInstanceOf(BusinessException.class);
    }

    @Test void deleteCancelsLinkedQuestionAndErasesItsText() {
        UUID requestId = UUID.randomUUID();
        requests.locked(requestId, () -> { requests.insert(user, requestId, "AWAITING_ANSWER", UUID.randomUUID().toString(), "encrypted-question-fixture"); return null; });
        UUID id = UUID.randomUUID(); service.put(user, id, put(null, "대기 질문", requestId)); service.delete(user, id, UUID.randomUUID());
        var request = requests.find(user, requestId).orElseThrow();
        assertThat(request.status()).isEqualTo("CANCELLED"); assertThat(request.payloadEnc()).isEmpty();
    }

    @Test void anAiRequestCanOnlyBelongToOneConversation() {
        UUID requestId = UUID.randomUUID();
        requests.locked(requestId, () -> { requests.insert(user, requestId, "AWAITING_ANSWER", UUID.randomUUID().toString(), "encrypted-question-fixture"); return null; });
        UUID firstId = UUID.randomUUID();
        service.put(user, firstId, put(null, "첫 대화", requestId));
        assertThat(requests.find(user, requestId).orElseThrow().conversationId()).isEqualTo(firstId);
        assertThatThrownBy(() -> service.put(user, UUID.randomUUID(), put(null, "잘못 연결한 대화", requestId)))
                .isInstanceOf(BusinessException.class);
        assertThat(service.page(user, null, 10).items()).hasSize(1);
    }

    @Test void malformedSavedQueryIsAClientErrorAndWritesNothing() {
        var original = record("조회", null);
        var query = new AiDtos.Query(new AiDtos.DateInstruction("rangeStart", "오늘", "not-a-date"), null,
                List.of(), List.of(), List.of(), null, null, null);
        var invalid = new ConversationDtos.Record(ConversationDtos.Status.QUERY_RESULT, original.sentence(), original.referenceTime(),
                original.referenceTimeZone(), original.recordDate(), null, original.context(), List.of(), List.of(), List.of(), query, original.counts());
        assertThatThrownBy(() -> service.put(user, UUID.randomUUID(), new ConversationDtos.PutRequest(UUID.randomUUID(), null, invalid)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversations", Integer.class)).isZero();
    }

    private ConversationDtos.PutRequest put(String revision, String sentence, UUID requestId) {
        return new ConversationDtos.PutRequest(UUID.randomUUID(), revision, record(sentence, requestId));
    }
    private ConversationDtos.Record record(String sentence, UUID requestId) {
        return new ConversationDtos.Record(requestId == null ? ConversationDtos.Status.PENDING_INBOX : ConversationDtos.Status.AWAITING_ANSWER,
                sentence, Instant.parse("2026-11-15T00:00:00Z"), "Asia/Seoul", LocalDate.parse("2026-11-15"), requestId,
                new AiDtos.Context("HOME", null, null, null, null, List.of()), List.of(), List.of(), List.of(), null,
                new ConversationDtos.Counts(0, 0, 0, 0));
    }
    private static final class MemoryKeys implements UserDataKeyProvider {
        private final Map<UUID, byte[]> keys = new HashMap<>();
        public void createIfAbsent(UUID id) { keys.computeIfAbsent(id, ignored -> { byte[] key = new byte[32]; new SecureRandom().nextBytes(key); return key; }); }
        public byte[] load(UUID id) { return keys.get(id).clone(); }
        public void delete(UUID id) { keys.remove(id); }
    }
}
