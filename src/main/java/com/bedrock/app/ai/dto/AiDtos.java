package com.bedrock.app.ai.dto;

import com.bedrock.app.common.port.ItemBatchWritePort.ItemTarget;
import com.bedrock.app.common.port.ItemBatchWritePort.RecurrenceSpec;
import com.bedrock.app.common.port.ItemBatchWritePort.ScheduleValue;
import com.bedrock.app.common.validation.IanaTimeZone;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AiDtos {
    private AiDtos() {}
    public enum Kind { READY, ASK, QUERY, UNSUPPORTED, COMPLETED }
    public enum OperationKind { CREATE, PATCH, DELETE, POSTPONE }
    public enum Scope { SINGLE, THIS_OCCURRENCE, THIS_AND_FUTURE, ALL }
    public enum PostponeMode { TO_DATE, SKIP_TO_NEXT }
    public enum Nature { SCHEDULE, TODO, NOTE, TIMETABLE }
    public enum TodoStatus { TODO, DONE, CANCELLED, POSTPONED }

    public record InterpretationRequest(@NotNull UUID requestId, @NotBlank @Size(max = 4000) String sentence,
            @NotNull Instant referenceTime, @NotNull @IanaTimeZone String referenceTimeZone,
            @NotNull @Valid Context context, @NotNull @Size(max = 20) List<@NotNull @Valid Candidate> candidates,
            @NotNull @Size(max = 4000) String guideline, UUID pendingItemId, @Size(max = 64) Map<String, UUID> baseFieldVersions) {
        @Override public String toString() { return "InterpretationRequest[id=" + requestId + "]"; }
    }
    public record Context(@NotBlank @Pattern(regexp = "[A-Z_]{1,40}") String module, LocalDate date,
            LocalDate weekStart, LocalDate weekEnd, @Size(max = 40) String selectionKind,
            @NotNull @Size(max = 20) List<@NotNull ItemTarget> selectedTargets) {}
    public record Candidate(@NotNull ItemTarget target, @Size(max = 1000) String name, ScheduleValue schedule,
            Instant dueAt, LocalDate dueDate, @IanaTimeZone String timeZone, RecurrenceSpec recurrence,
            @NotNull @Size(max = 32) List<@NotNull @Min(0) @Max(525600) Integer> alarms,
            @NotNull UUID collectionId, @NotNull @Size(max = 64) Map<String, UUID> baseFieldVersions) {}
    public record AnswerRequest(@NotNull UUID answerId, @NotBlank @Size(max = 64) String expectedRevision,
            @NotEmpty @Size(max = 20) List<@NotNull @Valid Answer> answers) {
        @Override public String toString() { return "AnswerRequest[id=" + answerId + "]"; }
    }
    public record Answer(@NotBlank @Size(max = 64) String questionId, @Size(max = 64) String choiceId, @Size(max = 4000) String text) {}
    public record Interpretation(UUID requestId, String revision, Kind kind, List<Operation> operations,
            List<Question> questions, Query query, List<Warning> warnings, String reason, UUID committedBatchId) {
        public Interpretation committed(UUID batchId) { return new Interpretation(requestId, revision, Kind.COMPLETED, List.of(), List.of(), null, List.of(), null, batchId); }
        @Override public String toString() { return "Interpretation[id=" + requestId + ", kind=" + kind + "]"; }
    }
    public record Operation(UUID operationId, OperationKind kind, ItemTarget target, Scope scope, LocalDate effectiveDate,
            Map<String, JsonNode> proposedFields, List<DateInstruction> dateInstructions, TimeDependency timeDependency,
            PostponeMode postponeMode, boolean deleteDerivedCreatedFromItems) {}
    public record DateInstruction(String fieldKey, String expression, String resolvedValue) {}
    public record TimeDependency(ItemTarget referenceTarget, String referenceField, Integer offsetMinutes, Map<String, UUID> baseFieldVersions) {}
    public record Question(String questionId, String message, List<Choice> choices, boolean required) {}
    public record Choice(String choiceId, String label, String reason, JsonNode value) {}
    public record Query(DateInstruction rangeStart, DateInstruction rangeEnd, List<Nature> natures, List<TodoStatus> todoStatuses,
            List<UUID> collectionIds, String nameContains, String tagContains, Integer freeTimeMinutes) {}
    public record Warning(String code, UUID itemId, List<String> fieldKeys, String message) {}
    public record RequestView(UUID requestId, String sentence, Instant referenceTime, String referenceTimeZone, Context context,
            List<Answer> answers, Interpretation interpretation, Instant createdAt, Instant updatedAt) {}

    /** 구조화 모델 출력 전용: 생략과 명시적 null을 보존하기 위해 칸을 key/value 목록으로 받는다. */
    public record ModelResult(Kind kind, List<ModelOperation> operations, List<Question> questions, Query query, List<Warning> warnings, String reason) {}
    public record ModelOperation(OperationKind kind, ItemTarget target, Scope scope, LocalDate effectiveDate, List<Field> fields,
            List<DateInstruction> dateInstructions, ModelDependency timeDependency, PostponeMode postponeMode, boolean deleteDerivedCreatedFromItems) {}
    public record Field(String key, JsonNode value) {}
    public record ModelDependency(ItemTarget referenceTarget, String referenceField, Integer offsetMinutes) {}
}
