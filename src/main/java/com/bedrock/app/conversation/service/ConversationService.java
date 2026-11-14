package com.bedrock.app.conversation.service;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.persistence.AiRequestStore;
import com.bedrock.app.ai.service.AiValidation;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.ItemBatchWritePort;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.conversation.dto.ConversationDtos;
import com.bedrock.app.conversation.persistence.ConversationStore;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

@Service("conversationService")
public class ConversationService {
    private final ConversationStore store;
    private final AuthStore auth;
    private final AiRequestStore requests;
    private final UserCryptoPort crypto;
    private final ItemBatchWritePort batches;
    private final AiValidation validation;
    private final ObjectMapper mapper;

    public ConversationService(ConversationStore store, AuthStore auth, AiRequestStore requests, UserCryptoPort crypto,
            ItemBatchWritePort batches, AiValidation validation, ObjectMapper mapper) {
        this.store = store; this.auth = auth; this.requests = requests; this.crypto = crypto;
        this.batches = batches; this.validation = validation; this.mapper = mapper;
    }

    public ConversationDtos.Detail put(UUID userId, UUID id, ConversationDtos.PutRequest input) {
        return workspaceWrite(userId, () -> input.record().requestId() == null
                ? putLocked(userId, id, input)
                : requests.locked(input.record().requestId(), () -> putLocked(userId, id, input)));
    }

    private ConversationDtos.Detail putLocked(UUID userId, UUID id, ConversationDtos.PutRequest input) {
        if (store.deleted(userId, id)) throw changed();
        if (store.belongsToAnotherUser(userId, id)) throw missing();
        var priorWrite = store.write(userId, input.writeId()).orElse(null);
        if (priorWrite != null) {
            if (!"PUT".equals(priorWrite.operation()) || !id.equals(priorWrite.conversationId()) || priorWrite.responseEnc() == null) throw changed();
            var snapshot = decode(userId, priorWrite.responseEnc(), ConversationDtos.WriteSnapshot.class);
            if (!snapshot.request().equals(input)) throw changed();
            return snapshot.response();
        }
        var prior = store.find(userId, id).orElse(null);
        if (prior == null ? input.expectedRevision() != null : !prior.revision().equals(input.expectedRevision())) throw changed();
        if (prior != null && !Objects.equals(prior.requestId(), input.record().requestId())) throw changed();
        UUID batchId = validateRecord(userId, id, input.record());
        String revision = UUID.randomUUID().toString();
        String encrypted = crypto.encrypt(userId, mapper.writeValueAsString(input.record()));
        store.save(userId, id, input.record(), revision, encrypted, batchId, prior == null);
        if (input.record().requestId() != null) requests.bindConversation(userId, input.record().requestId(), id);
        var result = detail(userId, store.find(userId, id).orElseThrow());
        store.recordWrite(userId, input.writeId(), id, "PUT", crypto.encrypt(userId,
                mapper.writeValueAsString(new ConversationDtos.WriteSnapshot(input, result))));
        return result;
    }

    public ConversationDtos.Detail get(UUID userId, UUID id) { return detail(userId, store.find(userId, id).orElseThrow(this::missing)); }

