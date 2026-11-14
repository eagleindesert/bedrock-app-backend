package com.bedrock.app.ai.service;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.common.domain.ItemTimeZones;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.ItemBatchWritePort.ItemTarget;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

@Component("aiValidation")
public class AiValidation {
    public static final Set<String> FIELD_KEYS = Set.of("name", "body", "location", "tags", "color", "hasSchedule", "startAt", "endAt",
            "startDate", "endDate", "timeZone", "isAllDay", "hasTodo", "todoState", "dueAt", "dueDate", "priority",
            "estimatedMinutes", "alarms", "hasNote", "recurrence", "timetable");
    private static final Set<String> DATES = Set.of("startAt", "endAt", "dueAt", "startDate", "endDate", "dueDate", "effectiveDate", "rangeStart", "rangeEnd");
    private final ObjectMapper mapper;
    private final Validator validator;
    public AiValidation(ObjectMapper mapper, Validator validator) { this.mapper = mapper; this.validator = validator; }

    public <T> T readRequest(JsonNode node, Class<T> type) {
        try {
            if (node == null || node.toString().length() > 131072) throw invalid();
            T value = mapper.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(node);
            if (!validator.validate(value).isEmpty()) throw invalid();
            return value;
        } catch (BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw invalid(); }
    }

    public void input(AiDtos.InterpretationRequest request) {
        if (!validator.validate(request).isEmpty()) throw invalid();
        var context = request.context();
        if ((context.weekStart() == null) != (context.weekEnd() == null)
                || context.weekStart() != null && (!context.weekEnd().isAfter(context.weekStart())
                || java.time.temporal.ChronoUnit.DAYS.between(context.weekStart(), context.weekEnd()) > 7)) throw invalid();
        var seen = new HashSet<ItemTarget>();
        for (var target : context.selectedTargets()) { target(target); if (!seen.add(target)) throw invalid(); }
        seen.clear();
        for (var candidate : request.candidates()) {
            target(candidate.target());
            if (!seen.add(candidate.target()) || candidate.dueAt() != null && candidate.dueDate() != null) throw invalid();
            if (candidate.schedule() != null) {
                var schedule = candidate.schedule();
                if (schedule.isAllDay()) {
                    if (schedule.startAt() != null || schedule.endAt() != null || schedule.startDate() == null
                            || schedule.endDate() != null && !schedule.endDate().isAfter(schedule.startDate())) throw invalid();
                } else if (schedule.startDate() != null || schedule.endDate() != null || schedule.startAt() == null
                        || schedule.endAt() != null && schedule.endAt().isBefore(schedule.startAt())) throw invalid();
            }
            if (candidate.recurrence() != null) {
                try { recurrence(mapper.valueToTree(candidate.recurrence())); }
                catch (RuntimeException exception) { throw invalid(); }
            }
        }
        if (request.pendingItemId() != null && request.baseFieldVersions() == null) throw invalid();
    }

    public List<ItemTarget> allowedTargets(AiDtos.InterpretationRequest request) {
        var targets = new LinkedHashSet<>(request.context().selectedTargets());
        request.candidates().forEach(candidate -> targets.add(candidate.target()));
        return List.copyOf(targets);
    }

