package com.bedrock.app.ai.persistence;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Repository("aiRequestStore")
public class AiRequestStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public AiRequestStore(JdbcTemplate jdbc, PlatformTransactionManager manager) { this.jdbc = jdbc; transaction = new TransactionTemplate(manager); }

    public <T> T locked(UUID requestId, Supplier<T> work) {
        return transaction.execute(status -> {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "bedrock:ai:" + requestId);
            return work.get();
        });
    }

    public Optional<Stored> find(UUID userId, UUID requestId) {
        return jdbc.query("SELECT * FROM ai_pending_requests WHERE user_id = ? AND id = ?", (row, index) -> new Stored(
                row.getObject("id", UUID.class), row.getString("status"), row.getString("revision"), row.getString("context_payload_enc"),
                row.getObject("result_batch_id", UUID.class), row.getObject("conversation_id", UUID.class),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant()), userId, requestId).stream().findFirst();
    }

    public void insert(UUID userId, UUID id, String status, String revision, String encrypted) {
        int count = jdbc.update("INSERT INTO ai_pending_requests (id, user_id, status, revision, context_payload_enc, created_at, updated_at) VALUES (?, ?, ?, ?, ?, date_trunc('second', now()), date_trunc('second', now())) ON CONFLICT DO NOTHING",
                id, userId, status, revision, encrypted);
        if (count != 1) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND);
    }

    public void update(UUID userId, UUID id, String status, String revision, String encrypted) {
        jdbc.update("UPDATE ai_pending_requests SET status = ?, revision = ?, context_payload_enc = ?, updated_at = date_trunc('second', now()) WHERE user_id = ? AND id = ?",
                status, revision, encrypted, userId, id);
    }

    public void committed(UUID userId, UUID id, UUID batchId, UUID conversationId) {
        jdbc.update("UPDATE ai_pending_requests SET status = 'COMPLETED', result_batch_id = ?, conversation_id = ?, updated_at = date_trunc('second', now()) WHERE user_id = ? AND id = ?",
                batchId, conversationId, userId, id);
    }

    /** 같은 요청 잠금을 보유한 대화 저장 트랜잭션에서만 연결한다. */
    public void bindConversation(UUID userId, UUID id, UUID conversationId) {
        int count = jdbc.update("UPDATE ai_pending_requests SET conversation_id = ? WHERE user_id = ? AND id = ? AND (conversation_id IS NULL OR conversation_id = ?)",
                conversationId, userId, id, conversationId);
        if (count != 1) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
    }

    public void cancel(UUID userId, UUID id) {
        // ID를 보존하여 지운 대화의 늦은 답·재전송이 요청을 되살리지 못하게 한다. 글은 즉시 비운다.
        jdbc.update("UPDATE ai_pending_requests SET status = 'CANCELLED', context_payload_enc = '', revision = ?, conversation_id = NULL, updated_at = date_trunc('second', now()) WHERE user_id = ? AND id = ?",
                UUID.randomUUID().toString(), userId, id);
    }

    public java.util.List<UUID> ids(UUID userId) {
        return jdbc.queryForList("SELECT id FROM ai_pending_requests WHERE user_id = ? AND status <> 'CANCELLED' ORDER BY id", UUID.class, userId);
    }

    public record Stored(UUID id, String status, String revision, String payloadEnc, UUID batchId, UUID conversationId,
            Instant createdAt, Instant updatedAt) {
        @Override public String toString() { return "AiRequest[id=" + id + ", status=" + status + "]"; }
    }
}
