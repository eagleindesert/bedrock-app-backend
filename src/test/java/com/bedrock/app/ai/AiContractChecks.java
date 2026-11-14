package com.bedrock.app.ai;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.ai.service.AiValidation;
import com.bedrock.app.ai.service.StructuredSchemas;
import com.bedrock.app.ai.service.StructuredSchemaValidator;
import com.bedrock.app.ai.service.StructuredModelClient;
import com.bedrock.app.ai.service.AiInterpreter;
import com.bedrock.app.ai.service.AiConfirmations;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.ItemBatchWritePort.ItemTarget;
import jakarta.validation.Validation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.math.BigInteger;
import java.util.*;

public final class AiContractChecks {
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    public static void main(String[] args) {
        inputPrivacy(); candidateBoundary(); destructiveConfirmation(); schemaStructure(); answerValidation();
        strictResponseStructure(); timetableAndNumericBounds(); delayedDateConfirmation(); badCandidateRecurrenceCode();
        System.out.println("AI: 9 contract groups passed");
    }
    static void inputPrivacy() {
        var validation = validation();
        var input = input(UUID.randomUUID());
        validation.input(input);
        ObjectNode json = MAPPER.valueToTree(input);
        ((ObjectNode) json.path("candidates").get(0)).put("body", "workspace body must not leave the client");
        reject(() -> validation.readRequest(json, AiDtos.InterpretationRequest.class));
        ObjectNode badZone = MAPPER.valueToTree(input);
        badZone.put("referenceTimeZone", "+09:00");
        reject(() -> validation.readRequest(badZone, AiDtos.InterpretationRequest.class));
    }
    static void candidateBoundary() {
        var validation = validation();
        UUID target = UUID.randomUUID(); var input = input(target);
        String revision = UUID.randomUUID().toString();
        var allowed = validation.output(input.requestId(), revision, model("PATCH", target, "SINGLE"), input, List.of());
        require(allowed.kind() == AiDtos.Kind.READY && allowed.operations().size() == 1, "소유 검증된 후보에 대한 계획");
        var outside = validation.output(input.requestId(), revision, model("PATCH", UUID.randomUUID(), "SINGLE"), input, List.of());
        require(outside.kind() == AiDtos.Kind.ASK && outside.operations().isEmpty(), "후보 밖 대상은 되묻기");
        var badFields = model("PATCH", target, "SINGLE");
        ((ObjectNode) badFields.path("operations").get(0).path("fields").get(0)).put("key", "sql");
        reject(() -> validation.output(input.requestId(), revision, badFields, input, List.of()));
        var recurrencePlan = model("PATCH", target, "SINGLE");
        ObjectNode recurrenceField = (ObjectNode) recurrencePlan.path("operations").get(0).path("fields").get(0);
        recurrenceField.put("key", "recurrence");
        ObjectNode recurrence = recurrenceField.putObject("value");
        recurrence.put("rrule", "FREQ=WEEKLY;BYDAY=MO,WE");
        recurrence.putArray("rdates"); recurrence.putArray("exdates");
        require(validation.output(input.requestId(), revision, recurrencePlan, input, List.of()).kind() == AiDtos.Kind.READY, "공통 반복 규칙 검증");
        recurrence.put("rrule", "FREQ=MONTHLY;BYDAY=3TU");
        reject(() -> validation.output(input.requestId(), revision, recurrencePlan, input, List.of()));
    }
    static void destructiveConfirmation() {
        var validation = validation(); var input = input(UUID.randomUUID());
        var model = model("DELETE", input.candidates().getFirst().target().itemId(), "ALL");
        var first = validation.output(input.requestId(), UUID.randomUUID().toString(), model, input, List.of());
        require(first.kind() == AiDtos.Kind.ASK, "반복 전체 삭제 확인");
        var answer = new AiDtos.Answer(first.questions().getFirst().questionId(), "confirm", null);
        var confirmed = validation.output(input.requestId(), UUID.randomUUID().toString(), model, input, List.of(answer));
        require(confirmed.kind() == AiDtos.Kind.READY, "같은 삭제 범위에만 확인 적용");
        ((ObjectNode) model.path("operations").get(0)).put("deleteDerivedCreatedFromItems", true);
        var expanded = validation.output(input.requestId(), UUID.randomUUID().toString(), model, input, List.of(answer));
        require(expanded.kind() == AiDtos.Kind.ASK, "확인 뒤 삭제 범위 확대는 다시 확인");
    }
    static void schemaStructure() {
        var schemas = new StructuredSchemas(MAPPER);
        checkSchema(schemas.interpretation()); checkSchema(schemas.timetable());
    }
    static void answerValidation() {
        var validation = validation();
        var current = validation.ask(UUID.randomUUID(), UUID.randomUUID().toString(), "question", "날짜를 알려 주세요.");
        validation.answers(new AiDtos.AnswerRequest(UUID.randomUUID(), current.revision(), List.of(new AiDtos.Answer("question", null, "다음 주 월요일"))), current);
        reject(() -> validation.answers(new AiDtos.AnswerRequest(UUID.randomUUID(), current.revision(), List.of(new AiDtos.Answer("missing", null, "answer"))), current));
        reject(() -> validation.answers(new AiDtos.AnswerRequest(UUID.randomUUID(), current.revision(), List.of(new AiDtos.Answer("question", "invented", null))), current));
        require(AiConfirmations.deletionCancelled(new AiDtos.Answer("confirm_delete_plan", null, "취소")), "자유 입력 취소");
        require(!AiConfirmations.deleteConfirmed(new AiDtos.Answer("confirm_delete_plan", "confirm", "아니요")), "모순된 삭제 답 거부");
    }
    static void strictResponseStructure() {
        var schema = new StructuredSchemas(MAPPER).interpretation();
        var result = model("PATCH", UUID.randomUUID(), "SINGLE");
        StructuredSchemaValidator.validate(result, schema);
        var missing = result.deepCopy();
        ((ObjectNode) missing.path("operations").get(0)).remove("deleteDerivedCreatedFromItems");
        reject(() -> StructuredSchemaValidator.validate(missing, schema));
        var coerced = result.deepCopy();
        ((ObjectNode) coerced.path("operations").get(0)).put("deleteDerivedCreatedFromItems", "false");
        reject(() -> StructuredSchemaValidator.validate(coerced, schema));
        var extra = result.deepCopy(); extra.put("unexpected", "text");
        reject(() -> StructuredSchemaValidator.validate(extra, schema));
        var ask = result.deepCopy(); ask.put("kind", "ASK"); ask.putArray("operations");
        var question = ask.putArray("questions").addObject().put("questionId", "date").put("message", "언제인가요?");
        question.putArray("choices");
        reject(() -> StructuredSchemaValidator.validate(ask, schema));
        question.put("required", true);
        StructuredSchemaValidator.validate(ask, schema);
        var timetableSchema = new StructuredSchemas(MAPPER).timetable();
        var timetable = MAPPER.readTree("{\"items\":[{\"candidateId\":\"1\",\"name\":null,\"location\":null,\"slots\":[{\"dayOfWeek\":null,\"startTime\":null,\"endTime\":null}]}],\"warnings\":[]}");
        StructuredSchemaValidator.validate(timetable, timetableSchema);
        ((ObjectNode) timetable.path("items").get(0).path("slots").get(0)).put("dayOfWeek", "Monday");
        reject(() -> StructuredSchemaValidator.validate(timetable, timetableSchema));
        reject(() -> StructuredSchemaValidator.validate(result, MAPPER.readTree("{\"$ref\":\"https://example.invalid/schema\"}")));
    }
    static void timetableAndNumericBounds() {
        var validation = validation(); var input = input(UUID.randomUUID()); var revision = UUID.randomUUID().toString();
        var plan = model("PATCH", input.candidates().getFirst().target().itemId(), "SINGLE");
        var field = (ObjectNode) plan.path("operations").get(0).path("fields").get(0);
        field.put("key", "timetable");
        var timetable = field.putObject("value").put("periodStart", "2026-11-15").putNull("periodEnd").putNull("seriesRootId").putNull("effectiveFrom");
        var slots = timetable.putArray("slots");
        var slot = slots.addObject().put("slotId", UUID.randomUUID().toString()).put("dayOfWeek", 7).put("startTime", "09:00").put("endTime", "10:00");
        StructuredSchemaValidator.validate(plan, new StructuredSchemas(MAPPER).interpretation());
        require(validation.output(input.requestId(), revision, plan, input, List.of()).kind() == AiDtos.Kind.READY, "끝 없는 시간표 허용");
        timetable.put("periodEnd", "2026-11-15");
        require(validation.output(input.requestId(), revision, plan, input, List.of()).kind() == AiDtos.Kind.READY, "종료일 포함 하루 시간표 허용");
        timetable.put("periodEnd", "2026-11-14");
        reject(() -> validation.output(input.requestId(), revision, plan, input, List.of()));
        timetable.putNull("periodEnd");
        slots.add(slot.deepCopy());
        reject(() -> validation.output(input.requestId(), revision, plan, input, List.of()));
        slots.remove(1); slot.put("startTime", "09:00:01");
        reject(() -> validation.output(input.requestId(), revision, plan, input, List.of()));
        slot.put("startTime", "09:00"); slot.put("dayOfWeek", new BigInteger("18446744073709551617"));
        reject(() -> validation.output(input.requestId(), revision, plan, input, List.of()));
        field.put("key", "priority").put("value", new BigInteger("18446744073709551617"));
        reject(() -> validation.output(input.requestId(), revision, plan, input, List.of()));
        field.put("key", "alarms").putArray("value").add(new BigInteger("18446744073709551617"));
        reject(() -> validation.output(input.requestId(), revision, plan, input, List.of()));
    }
    static void delayedDateConfirmation() {
        var original = input(UUID.randomUUID());
        var input = new AiDtos.InterpretationRequest(original.requestId(), original.sentence(), original.referenceTime(), original.referenceTimeZone(),
                original.context(), original.candidates(), original.guideline(), UUID.randomUUID(), Map.of());
        var plan = model("PATCH", input.candidates().getFirst().target().itemId(), "SINGLE");
        var operation = (ObjectNode) plan.path("operations").get(0);
        operation.putArray("fields");
        var date = operation.putArray("dateInstructions").addObject().put("fieldKey", "dueDate").put("expression", "어제").put("resolvedValue", "2026-11-14");
        var model = new StructuredModelClient(MAPPER, "", "") {
            @Override public JsonNode generate(String instructions, Object payload, JsonNode schema, String name, Duration timeout) { return plan; }
        };
        var interpreter = new AiInterpreter(model, new StructuredSchemas(MAPPER), validation(), MAPPER,
                Clock.fixed(Instant.parse("2026-11-15T03:00:00Z"), ZoneOffset.UTC), 15000);
        var first = interpreter.interpret(input, List.of(), List.of());
        require(first.kind() == AiDtos.Kind.ASK && first.questions().getFirst().questionId().startsWith("past_"), "날짜 지시의 과거 값도 재확인");
        var answer = new AiDtos.Answer(first.questions().getFirst().questionId(), null, "예");
        require(interpreter.interpret(input, first.questions(), List.of(answer)).kind() == AiDtos.Kind.READY, "자유 입력으로 원래 날짜 유지");
        date.put("resolvedValue", "2026-11-13");
        require(interpreter.interpret(input, first.questions(), List.of(answer)).kind() == AiDtos.Kind.ASK, "다른 과거 날짜로 바뀌면 다시 확인");
        date.put("resolvedValue", "not-a-date");
        reject(() -> interpreter.interpret(input, List.of(), List.of()));
    }
    static void badCandidateRecurrenceCode() {
        var node = (ObjectNode) MAPPER.valueToTree(input(UUID.randomUUID()));
        var recurrence = ((ObjectNode) node.path("candidates").get(0)).putObject("recurrence");
        recurrence.put("rrule", "FREQ=MONTHLY;BYDAY=3TU"); recurrence.putArray("rdates"); recurrence.putArray("exdates");
        var validation = validation();
        var request = validation.readRequest(node, AiDtos.InterpretationRequest.class);
        try { validation.input(request); throw new AssertionError("잘못된 후보 반복 규칙을 허용했습니다."); }
        catch (BusinessException expected) { require(expected.getErrorCode() == Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION, "클라이언트 입력 오류는 400 검증 오류"); }
    }
    private static AiValidation validation() { return new AiValidation(MAPPER, Validation.buildDefaultValidatorFactory().getValidator()); }
    private static AiDtos.InterpretationRequest input(UUID itemId) {
        return new AiDtos.InterpretationRequest(UUID.randomUUID(), "회의 이름을 고쳐 줘", Instant.parse("2026-11-15T00:00:00Z"), "Asia/Seoul",
                new AiDtos.Context("HOME", null, null, null, null, List.of()),
                List.of(new AiDtos.Candidate(new ItemTarget(itemId, null), "회의", null, null, null, "Asia/Seoul", null, List.of(), UUID.randomUUID(), Map.of())), "", null, null);
    }
    private static ObjectNode model(String kind, UUID target, String scope) {
        ObjectNode root = MAPPER.createObjectNode(); root.put("kind", "READY"); root.putNull("query"); root.putNull("reason"); root.putArray("questions"); root.putArray("warnings");
        ObjectNode operation = root.putArray("operations").addObject(); operation.put("kind", kind); operation.set("target", MAPPER.valueToTree(new ItemTarget(target, null)));
        operation.put("scope", scope); operation.putNull("effectiveDate"); operation.putNull("timeDependency"); operation.putNull("postponeMode");
        operation.put("deleteDerivedCreatedFromItems", false); operation.putArray("dateInstructions");
        var fields = operation.putArray("fields"); if (kind.equals("PATCH")) fields.addObject().put("key", "name").put("value", "새 회의");
        return root;
    }
    private static void checkSchema(JsonNode schema) {
        if (schema.isObject()) {
            if ("object".equals(schema.path("type").asText())) {
                require(schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean(), "모델 객체는 추가 속성 금지");
                require(schema.path("required").size() == schema.path("properties").size(), "strict 스키마는 모든 속성을 명시");
            }
            schema.properties().forEach(entry -> checkSchema(entry.getValue()));
        } else if (schema.isArray()) schema.forEach(AiContractChecks::checkSchema);
    }
    private static void reject(Runnable task) { try { task.run(); throw new AssertionError("잘못된 AI 입력을 받아들였습니다."); } catch (BusinessException expected) {} }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