    public AiDtos.Interpretation output(UUID requestId, String revision, JsonNode node, AiDtos.InterpretationRequest input,
            List<AiDtos.Answer> answers) {
        try {
            var model = mapper.readerFor(AiDtos.ModelResult.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .<AiDtos.ModelResult>readValue(node);
            if (model.kind() == null || model.kind() == AiDtos.Kind.COMPLETED || model.operations() == null || model.operations().size() > 20
                    || model.questions() == null || model.questions().size() > 20 || model.warnings() == null || model.warnings().size() > 40) throw malformed();
            Set<ItemTarget> allowed = Set.copyOf(allowedTargets(input));
            List<AiDtos.Operation> operations = new ArrayList<>();
            boolean unknownTarget = false;
            for (var operation : model.operations()) {
                if (operation == null || operation.kind() == null || operation.fields() == null || operation.fields().size() > FIELD_KEYS.size()
                        || operation.dateInstructions() == null || operation.dateInstructions().size() > 12) throw malformed();
                if (operation.kind() != AiDtos.OperationKind.CREATE && (operation.target() == null || !allowed.contains(operation.target()))) unknownTarget = true;
                if (operation.kind() == AiDtos.OperationKind.CREATE && operation.target() != null) throw malformed();
                if (operation.target() != null) target(operation.target());
                if (operation.kind() != AiDtos.OperationKind.CREATE && operation.scope() == null) unknownTarget = true;
                if (operation.scope() == AiDtos.Scope.THIS_AND_FUTURE && operation.effectiveDate() == null) unknownTarget = true;
                if (operation.scope() == AiDtos.Scope.THIS_OCCURRENCE && (operation.target() == null || operation.target().occurrence() == null)) unknownTarget = true;
                if (operation.kind() == AiDtos.OperationKind.POSTPONE && operation.postponeMode() == null) throw malformed();
                var fields = new LinkedHashMap<String, JsonNode>();
                for (var field : operation.fields()) {
                    if (field == null || !FIELD_KEYS.contains(field.key()) || field.value() == null || fields.containsKey(field.key())) throw malformed();
                    field(field.key(), field.value());
                    fields.put(field.key(), field.value());
                }
                if ((operation.kind() == AiDtos.OperationKind.DELETE || operation.kind() == AiDtos.OperationKind.POSTPONE) && !fields.isEmpty()) throw malformed();
                if (operation.kind() == AiDtos.OperationKind.PATCH && fields.isEmpty() && operation.dateInstructions().isEmpty()) throw malformed();
                validatePairedTimes(fields);
                for (var instruction : operation.dateInstructions()) date(instruction);
                AiDtos.TimeDependency dependency = null;
                if (operation.timeDependency() != null) {
                    var reference = operation.timeDependency();
                    if (reference.referenceTarget() == null || !allowed.contains(reference.referenceTarget())) unknownTarget = true;
                    if (!Set.of("startAt", "endAt", "dueAt", "startDate", "endDate", "dueDate").contains(reference.referenceField())
                            || reference.offsetMinutes() == null || Math.abs((long) reference.offsetMinutes()) > 525600) throw malformed();
                    var candidate = input.candidates().stream().filter(item -> item.target().equals(reference.referenceTarget())).findFirst();
                    if (candidate.isEmpty()) unknownTarget = true;
                    dependency = new AiDtos.TimeDependency(reference.referenceTarget(), reference.referenceField(), reference.offsetMinutes(),
                            candidate.map(AiDtos.Candidate::baseFieldVersions).orElse(Map.of()));
                }
                operations.add(new AiDtos.Operation(UUID.randomUUID(), operation.kind(), operation.target(), operation.scope(), operation.effectiveDate(),
                        Collections.unmodifiableMap(fields), operation.dateInstructions(), dependency, operation.postponeMode(), operation.deleteDerivedCreatedFromItems()));
            }
            questions(model.questions());
            for (var warning : model.warnings()) {
                if (warning == null || !shortText(warning.code(), 64) || !shortText(warning.message(), 1000)
                        || warning.fieldKeys() == null || warning.fieldKeys().size() > 64) throw malformed();
                if (warning.itemId() != null && allowed.stream().noneMatch(target -> warning.itemId().equals(target.itemId()))) throw malformed();
            }
            if (model.query() != null) {
                query(model.query());
                var collections = input.candidates().stream().map(AiDtos.Candidate::collectionId).collect(java.util.stream.Collectors.toSet());
                if (!collections.containsAll(model.query().collectionIds())) return ask(requestId, revision, "collection_" + revision.substring(0, 8), "조회할 컬렉션을 골라 주세요.");
            }
            if (model.kind() == AiDtos.Kind.READY && (operations.isEmpty() || !model.questions().isEmpty() || model.query() != null)
                    || model.kind() == AiDtos.Kind.ASK && (model.questions().isEmpty() || model.query() != null)
                    || model.kind() == AiDtos.Kind.QUERY && (model.query() == null || !operations.isEmpty() || !model.questions().isEmpty())
                    || model.kind() == AiDtos.Kind.UNSUPPORTED && (!shortText(model.reason(), 1000) || !operations.isEmpty() || !model.questions().isEmpty() || model.query() != null)) throw malformed();
            if (unknownTarget) return ask(requestId, revision, "target_" + revision.substring(0, 8), "어떤 항목과 반복 범위를 바꿀지 골라 주세요.");
            boolean destructive = operations.stream().filter(op -> op.kind() == AiDtos.OperationKind.DELETE).count() > 1
                    || operations.stream().anyMatch(op -> op.kind() == AiDtos.OperationKind.DELETE && (op.scope() == AiDtos.Scope.ALL || op.scope() == AiDtos.Scope.THIS_AND_FUTURE));
            String deletePlan = operations.stream().filter(op -> op.kind() == AiDtos.OperationKind.DELETE)
                    .map(op -> op.target() + ":" + op.scope() + ":" + op.effectiveDate() + ":" + op.deleteDerivedCreatedFromItems()).sorted().collect(java.util.stream.Collectors.joining("|"));
            String confirmId = "confirm_delete_" + com.bedrock.app.auth.service.AuthSessions.fingerprint(deletePlan).substring(0, 24);
            if (model.kind() == AiDtos.Kind.READY && destructive && answers.stream().noneMatch(answer -> confirmId.equals(answer.questionId())
                    && AiConfirmations.deleteConfirmed(answer))) {
                var question = new AiDtos.Question(confirmId, "여러 항목 또는 반복 전체를 삭제할까요?",
                        List.of(new AiDtos.Choice("confirm", "삭제", "선택한 범위 전체를 삭제합니다.", mapper.valueToTree(true)),
                                new AiDtos.Choice("cancel", "취소", "삭제하지 않습니다.", mapper.valueToTree(false))), true);
                return new AiDtos.Interpretation(requestId, revision, AiDtos.Kind.ASK, operations, List.of(question), null, model.warnings(), null, null);
            }
            var questions = model.questions().stream().map(question -> new AiDtos.Question(revision.substring(0, 8) + "_" + question.questionId().substring(0, Math.min(55, question.questionId().length())),
                    question.message(), question.choices(), question.required())).toList();
            return new AiDtos.Interpretation(requestId, revision, model.kind(), operations, questions, model.query(), model.warnings(), model.reason(), null);
        } catch (BusinessException exception) { throw malformed(); }
        catch (RuntimeException exception) { throw malformed(); }
    }

    public void answers(AiDtos.AnswerRequest request, AiDtos.Interpretation current) {
        if (!validator.validate(request).isEmpty()) throw invalid();
        Set<String> ids = new HashSet<>();
        for (var answer : request.answers()) {
            var question = current.questions().stream().filter(q -> q.questionId().equals(answer.questionId())).findFirst().orElseThrow(this::invalid);
            if (!ids.add(answer.questionId()) || (answer.choiceId() == null || answer.choiceId().isBlank()) && (answer.text() == null || answer.text().isBlank())) throw invalid();
            if (answer.choiceId() != null && question.choices().stream().noneMatch(choice -> choice.choiceId().equals(answer.choiceId()))) throw invalid();
        }
    }

    public void questions(List<AiDtos.Question> questions) {
        if (questions == null || questions.size() > 20) throw malformed();
        Set<String> ids = new HashSet<>();
        for (var question : questions) {
            if (question == null || !shortText(question.questionId(), 64) || !ids.add(question.questionId()) || !shortText(question.message(), 1000)
                    || question.choices() == null || question.choices().size() > 20) throw malformed();
            Set<String> choices = new HashSet<>();
            for (var choice : question.choices()) {
                if (choice == null || !shortText(choice.choiceId(), 64) || !choices.add(choice.choiceId()) || !shortText(choice.label(), 300)
                        || !shortText(choice.reason(), 1000) || choice.value() == null || choice.value().toString().length() > 2048) throw malformed();
            }
        }
    }

    public void query(AiDtos.Query query) {
        if (query.natures() == null || query.natures().size() > 4 || query.todoStatuses() == null || query.todoStatuses().size() > 8
                || query.collectionIds() == null || query.collectionIds().size() > 20 || query.collectionIds().contains(null)
                || query.nameContains() != null && query.nameContains().length() > 1000 || query.tagContains() != null && query.tagContains().length() > 100
                || query.freeTimeMinutes() != null && (query.freeTimeMinutes() < 1 || query.freeTimeMinutes() > 1440)) throw malformed();
        if (query.rangeStart() != null) date(query.rangeStart());
        if (query.rangeEnd() != null) date(query.rangeEnd());
    }

    public AiDtos.Interpretation ask(UUID id, String revision, String questionId, String message) {
        return new AiDtos.Interpretation(id, revision, AiDtos.Kind.ASK, List.of(),
                List.of(new AiDtos.Question(questionId, message, List.of(), true)), null, List.of(), null, null);
    }

    private void field(String key, JsonNode value) {
        if (value.isNull()) return;
        switch (key) {
            case "name", "body", "location", "color" -> { if (!value.isString() || value.asText().length() > (key.equals("body") ? 10000 : 1000)) throw malformed(); }
            case "timeZone" -> { if (!value.isString() || !ItemTimeZones.isIanaZoneId(value.asText())) throw malformed(); }
            case "hasSchedule", "isAllDay", "hasTodo", "hasNote" -> { if (!value.isBoolean()) throw malformed(); }
            case "startAt", "endAt", "dueAt" -> { if (!value.isString() || !value.asText().endsWith("Z")) throw malformed(); Instant.parse(value.asText()); }
            case "startDate", "endDate", "dueDate" -> { if (!value.isString()) throw malformed(); LocalDate.parse(value.asText()); }
            case "priority", "estimatedMinutes" -> { if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1 || value.asInt() > (key.equals("priority") ? 5 : 525600)) throw malformed(); }
            case "tags", "alarms" -> {
                if (!value.isArray() || value.size() > 32) throw malformed();
                for (var item : value) if (key.equals("tags") ? !item.isString() || item.asText().length() > 100 : !item.isIntegralNumber() || !item.canConvertToInt() || item.asInt() < 0 || item.asInt() > 525600) throw malformed();
            }
            case "todoState" -> {
                objectKeys(value, Set.of("status", "completedAt"));
                AiDtos.TodoStatus.valueOf(value.path("status").asText());
                if (!value.path("completedAt").isNull()) Instant.parse(value.path("completedAt").asText());
            }
            case "recurrence" -> recurrence(value);
            case "timetable" -> {
                objectKeys(value, Set.of("slots", "periodStart", "periodEnd", "seriesRootId", "effectiveFrom"));
                if (!value.path("slots").isArray() || value.path("slots").isEmpty() || value.path("slots").size() > 30) throw malformed();
                Set<UUID> slotIds = new HashSet<>();
                for (var slot : value.path("slots")) {
                    objectKeys(slot, Set.of("slotId", "dayOfWeek", "startTime", "endTime"));
                    if (!slotIds.add(UUID.fromString(slot.path("slotId").asText()))) throw malformed();
                    var day = slot.path("dayOfWeek");
                    if (!day.isIntegralNumber() || !day.canConvertToInt() || day.asInt() < 1 || day.asInt() > 7) throw malformed();
                    if (!slot.path("startTime").asText().matches("[0-9]{2}:[0-9]{2}")
                            || !slot.path("endTime").asText().matches("[0-9]{2}:[0-9]{2}")
                            || !LocalTime.parse(slot.path("endTime").asText()).isAfter(LocalTime.parse(slot.path("startTime").asText()))) throw malformed();
                }
                LocalDate start = LocalDate.parse(value.path("periodStart").asText());
                if (value.hasNonNull("periodEnd") && LocalDate.parse(value.path("periodEnd").asText()).isBefore(start)) throw malformed();
                if (value.hasNonNull("seriesRootId")) UUID.fromString(value.path("seriesRootId").asText());
                if (value.hasNonNull("effectiveFrom")) LocalDate.parse(value.path("effectiveFrom").asText());
            }
            default -> throw malformed();
        }
    }

