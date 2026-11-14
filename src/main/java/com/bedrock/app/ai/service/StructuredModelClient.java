package com.bedrock.app.ai.service;

import com.bedrock.app.auth.security.SafeHttp;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 텍스트 생성은 OpenAI Responses API. 외부 대화·파일 저장을 생성하지 않는다. */
@Service("structuredModelClient")
public class StructuredModelClient {
    private final ObjectMapper mapper;
    private final String apiKey;
    private final String model;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public StructuredModelClient(ObjectMapper mapper, @Value("${bedrock.ai.api-key:}") String apiKey,
            @Value("${bedrock.ai.model:gpt-4.1-mini}") String model) {
        this.mapper = mapper; this.apiKey = apiKey; this.model = model;
    }

    public JsonNode generate(String instructions, Object input, JsonNode schema, String schemaName, Duration timeout) {
        return call(instructions, List.of(Map.of("role", "user", "content", mapper.writeValueAsString(input))), schema, schemaName, timeout);
    }

    public JsonNode recognize(String instructions, byte[] image, String mediaType, JsonNode schema, Duration timeout) {
        String dataUrl = "data:" + mediaType + ";base64," + java.util.Base64.getEncoder().encodeToString(image);
        return call(instructions, List.of(Map.of("role", "user", "content", List.of(
                Map.of("type", "input_text", "text", "첨부 시간표의 이름·요일·시간·장소 후보만 읽어 주세요."),
                Map.of("type", "input_image", "image_url", dataUrl, "detail", "high")))), schema, "bedrock_timetable", timeout);
    }

    private JsonNode call(String instructions, Object messages, JsonNode schema, String schemaName, Duration timeout) {
        if (apiKey.isBlank() || model.isBlank()) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(60)) > 0) throw failed();
        byte[] payload = null;
        byte[] received = null;
        try {
            payload = mapper.writeValueAsBytes(Map.of("model", model, "store", false, "stream", false,
                    "max_output_tokens", 8192, "instructions", instructions,
                    "input", messages,
                    "text", Map.of("format", Map.of("type", "json_schema", "name", schemaName, "strict", true, "schema", schema))));
            var request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses")).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            var response = SafeHttp.send(http, request, 262144);
            received = response.body();
            if (response.statusCode() != 200) throw failed();
            var result = mapper.readTree(received);
            if (!"completed".equals(result.path("status").asText()) || !result.path("output").isArray()) throw failed();
            String text = null;
            for (var item : result.path("output")) {
                if (!"message".equals(item.path("type").asText()) || !"assistant".equals(item.path("role").asText())) throw failed();
                for (var content : item.path("content")) {
                    if (!"output_text".equals(content.path("type").asText()) || text != null || !content.path("text").isString()) throw failed();
                    text = content.path("text").asText();
                }
            }
            if (text == null || text.length() > 131072) throw failed();
            JsonNode generated = mapper.readTree(text);
            StructuredSchemaValidator.validate(generated, schema);
            return generated;
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw failed(); }
        catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw failed(); }
        finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (received != null) Arrays.fill(received, (byte) 0);
        }
    }
    public String model() { return model; }
    private BusinessException failed() { return new BusinessException(Be1AuthAiErrorCode.AI_TIMEOUT_OR_INVALID_SCHEMA); }
}
