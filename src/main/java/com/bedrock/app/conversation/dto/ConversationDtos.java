package com.bedrock.app.conversation.dto;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.dto.AiDtos.Answer;
import com.bedrock.app.common.validation.IanaTimeZone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ConversationDtos {
    private ConversationDtos() {}
    public enum Status { SAVED, QUERY_RESULT, AWAITING_ANSWER, PENDING_INBOX }
    public record PutRequest(@NotNull UUID writeId, @Size(max = 64) String expectedRevision, @NotNull @Valid Record record) {
        @Override public String toString() { return "ConversationPut[writeId=" + writeId + "]"; }
    }
    public record Record(@NotNull Status status, @NotBlank @Size(max = 4000) String sentence, @NotNull Instant referenceTime,
            @NotNull @IanaTimeZone String referenceTimeZone, @NotNull LocalDate recordDate, UUID requestId,
            @NotNull @Valid AiDtos.Context context, @NotNull @Size(max = 20) List<AiDtos.Question> questions,
            @NotNull @Size(max = 100) List<@NotNull @Valid Answer> answers,
            @NotNull @Size(max = 100) List<@NotNull @Valid ResultRef> resultRefs, AiDtos.Query query, @NotNull @Valid Counts counts) {
        @Override public String toString() { return "ConversationRecord[status=" + status + "]"; }
    }
    public record ResultRef(@NotNull UUID batchId, @NotNull UUID resultId, @Size(max = 2000) String summary) {}
    public record Counts(@Min(0) @Max(100) int createdScheduleCount, @Min(0) @Max(100) int createdTodoCount,
            @Min(0) @Max(100) int updatedCount, @Min(0) @Max(100) int deletedCount) {
        public boolean empty() { return createdScheduleCount == 0 && createdTodoCount == 0 && updatedCount == 0 && deletedCount == 0; }
    }
    public record Detail(UUID id, String revision, Record record, Instant createdAt, Instant updatedAt) {}
    public record Summary(UUID id, Status status, String sentence, LocalDate recordDate, Counts counts, UUID relatedBatchId,
            UUID requestId, Instant createdAt) {}
    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}
    public record WriteSnapshot(PutRequest request, Detail response) {}
}
