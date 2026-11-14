package com.bedrock.app.ocr.service;

import com.bedrock.app.auth.security.SafeHttp;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

/** HEIC는 Azure Layout v4의 HEIF 입력으로 읽고 추출 결과를 삭제한다. 원본은 서버에 저장하지 않는다. */
@Service("heicLayoutClient")
public class HeicLayoutClient {
    private static final String PREFIX = "/documentintelligence/documentModels/prebuilt-layout/analyzeResults/";
    private static final String API = "?api-version=2024-11-30";
    private static final String CLEANUP = "bedrock:ocr:azure:cleanup";
    private final String endpoint;
    private final String apiKey;
    private final ObjectMapper mapper;
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();

    public HeicLayoutClient(@Value("${bedrock.ocr.azure.endpoint:}") String endpoint,
            @Value("${bedrock.ocr.azure.api-key:}") String apiKey, ObjectMapper mapper, StringRedisTemplate redis, MeterRegistry metrics) {
        this.endpoint = endpoint.replaceAll("/+$", ""); this.apiKey = apiKey; this.mapper = mapper; this.redis = redis; this.metrics = metrics;
        if (!this.endpoint.isBlank()) {
            URI uri;
            try { uri = URI.create(this.endpoint); }
            catch (IllegalArgumentException exception) { throw new IllegalStateException("OCR Azure 주소가 올바르지 않습니다."); }
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || !uri.getHost().endsWith(".cognitiveservices.azure.com")
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !uri.getPath().isEmpty()
                    || uri.getPort() != -1 && uri.getPort() != 443) throw new IllegalStateException("OCR Azure 주소가 올바르지 않습니다.");
        }
    }

    public String read(byte[] image, long deadlineNanos) {
        if (endpoint.isBlank() || apiKey.isBlank()) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        UUID operationId = null;
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint + "/documentintelligence/documentModels/prebuilt-layout:analyze"
                            + API + "&outputContentFormat=markdown&pages=1"))
                    .timeout(remaining(deadlineNanos)).header("Ocp-Apim-Subscription-Key", apiKey)
                    .header("Content-Type", "image/heif").POST(HttpRequest.BodyPublishers.ofByteArray(image)).build();
            var started = SafeHttp.send(http, request, 16384);
            if (started.statusCode() != 202) throw failed();
            URI location = URI.create(started.headers().firstValue("operation-location").orElseThrow(this::failed));
            URI origin = URI.create(endpoint);
            if (!origin.getScheme().equals(location.getScheme()) || !origin.getAuthority().equals(location.getAuthority())
                    || !location.getPath().startsWith(PREFIX) || location.getFragment() != null) throw failed();
            operationId = UUID.fromString(location.getPath().substring(PREFIX.length()));
            // 결과 ID만 남긴다. 이미지·인식 글·주소·사용자 ID는 이 정리 큐에 없다.
            redis.opsForSet().add(CLEANUP, operationId.toString());
            while (true) {
                Thread.sleep(Math.min(1000, remaining(deadlineNanos).toMillis()));
                var response = SafeHttp.send(http, HttpRequest.newBuilder(resultUri(operationId)).timeout(remaining(deadlineNanos))
                        .header("Ocp-Apim-Subscription-Key", apiKey).GET().build(), 524288);
                byte[] bytes = response.body();
                try {
                    if (response.statusCode() != 200) throw failed();
                    var result = mapper.readTree(bytes);
                    switch (result.path("status").asText()) {
                        case "succeeded" -> {
                            var content = result.path("analyzeResult").path("content");
                            if (!content.isString() || content.asText().length() > 65536) throw failed();
                            return content.asText();
                        }
                        case "running", "notStarted" -> { }
                        default -> throw failed();
                    }
                } finally { Arrays.fill(bytes, (byte) 0); }
            }
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw failed(); }
        catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw failed(); }
        finally {
            if (operationId != null) {
                long cleanupMillis = Math.max(1, Math.min(1000, (deadlineNanos - System.nanoTime()) / 1_000_000));
                try { erase(operationId, Duration.ofMillis(cleanupMillis)); }
                catch (RuntimeException exception) { metrics.counter("bedrock.ocr.remote_cleanup", "outcome", "retry").increment(); }
            }
        }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000, scheduler = "be1MaintenanceScheduler")
    public void cleanup() {
        if (endpoint.isBlank() || apiKey.isBlank()) return;
        try {
            var pending = redis.opsForSet().distinctRandomMembers(CLEANUP, 20);
            if (pending != null) for (String id : pending) {
                try { erase(UUID.fromString(id), Duration.ofSeconds(2)); }
                catch (RuntimeException ignored) { metrics.counter("bedrock.ocr.remote_cleanup", "outcome", "failed").increment(); }
            }
        } catch (RuntimeException ignored) { metrics.counter("bedrock.ocr.remote_cleanup", "outcome", "failed").increment(); }
    }

    private void erase(UUID id, Duration timeout) {
        try {
            var response = SafeHttp.send(http, HttpRequest.newBuilder(resultUri(id)).timeout(timeout)
                    .header("Ocp-Apim-Subscription-Key", apiKey).DELETE().build(), 16384);
            if (response.statusCode() != 204 && response.statusCode() != 404) throw failed();
            redis.opsForSet().remove(CLEANUP, id.toString());
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw failed(); }
        catch (Exception exception) { throw failed(); }
    }
    private URI resultUri(UUID id) { return URI.create(endpoint + PREFIX + id + API); }
    private Duration remaining(long deadline) {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw failed();
        return Duration.ofNanos(nanos);
    }
    private BusinessException failed() { return new BusinessException(Be1AuthAiErrorCode.OCR_TIMEOUT_OR_INVALID_RESULT); }
}
