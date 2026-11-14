package com.bedrock.app.ai.service;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.persistence.AiRequestStore;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.ItemBatchWritePort;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.conversation.persistence.ConversationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service("aiRequestService")
public class AiRequestService {
    private final AiRequestStore store;
    private final AuthStore accounts;
    private final ConversationStore conversations;
    private final AiWorkspaceAccess access;
    private final AiValidation validation;
    private final AiInterpreter interpreter;
    private final InferenceLimits limits;
    private final UserCryptoPort crypto;
    private final ItemBatchWritePort batches;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;
    private final java.time.Clock clock;

    public AiRequestService(AiRequestStore store, AuthStore accounts, ConversationStore conversations, AiWorkspaceAccess access,
            AiValidation validation, AiInterpreter interpreter, InferenceLimits limits, UserCryptoPort crypto,
            ItemBatchWritePort batches, ObjectMapper mapper, MeterRegistry metrics, @Qualifier("be1Clock") java.time.Clock clock) {
        this.store = store; this.accounts = accounts; this.conversations = conversations; this.access = access;
        this.validation = validation; this.interpreter = interpreter; this.limits = limits; this.crypto = crypto;
        this.batches = batches; this.mapper = mapper; this.metrics = metrics;
        this.clock = clock;
    }

    public AiDtos.Interpretation interpret(UUID userId, AiDtos.InterpretationRequest input) {
        validation.input(input);
        access.requireAiConsent(userId);
        var ticket = accounts.locked(userId, () -> store.locked(input.requestId(), () -> {
            var prior = store.find(userId, input.requestId()).orElse(null);
            if (prior != null) {
                Payload payload = payload(userId, prior);
                if (!payload.input().equals(input)) throw changed();
                if (payload.interpretation() != null) return new Ticket(null, result(userId, prior, payload));
                if ("PROCESSING".equals(prior.status()) && prior.updatedAt().isAfter(clock.instant().minusSeconds(65))) throw changed();
                String revision = UUID.randomUUID().toString();
                store.update(userId, input.requestId(), "PROCESSING", revision, encrypt(userId, payload));
                return new Ticket(revision, null);
            }
            String revision = UUID.randomUUID().toString();
            store.insert(userId, input.requestId(), "PROCESSING", revision, encrypt(userId, new Payload(input, List.of(), List.of(), null, Map.of())));
            return new Ticket(revision, null);
        }));
        if (ticket.cached() != null) return ticket.cached();
        try {
            access.validateContext(userId, input);
            var result = limits.execute(userId, false, () -> interpreter.interpret(input, List.of(), List.of()));
            access.requireAiConsent(userId);
            return store.locked(input.requestId(), () -> {
                var current = required(userId, input.requestId());
                if (!current.revision().equals(ticket.revision()) || "CANCELLED".equals(current.status())) throw changed();
                save(userId, input.requestId(), new Payload(input, result.questions(), List.of(), result, Map.of()));
                return result;
            });
        } catch (RuntimeException exception) {
            abandon(userId, input.requestId(), ticket.revision());
            throw exception;
        }
    }

    public AiDtos.RequestView get(UUID userId, UUID requestId) {
        var stored = required(userId, requestId);
        var payload = payload(userId, stored);
        if (payload.interpretation() == null) throw changed();
        var input = payload.input();
        return new AiDtos.RequestView(requestId, input.sentence(), input.referenceTime(), input.referenceTimeZone(), input.context(),
                payload.answers(), result(userId, stored, payload), stored.createdAt(), stored.updatedAt());
    }

    public AiDtos.Interpretation answer(UUID userId, UUID requestId, AiDtos.AnswerRequest input) {
        access.requireAiConsent(userId);
        // 모델 호출 중에는 DB 잠금을 잡지 않는다. 같은 revision을 가진 답의 최종 반영만 한 번 성공한다.
        var snapshot = store.locked(requestId, () -> {
            var stored = required(userId, requestId);
            var payload = payload(userId, stored);
            if ("COMPLETED".equals(stored.status())) return new AnswerTicket(stored.revision(), payload, result(userId, stored, payload));
            var repeated = payload.receipts().get(input.answerId());
            if (repeated != null) {
                if (!repeated.request().equals(input)) throw changed();
                return new AnswerTicket(stored.revision(), payload, repeated.result());
            }
            if (!stored.revision().equals(input.expectedRevision()) || payload.interpretation() == null
                    || payload.interpretation().kind() != AiDtos.Kind.ASK) throw changed();
            validation.answers(input, payload.interpretation());
            if (payload.receipts().size() >= 50) throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
            return new AnswerTicket(stored.revision(), payload, null);
        });
        if (snapshot.cached() != null) return snapshot.cached();
        access.validateContext(userId, snapshot.payload().input());
        Map<String, AiDtos.Answer> accumulated = new LinkedHashMap<>();
        snapshot.payload().answers().forEach(answer -> accumulated.put(answer.questionId(), answer));
        input.answers().forEach(answer -> accumulated.put(answer.questionId(), answer));
        var answers = List.copyOf(accumulated.values());
        var pending = snapshot.payload().interpretation().questions().stream().filter(question -> question.required()
                && !accumulated.containsKey(question.questionId())).toList();
        AiDtos.Interpretation interpreted;
        if (!pending.isEmpty()) {
            var previous = snapshot.payload().interpretation();
            interpreted = new AiDtos.Interpretation(requestId, UUID.randomUUID().toString(), AiDtos.Kind.ASK, previous.operations(), pending,
                    null, previous.warnings(), null, null);
        } else if (input.answers().stream().anyMatch(AiConfirmations::deletionCancelled)) {
            interpreted = new AiDtos.Interpretation(requestId, UUID.randomUUID().toString(), AiDtos.Kind.UNSUPPORTED, List.of(), List.of(), null, List.of(), "삭제를 취소했습니다.", null);
        } else {
            interpreted = limits.execute(userId, false, () -> interpreter.interpret(snapshot.payload().input(), snapshot.payload().questions(), answers));
        }
        access.requireAiConsent(userId);
        AiDtos.Interpretation result = interpreted;
        return store.locked(requestId, () -> {
            var current = required(userId, requestId);
            var currentPayload = payload(userId, current);
            if ("COMPLETED".equals(current.status())) return result(userId, current, currentPayload);
            var prior = currentPayload.receipts().get(input.answerId());
            if (prior != null && prior.request().equals(input)) return prior.result();
            if (!current.revision().equals(snapshot.revision())) throw changed();
            var receipts = new LinkedHashMap<>(currentPayload.receipts());
            receipts.put(input.answerId(), new AnswerReceipt(input, result));
            var questions = new LinkedHashMap<String, AiDtos.Question>();
            currentPayload.questions().forEach(question -> questions.put(question.questionId(), question));
            result.questions().forEach(question -> questions.put(question.questionId(), question));
            save(userId, requestId, new Payload(currentPayload.input(), List.copyOf(questions.values()), answers, result, Map.copyOf(receipts)));
            return result;
        });
    }

