package com.bedrock.app.ai.service;

import com.bedrock.app.ai.dto.AiDtos;
import com.bedrock.app.auth.service.AuthSessions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service("aiInterpreter")
public class AiInterpreter {
    private final StructuredModelClient model;
    private final StructuredSchemas schemas;
    private final AiValidation validation;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration timeout;
    private final String instructions;

    public AiInterpreter(StructuredModelClient model, StructuredSchemas schemas, AiValidation validation, ObjectMapper mapper,
            @Qualifier("be1Clock") Clock clock, @Value("${bedrock.ai.timeout-ms:15000}") long timeoutMs) {
        if (timeoutMs < 100 || timeoutMs > 60000) throw new IllegalArgumentException("AI 시간 상한은 100~60000ms입니다.");
        this.model = model; this.schemas = schemas; this.validation = validation; this.mapper = mapper; this.clock = clock;
        this.timeout = Duration.ofMillis(timeoutMs);
        try (var input = new ClassPathResource("ai/interpretation-instructions.txt").getInputStream()) {
            instructions = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) { throw new IllegalStateException("AI 해석 지침을 읽을 수 없습니다."); }
    }

    public AiDtos.Interpretation interpret(AiDtos.InterpretationRequest input, List<AiDtos.Question> questions, List<AiDtos.Answer> answers) {
        validation.input(input);
        var candidates = input.candidates().stream().map(candidate -> {
            var value = mapper.valueToTree(candidate).deepCopy();
            ((tools.jackson.databind.node.ObjectNode) value).remove("baseFieldVersions");
            return value;
        }).toList();
        var payload = Map.of("sentence", input.sentence(), "referenceTime", input.referenceTime(), "referenceTimeZone", input.referenceTimeZone(),
                "context", input.context(), "candidates", candidates, "guideline", input.guideline(), "questions", questions, "answers", answers);
        String revision = UUID.randomUUID().toString();
        JsonNode generated = model.generate(instructions, payload, schemas.interpretation(), "bedrock_interpretation", timeout);
        var result = validation.output(input.requestId(), revision, generated, input, answers);
        if (result.kind() == AiDtos.Kind.READY && (input.pendingItemId() != null || !answers.isEmpty())) {
            var past = new ArrayList<String>();
            Instant now = clock.instant();
            LocalDate today = LocalDate.ofInstant(now, ZoneId.of(input.referenceTimeZone()));
            for (var operation : result.operations()) {
                for (var field : operation.proposedFields().entrySet()) {
                    if (field.getValue().isNull()) continue;
                    collectPast(past, field.getKey(), field.getValue().asText(), now, today);
                }
                for (var date : operation.dateInstructions()) collectPast(past, date.fieldKey(), date.resolvedValue(), now, today);
            }
            if (!past.isEmpty()) {
                past.sort(String::compareTo);
                String confirmationId = "past_" + AuthSessions.fingerprint(today + ":" + String.join(",", past)).substring(0, 24);
                if (answers.stream().noneMatch(answer -> confirmationId.equals(answer.questionId()) && AiConfirmations.pastKept(answer))) {
                    var question = new AiDtos.Question(confirmationId, "처음 요청을 기준으로 푼 날짜가 지났습니다. 원래 날짜를 유지할까요?",
                            List.of(new AiDtos.Choice("keep", "원래 날짜 유지", "처음 요청의 기준으로 저장합니다.", mapper.valueToTree(true))), true);
                    return new AiDtos.Interpretation(input.requestId(), revision, AiDtos.Kind.ASK, result.operations(), List.of(question), null, result.warnings(), null, null);
                }
            }
        }
        return result;
    }

    private void collectPast(List<String> past, String field, String value, Instant now, LocalDate today) {
        if (value == null) return;
        if (List.of("startAt", "endAt", "dueAt").contains(field) && Instant.parse(value).isBefore(now)) past.add(field + ":" + value);
        if (List.of("startDate", "endDate", "dueDate", "effectiveDate").contains(field) && LocalDate.parse(value).isBefore(today)) past.add(field + ":" + value);
    }
}