    public ConversationDtos.Page<ConversationDtos.Summary> page(UUID userId, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw invalid();
        Instant before = null;
        UUID beforeId = null;
        if (cursor != null) {
            try {
                if (cursor.length() > 256) throw invalid();
                String[] pieces = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII).split("\\|", -1);
                if (pieces.length != 3 || !UUID.fromString(pieces[0]).equals(userId)) throw invalid();
                before = Instant.parse(pieces[1]); beforeId = UUID.fromString(pieces[2]);
            } catch (RuntimeException exception) { throw invalid(); }
        }
        var rows = store.page(userId, before, beforeId, limit + 1);
        boolean hasNext = rows.size() > limit;
        var items = new ArrayList<ConversationDtos.Summary>();
        for (var row : rows.subList(0, Math.min(limit, rows.size()))) {
            var record = decode(userId, row.contentEnc(), ConversationDtos.Record.class);
            items.add(new ConversationDtos.Summary(row.id(), record.status(), record.sentence(), record.recordDate(), record.counts(), row.batchId(), row.requestId(), row.createdAt()));
        }
        String next = null;
        if (hasNext) {
            var last = items.getLast();
            next = Base64.getUrlEncoder().withoutPadding().encodeToString((userId + "|" + last.createdAt() + "|" + last.id()).getBytes(StandardCharsets.US_ASCII));
        }
        return new ConversationDtos.Page<>(items, next, hasNext);
    }

    public void delete(UUID userId, UUID id, UUID writeId) {
        workspaceWrite(userId, () -> {
            if (repeatedDeletion(userId, writeId, id, "DELETE")) return null;
            var record = store.find(userId, id).orElse(null);
            if (record == null && !store.deleted(userId, id)) throw missing();
            if (record != null) deleteRow(userId, record);
            store.recordWrite(userId, writeId, id, "DELETE", null);
            return null;
        });
    }

    public void deleteAll(UUID userId, UUID writeId, boolean confirmed) {
        if (!confirmed) throw invalid();
        workspaceWrite(userId, () -> {
            if (repeatedDeletion(userId, writeId, null, "DELETE_ALL")) return null;
            for (UUID id : store.ids(userId)) deleteRow(userId, store.find(userId, id).orElseThrow());
            for (UUID requestId : requests.ids(userId)) requests.locked(requestId, () -> { requests.cancel(userId, requestId); return null; });
            store.recordWrite(userId, writeId, null, "DELETE_ALL", null);
            return null;
        });
    }

    /** BE2 되돌리기 검사는 이 상태를 읽는 공통 계약을 통해 연결한다. */
    public boolean wasDeleted(UUID userId, UUID id) { return store.deleted(userId, id); }

    private <T> T workspaceWrite(UUID userId, java.util.function.Supplier<T> work) {
        return auth.workspaceLocked(userId, () -> {
            if (!auth.user(userId).orElseThrow(this::missing).active()) {
                throw new BusinessException(Be1AuthAiErrorCode.UNAUTHORIZED);
            }
            return work.get();
        });
    }

    private void deleteRow(UUID userId, ConversationStore.Row record) {
        if (record.requestId() != null) requests.locked(record.requestId(), () -> { requests.cancel(userId, record.requestId()); return null; });
        store.delete(userId, record.id());
    }
    private boolean repeatedDeletion(UUID userId, UUID writeId, UUID conversationId, String operation) {
        if (writeId == null) throw invalid();
        var prior = store.write(userId, writeId).orElse(null);
        if (prior == null) return false;
        if (!operation.equals(prior.operation()) || !Objects.equals(conversationId, prior.conversationId())) throw changed();
        return true;
    }

    private UUID validateRecord(UUID userId, UUID conversationId, ConversationDtos.Record record) {
        try {
            if (record.query() != null) validation.query(record.query());
            validation.questions(record.questions());
        } catch (RuntimeException exception) { throw invalid(); }
        if (record.status() == ConversationDtos.Status.QUERY_RESULT) {
            if (record.query() == null || !record.resultRefs().isEmpty() || !record.counts().empty()) throw invalid();
        } else if (record.query() != null) throw invalid();
        if (record.status() != ConversationDtos.Status.SAVED && (!record.resultRefs().isEmpty() || !record.counts().empty())) throw invalid();
        if (record.status() == ConversationDtos.Status.AWAITING_ANSWER && record.requestId() == null) throw invalid();
        if (record.requestId() != null) {
            var request = requests.find(userId, record.requestId()).orElseThrow(this::missing);
            if ("CANCELLED".equals(request.status())) throw changed();
            if (request.conversationId() != null && !conversationId.equals(request.conversationId())) throw changed();
            if (record.status() == ConversationDtos.Status.AWAITING_ANSWER && !"AWAITING_ANSWER".equals(request.status())) throw changed();
            if (record.status() == ConversationDtos.Status.SAVED && (!"COMPLETED".equals(request.status())
                    || record.resultRefs().isEmpty() || !Objects.equals(request.batchId(), record.resultRefs().getFirst().batchId()))) throw changed();
        }
        if (record.status() != ConversationDtos.Status.SAVED) return null;
        if (record.resultRefs().isEmpty()) throw invalid();
        UUID batchId = record.resultRefs().getFirst().batchId();
        if (record.resultRefs().stream().anyMatch(ref -> !batchId.equals(ref.batchId()))) throw invalid();
        var batch = batches.getBatchStatus(userId, batchId);
        if (batch == null || !"COMPLETED".equals(batch.status()) || batch.result() == null) throw invalid();
        var ids = new HashSet<UUID>();
        for (var reference : record.resultRefs()) {
            if (!ids.add(reference.resultId()) || batch.result().results().stream().noneMatch(result -> result.resultId().equals(reference.resultId()))) throw invalid();
        }
        return batchId;
    }

    private ConversationDtos.Detail detail(UUID userId, ConversationStore.Row row) {
        return new ConversationDtos.Detail(row.id(), row.revision(), decode(userId, row.contentEnc(), ConversationDtos.Record.class), row.createdAt(), row.updatedAt());
    }
    private <T> T decode(UUID userId, String ciphertext, Class<T> type) {
        try { return mapper.readValue(crypto.decrypt(userId, ciphertext), type); }
        catch (BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE); }
    }
    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION); }
    private BusinessException missing() { return new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND); }
    private BusinessException changed() { return new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED); }
}