    private void recurrence(JsonNode value) {
        objectKeys(value, Set.of("rrule", "rdates", "exdates", "untilDate", "effectiveFrom"));
        if (!value.path("rrule").isString()
                || !com.bedrock.app.common.domain.RecurrenceRule.isSupported(value.path("rrule").asText())) throw malformed();
        for (String field : List.of("rdates", "exdates")) {
            if (!value.path(field).isArray() || value.path(field).size() > 366) throw malformed();
            for (var date : value.path(field)) LocalDate.parse(date.asText());
        }
        for (String field : List.of("untilDate", "effectiveFrom")) if (value.hasNonNull(field)) LocalDate.parse(value.path(field).asText());
    }
    private void validatePairedTimes(Map<String, JsonNode> fields) {
        if (present(fields, "dueAt") && present(fields, "dueDate")) throw malformed();
        if (present(fields, "startAt") && present(fields, "endAt") && Instant.parse(fields.get("endAt").asText()).isBefore(Instant.parse(fields.get("startAt").asText()))) throw malformed();
        if (present(fields, "startDate") && present(fields, "endDate") && !LocalDate.parse(fields.get("endDate").asText()).isAfter(LocalDate.parse(fields.get("startDate").asText()))) throw malformed();
    }
    private boolean present(Map<String, JsonNode> fields, String key) { return fields.containsKey(key) && !fields.get(key).isNull(); }
    private void target(ItemTarget target) {
        if (target == null || target.itemId() == null || target.occurrence() != null && (target.occurrence().recurrenceDate() == null
                || !target.itemId().equals(target.occurrence().masterItemId()))) throw invalid();
    }
    private void date(AiDtos.DateInstruction date) {
        if (date == null || !DATES.contains(date.fieldKey()) || !shortText(date.expression(), 512)
                || date.resolvedValue() != null && date.resolvedValue().length() > 64) throw malformed();
        if (date.resolvedValue() == null) return;
        if (Set.of("startAt", "endAt", "dueAt").contains(date.fieldKey())) {
            if (!date.resolvedValue().endsWith("Z")) throw malformed();
            Instant.parse(date.resolvedValue());
        } else if (Set.of("rangeStart", "rangeEnd").contains(date.fieldKey()) && date.resolvedValue().endsWith("Z")) {
            Instant.parse(date.resolvedValue());
        } else LocalDate.parse(date.resolvedValue());
    }
    private void objectKeys(JsonNode value, Set<String> keys) {
        if (!value.isObject() || !keys.containsAll(value.propertyNames())) throw malformed();
    }
    private boolean shortText(String value, int limit) { return value != null && !value.isBlank() && value.length() <= limit; }
    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION); }
    private BusinessException malformed() { return new BusinessException(Be1AuthAiErrorCode.AI_TIMEOUT_OR_INVALID_SCHEMA); }
}
