package com.bedrock.app.ai.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** StructuredSchemas가 만드는 제한된 JSON Schema를 수신 측에서도 검사한다. */
public final class StructuredSchemaValidator {
    private static final Set<String> KEYWORDS = Set.of("type", "enum", "anyOf", "properties", "required", "additionalProperties", "items");
    private int remainingNodes = 32768;

    private StructuredSchemaValidator() {}

    public static void validate(JsonNode value, JsonNode schema) {
        if (!new StructuredSchemaValidator().matches(value, schema, 0)) throw malformed();
    }

    private boolean matches(JsonNode value, JsonNode schema, int depth) {
        if (--remainingNodes < 0 || depth > 64) throw malformed();
        if (value == null || schema == null || !schema.isObject() || !KEYWORDS.containsAll(schema.propertyNames())) return false;
        if (schema.has("anyOf")) {
            if (schema.size() != 1 || !schema.path("anyOf").isArray() || schema.path("anyOf").isEmpty()) return false;
            for (var alternative : schema.path("anyOf")) {
                if (matches(value, alternative, depth + 1)) return true;
            }
            return false;
        }
        boolean matchesType = switch (schema.path("type").asText()) {
            case "null" -> value.isNull();
            case "string" -> value.isString();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            default -> false;
        };
        if (!matchesType) return false;
        if (schema.has("enum")) {
            if (!schema.path("enum").isArray()) return false;
            boolean found = false;
            for (var allowed : schema.path("enum")) if (allowed.equals(value)) found = true;
            if (!found) return false;
        }
        if (value.isObject()) {
            var properties = schema.path("properties");
            var required = schema.path("required");
            if (!properties.isObject() || !required.isArray() || !schema.path("additionalProperties").isBoolean()
                    || schema.path("additionalProperties").asBoolean() || !properties.propertyNames().containsAll(value.propertyNames())) return false;
            for (var name : required) {
                if (!name.isString() || !properties.has(name.asText()) || !value.has(name.asText())) return false;
            }
            for (var property : value.properties()) {
                if (!matches(property.getValue(), properties.path(property.getKey()), depth + 1)) return false;
            }
        } else if (value.isArray()) {
            if (!schema.has("items")) return false;
            for (var item : value) if (!matches(item, schema.path("items"), depth + 1)) return false;
        }
        return true;
    }

    private static BusinessException malformed() {
        return new BusinessException(Be1AuthAiErrorCode.AI_TIMEOUT_OR_INVALID_SCHEMA);
    }
}