    /** BE2의 묶음 쓰기 트랜잭션 안에서 호출할 계약 구현. */
    public UUID lockReadyRequest(UUID userId, UUID requestId, String expectedRevision) {
        requireTransaction();
        return store.locked(requestId, () -> {
            var stored = required(userId, requestId);
            if ("COMPLETED".equals(stored.status())) return stored.batchId();
            var payload = payload(userId, stored);
            if (!stored.revision().equals(expectedRevision) || payload.interpretation() == null || payload.interpretation().kind() != AiDtos.Kind.READY) throw changed();
            return null;
        });
    }

    public void markCommitted(UUID userId, UUID requestId, String expectedRevision, UUID batchId, UUID conversationId) {
        requireTransaction();
        UUID previous = lockReadyRequest(userId, requestId, expectedRevision);
        var stored = required(userId, requestId);
        if (stored.conversationId() != null && conversationId != null && !stored.conversationId().equals(conversationId)) throw changed();
        UUID linkedConversation = stored.conversationId() != null ? stored.conversationId() : conversationId;
        if (batchId == null || linkedConversation != null && (conversations.deleted(userId, linkedConversation)
                || conversations.belongsToAnotherUser(userId, linkedConversation))) throw changed();
        if (previous != null) {
            if (!previous.equals(batchId)) throw changed();
            return;
        }
        store.committed(userId, requestId, batchId, linkedConversation);
    }

    public boolean wasConversationDeleted(UUID userId, UUID conversationId) { return conversations.deleted(userId, conversationId); }

    private AiDtos.Interpretation result(UUID userId, AiRequestStore.Stored stored, Payload payload) {
        if ("COMPLETED".equals(stored.status())) {
            var batch = batches.getBatchStatus(userId, stored.batchId());
            if (batch == null || !"COMPLETED".equals(batch.status()) || batch.result() == null) throw changed();
            return payload.interpretation().committed(stored.batchId());
        }
        return payload.interpretation();
    }
    private void save(UUID userId, UUID requestId, Payload payload) {
        String status = payload.interpretation().kind() == AiDtos.Kind.ASK ? "AWAITING_ANSWER" : "READY";
        store.update(userId, requestId, status, payload.interpretation().revision(), encrypt(userId, payload));
        metrics.counter("bedrock.ai.interpretation", "kind", payload.interpretation().kind().name()).increment();
    }
    private void abandon(UUID userId, UUID id, String revision) {
        try {
            store.locked(id, () -> {
                var stored = store.find(userId, id).orElse(null);
                if (stored != null && revision.equals(stored.revision()) && "PROCESSING".equals(stored.status())) store.update(userId, id, "RETRYABLE", revision, stored.payloadEnc());
                return null;
            });
        } catch (RuntimeException ignored) { /* 만료된 PROCESSING 작업은 65초 뒤 같은 요청으로 재시도한다. */ }
    }
    private AiRequestStore.Stored required(UUID userId, UUID id) {
        return store.find(userId, id).filter(row -> !"CANCELLED".equals(row.status()))
                .orElseThrow(() -> new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND));
    }
    private Payload payload(UUID userId, AiRequestStore.Stored stored) {
        if ("CANCELLED".equals(stored.status())) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND);
        try { return mapper.readValue(crypto.decrypt(userId, stored.payloadEnc()), Payload.class); }
        catch (BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE); }
    }
    private String encrypt(UUID userId, Payload payload) { return crypto.encrypt(userId, mapper.writeValueAsString(payload)); }
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("AI 완료 처리는 묶음 트랜잭션 안에서 실행해야 합니다.");
    }
    private BusinessException changed() { return new BusinessException(Be1AuthAiErrorCode.AI_REQUEST_CHANGED); }
    public record Payload(AiDtos.InterpretationRequest input, List<AiDtos.Question> questions, List<AiDtos.Answer> answers,
            AiDtos.Interpretation interpretation, Map<UUID, AnswerReceipt> receipts) {
        @Override public String toString() { return "AiPayload[redacted]"; }
    }
    public record AnswerReceipt(AiDtos.AnswerRequest request, AiDtos.Interpretation result) {}
    private record Ticket(String revision, AiDtos.Interpretation cached) {}
    private record AnswerTicket(String revision, Payload payload, AiDtos.Interpretation cached) {}
}
