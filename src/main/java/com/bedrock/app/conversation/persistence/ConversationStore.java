package com.bedrock.app.conversation.persistence;

import com.bedrock.app.conversation.dto.ConversationDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository("conversationStore")
public class ConversationStore {
    private final JdbcTemplate jdbc;
    public ConversationStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Row> find(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM conversations WHERE user_id = ? AND id = ?", this::row, userId, id).stream().findFirst();
    }
    public List<Row> page(UUID userId, Instant before, UUID beforeId, int count) {
        if (before == null) return jdbc.query("SELECT * FROM conversations WHERE user_id = ? ORDER BY created_at DESC, id DESC LIMIT ?", this::row, userId, count);
        return jdbc.query("SELECT * FROM conversations WHERE user_id = ? AND (created_at, id) < (?, ?) ORDER BY created_at DESC, id DESC LIMIT ?",
                this::row, userId, Timestamp.from(before), beforeId, count);
    }
    public List<UUID> ids(UUID userId) { return jdbc.queryForList("SELECT id FROM conversations WHERE user_id = ? ORDER BY id", UUID.class, userId); }
    public boolean deleted(UUID userId, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM conversation_deleted_ids WHERE user_id = ? AND conversation_id = ?)", Boolean.class, userId, id));
    }
    public boolean belongsToAnotherUser(UUID userId, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM conversations WHERE id = ? AND user_id <> ?)", Boolean.class, id, userId));
    }
    public void save(UUID userId, UUID id, ConversationDtos.Record record, String revision, String encrypted, UUID batchId, boolean create) {
        if (create) {
            jdbc.update("INSERT INTO conversations (id, user_id, status, record_date, content_enc, request_id, related_batch_id, revision, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, date_trunc('second', now()), date_trunc('second', now()))",
                    id, userId, record.status().name(), record.recordDate(), encrypted, record.requestId(), batchId, revision);
        } else {
            jdbc.update("UPDATE conversations SET status = ?, record_date = ?, content_enc = ?, request_id = ?, related_batch_id = ?, revision = ?, updated_at = date_trunc('second', now()) WHERE user_id = ? AND id = ?",
                    record.status().name(), record.recordDate(), encrypted, record.requestId(), batchId, revision, userId, id);
        }
    }
    public Optional<Write> write(UUID userId, UUID writeId) {
        return jdbc.query("SELECT * FROM conversation_writes WHERE user_id = ? AND write_id = ?", (row, index) -> new Write(
                row.getObject("conversation_id", UUID.class), row.getString("operation"), row.getString("response_enc")), userId, writeId).stream().findFirst();
    }
    public void recordWrite(UUID userId, UUID writeId, UUID conversationId, String operation, String encrypted) {
        jdbc.update("INSERT INTO conversation_writes (user_id, write_id, conversation_id, operation, response_enc) VALUES (?, ?, ?, ?, ?)",
                userId, writeId, conversationId, operation, encrypted);
    }
    public void delete(UUID userId, UUID id) {
        jdbc.update("INSERT INTO conversation_deleted_ids (user_id, conversation_id) VALUES (?, ?) ON CONFLICT DO NOTHING", userId, id);
        jdbc.update("UPDATE conversation_writes SET response_enc = NULL WHERE user_id = ? AND conversation_id = ?", userId, id);
        // V10의 공통 트리거가 CONVERSATION tombstone과 sync_seq를 원자적으로 기록한다.
        jdbc.update("DELETE FROM conversations WHERE user_id = ? AND id = ?", userId, id);
    }
    private Row row(ResultSet row, int index) throws SQLException {
        return new Row(row.getObject("id", UUID.class), row.getString("status"), row.getObject("record_date", LocalDate.class),
                row.getString("content_enc"), row.getObject("request_id", UUID.class), row.getObject("related_batch_id", UUID.class),
                row.getString("revision"), row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant());
    }
    public record Row(UUID id, String status, LocalDate recordDate, String contentEnc, UUID requestId, UUID batchId,
            String revision, Instant createdAt, Instant updatedAt) {
        @Override public String toString() { return "Conversation[id=" + id + "]"; }
    }
    public record Write(UUID conversationId, String operation, String responseEnc) {
        @Override public String toString() { return "ConversationWrite[operation=" + operation + "]"; }
    }
}
