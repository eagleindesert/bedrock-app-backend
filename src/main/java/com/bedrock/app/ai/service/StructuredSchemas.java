package com.bedrock.app.ai.service;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** strict JSON Schema: 모든 속성을 필수로 하고 선택 값은 null로 표현한다. */
@Component("structuredSchemas")
public class StructuredSchemas {
    private final ObjectMapper mapper;
    public StructuredSchemas(ObjectMapper mapper) { this.mapper = mapper; }

    public JsonNode interpretation() {
        var occurrence = object("masterItemId", string(), "recurrenceDate", string(), "slotId", nullable(string()));
        var target = object("itemId", string(), "occurrence", nullable(occurrence));
        var dates = object("fieldKey", enumeration("startAt", "endAt", "dueAt", "startDate", "endDate", "dueDate", "effectiveDate", "rangeStart", "rangeEnd"),
                "expression", string(), "resolvedValue", nullable(string()));
        var recurrence = object("rrule", string(), "rdates", array(string()), "exdates", array(string()),
                "untilDate", nullable(string()), "effectiveFrom", nullable(string()));
        var timetable = object("slots", array(object("slotId", string(), "dayOfWeek", integer(), "startTime", string(), "endTime", string())),
                "periodStart", string(), "periodEnd", nullable(string()), "seriesRootId", nullable(string()), "effectiveFrom", nullable(string()));
        var todo = object("status", enumeration("TODO", "DONE", "POSTPONED", "CANCELLED"), "completedAt", nullable(string()));
        var fieldValue = Map.of("anyOf", List.of(string(), integer(), bool(), Map.of("type", "null"), array(string()), array(integer()), recurrence, timetable, todo));
        var operation = object("kind", enumeration("CREATE", "PATCH", "DELETE", "POSTPONE"), "target", nullable(target),
                "scope", nullable(enumeration("SINGLE", "THIS_OCCURRENCE", "THIS_AND_FUTURE", "ALL")), "effectiveDate", nullable(string()),
                "fields", array(object("key", enumeration(AiValidation.FIELD_KEYS.stream().sorted().toArray(String[]::new)), "value", fieldValue)),
                "dateInstructions", array(dates), "timeDependency", nullable(object("referenceTarget", target, "referenceField", string(), "offsetMinutes", integer())),
                "postponeMode", nullable(enumeration("TO_DATE", "SKIP_TO_NEXT")), "deleteDerivedCreatedFromItems", bool());
        var choice = object("choiceId", string(), "label", string(), "reason", string(),
                "value", Map.of("anyOf", List.of(string(), integer(), bool(), target)));
        var question = object("questionId", string(), "message", string(), "choices", array(choice), "required", bool());
        var query = object("rangeStart", nullable(dates), "rangeEnd", nullable(dates), "natures", array(enumeration("SCHEDULE", "TODO", "NOTE", "TIMETABLE")),
                "todoStatuses", array(enumeration("TODO", "DONE", "POSTPONED", "CANCELLED")), "collectionIds", array(string()),
                "nameContains", nullable(string()), "tagContains", nullable(string()), "freeTimeMinutes", nullable(integer()));
        return mapper.valueToTree(object("kind", enumeration("READY", "ASK", "QUERY", "UNSUPPORTED"), "operations", array(operation),
                "questions", array(question), "query", nullable(query), "warnings", array(warning()), "reason", nullable(string())));
    }

    public JsonNode timetable() {
        var slot = object("dayOfWeek", nullable(integer()), "startTime", nullable(string()), "endTime", nullable(string()));
        return mapper.valueToTree(object("items", array(object("candidateId", string(), "name", nullable(string()), "location", nullable(string()), "slots", array(slot))),
                "warnings", array(warning())));
    }

    private Map<String, Object> warning() { return object("code", string(), "itemId", nullable(string()), "fieldKeys", array(string()), "message", string()); }
    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) properties.put((String) pairs[i], pairs[i + 1]);
        return Map.of("type", "object", "properties", properties, "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    private static Map<String, Object> string() { return Map.of("type", "string"); }
    private static Map<String, Object> integer() { return Map.of("type", "integer"); }
    private static Map<String, Object> bool() { return Map.of("type", "boolean"); }
    private static Map<String, Object> enumeration(String... values) { return Map.of("type", "string", "enum", Arrays.asList(values)); }
    private static Map<String, Object> array(Object items) { return Map.of("type", "array", "items", items); }
    private static Map<String, Object> nullable(Object schema) { return Map.of("anyOf", List.of(schema, Map.of("type", "null"))); }
}
