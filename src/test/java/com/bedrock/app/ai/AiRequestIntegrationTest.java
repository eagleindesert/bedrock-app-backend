package com.bedrock.app.ai;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.persistence.AiRequestStore;
import com.bedrock.app.ai.service.*;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.ItemBatchWritePort;
import com.bedrock.app.conversation.persistence.ConversationStore;
import com.bedrock.app.crypto.key.UserDataKeyProvider;
import com.bedrock.app.crypto.service.EnvelopeUserCrypto;
import com.bedrock.app.support.PostgresIntegrationSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 외부 모델·타 담당 읽기 포트만 대체하고 실제 PostgreSQL 트랜잭션과 암호화를 사용한다. */
class AiRequestIntegrationTest extends PostgresIntegrationSupport {
    private final UUID user = UUID.randomUUID();
    private AiRequestStore store;
    private AiRequestService service;
    private AiWorkspaceAccess access;
    private AiInterpreter interpreter;
    private ItemBatchWritePort batches;
    private TransactionTemplate transaction;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).outOfOrder(true).load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM users");
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var accounts = new AuthStore(jdbc, manager);
        accounts.create(user, "ai-fixture@example.com", "fixture", null);
        store = new AiRequestStore(jdbc, manager);
        access = mock(AiWorkspaceAccess.class);
        interpreter = mock(AiInterpreter.class);
        batches = mock(ItemBatchWritePort.class);
        var limits = mock(InferenceLimits.class);
        when(limits.execute(eq(user), eq(false), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(2).get());
        var mapper = JsonMapper.builder().build();
        var keys = new MemoryKeys();
        service = new AiRequestService(store, accounts, new ConversationStore(jdbc), access,
                new AiValidation(mapper, Validation.buildDefaultValidatorFactory().getValidator()), interpreter, limits,
                new EnvelopeUserCrypto(keys), batches, mapper, new SimpleMeterRegistry(), Clock.systemUTC());
    }

    @Test void interpretationRetryReturnsTheOriginalEncryptedResult() {
        var input = input(UUID.randomUUID(), "비공개 회의 만들기");
        var result = ready(input.requestId());
        when(interpreter.interpret(eq(input), anyList(), anyList())).thenReturn(result);
        assertThat(service.interpret(user, input)).isEqualTo(result);
        assertThat(service.interpret(user, input)).isEqualTo(result);
        verify(interpreter, times(1)).interpret(eq(input), anyList(), anyList());
        assertThat(store.find(user, input.requestId()).orElseThrow().payloadEnc()).doesNotContain(input.sentence());
        assertThatThrownBy(() -> service.interpret(user, input(input.requestId(), "같은 ID의 다른 입력")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), input.requestId())).isInstanceOf(BusinessException.class);
    }

    @Test void lateModelResponseCannotRestoreACancelledRequest() {
        var input = input(UUID.randomUUID(), "처리 중 지울 요청");
        when(interpreter.interpret(eq(input), anyList(), anyList())).thenAnswer(call -> {
            store.locked(input.requestId(), () -> { store.cancel(user, input.requestId()); return null; });
            return ready(input.requestId());
        });
        assertThatThrownBy(() -> service.interpret(user, input)).isInstanceOf(BusinessException.class);
        var cancelled = store.find(user, input.requestId()).orElseThrow();
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.payloadEnc()).isEmpty();
        assertThatThrownBy(() -> service.interpret(user, input)).isInstanceOf(BusinessException.class);
    }

    @Test void consentRevokedDuringTheCallDoesNotPersistTheModelResult() {
        var input = input(UUID.randomUUID(), "동의를 철회할 요청");
        doNothing().doThrow(new BusinessException(com.bedrock.app.common.error.CommonErrorCode.FEATURE_NOT_READY))
                .when(access).requireAiConsent(user);
        when(interpreter.interpret(eq(input), anyList(), anyList())).thenReturn(ready(input.requestId()));
        assertThatThrownBy(() -> service.interpret(user, input)).isInstanceOf(BusinessException.class);
        assertThat(store.find(user, input.requestId()).orElseThrow().status()).isEqualTo("RETRYABLE");
        assertThatThrownBy(() -> service.get(user, input.requestId())).isInstanceOf(BusinessException.class);
    }

    @Test void answerIdAndRevisionPreventRepeatedOrStaleAnswers() {
        var input = input(UUID.randomUUID(), "일정을 만들어 줘");
        var asking = new AiDtos.Interpretation(input.requestId(), UUID.randomUUID().toString(), AiDtos.Kind.ASK,
                List.of(), List.of(new AiDtos.Question("date", "언제인가요?", List.of(), true)), null, List.of(), null, null);
        var result = ready(input.requestId());
        when(interpreter.interpret(eq(input), anyList(), anyList())).thenReturn(asking, result);
        service.interpret(user, input);
        var answer = new AiDtos.AnswerRequest(UUID.randomUUID(), asking.revision(), List.of(new AiDtos.Answer("date", null, "내일")));
        assertThat(service.answer(user, input.requestId(), answer)).isEqualTo(result);
        assertThat(service.answer(user, input.requestId(), answer)).isEqualTo(result);
        verify(interpreter, times(2)).interpret(eq(input), anyList(), anyList());
        assertThatThrownBy(() -> service.answer(user, input.requestId(), new AiDtos.AnswerRequest(
                UUID.randomUUID(), asking.revision(), answer.answers()))).isInstanceOf(BusinessException.class);
    }

    @Test void batchCompletionSharesTheCallerTransactionAndCannotBeReassigned() {
        var input = input(UUID.randomUUID(), "한 번만 저장할 요청");
        var result = ready(input.requestId());
        when(interpreter.interpret(eq(input), anyList(), anyList())).thenReturn(result);
        service.interpret(user, input);
        UUID batchId = UUID.randomUUID();
        assertThatThrownBy(() -> service.markCommitted(user, input.requestId(), result.revision(), batchId, null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.markCommitted(user, input.requestId(), result.revision(), batchId, null);
            throw new IllegalStateException("simulate batch rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.find(user, input.requestId()).orElseThrow().status()).isEqualTo("READY");
        transaction.executeWithoutResult(status -> service.markCommitted(user, input.requestId(), result.revision(), batchId, null));
        transaction.executeWithoutResult(status -> assertThat(service.lockReadyRequest(user, input.requestId(), "old-revision")).isEqualTo(batchId));
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> service.markCommitted(
                user, input.requestId(), result.revision(), UUID.randomUUID(), null))).isInstanceOf(BusinessException.class);
        when(batches.getBatchStatus(user, batchId)).thenReturn(new ItemBatchWritePort.BatchStatusResult(batchId, "COMPLETED",
                new ItemBatchWritePort.BatchWriteResult(batchId, true, List.of(), List.of(), 1)));
        assertThat(service.get(user, input.requestId()).interpretation().committedBatchId()).isEqualTo(batchId);
    }

    private AiDtos.InterpretationRequest input(UUID id, String sentence) {
        return new AiDtos.InterpretationRequest(id, sentence, Instant.parse("2026-11-15T00:00:00Z"), "Asia/Seoul",
                new AiDtos.Context("HOME", null, null, null, null, List.of()), List.of(), "", null, null);
    }

    private AiDtos.Interpretation ready(UUID id) {
        var mapper = JsonMapper.builder().build();
        var operation = new AiDtos.Operation(UUID.randomUUID(), AiDtos.OperationKind.CREATE, null, null, null,
                Map.of("name", mapper.valueToTree("새 일정")), List.of(), null, null, false);
        return new AiDtos.Interpretation(id, UUID.randomUUID().toString(), AiDtos.Kind.READY,
                List.of(operation), List.of(), null, List.of(), null, null);
    }

    private static final class MemoryKeys implements UserDataKeyProvider {
        private final byte[] key = new byte[32];
        private MemoryKeys() { new SecureRandom().nextBytes(key); }
        public void createIfAbsent(UUID id) {}
        public byte[] load(UUID id) { return key.clone(); }
        public void delete(UUID id) { java.util.Arrays.fill(key, (byte) 0); }
    }
}
