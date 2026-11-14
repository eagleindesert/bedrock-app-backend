package com.bedrock.app.mail;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.mail.api.SendGridWebhookController;
import com.bedrock.app.mail.security.SendGridWebhookVerifier;
import com.bedrock.app.mail.service.SendGridEvents;
import com.bedrock.app.mail.service.SendGridWebhookService;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 실제 JCA ECDSA와 JSON 파서를 쓰며 외부 제공자·네트워크 없이 실행한다. */
public final class MailWebhookContractChecks {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneOffset.UTC);
    private static final KeyPair KEYS = keys();
    private static final byte[] BODY = "[{\"event\":\"bounce\",\"type\":\"bounce\",\"email\":\"member@example.com\",\"sg_event_id\":\"one\",\"timestamp\":1794700800,\"reason\":\"한글 사유\"}]".getBytes(StandardCharsets.UTF_8);

    public static void main(String[] args) {
        authenticatesOriginalBytes(); expiresSignedRequests(); handlesOnlyPermanentBounces(); rejectsMalformedEvents(); boundsAndErasesBody();
        System.out.println("Mail webhook: 5 contract groups passed");
    }

    static void authenticatesOriginalBytes() {
        var verifier = verifier();
        String timestamp = Long.toString(CLOCK.instant().getEpochSecond());
        String signature = sign(BODY, timestamp);
        verifier.verify(BODY, timestamp, signature);
        byte[] changed = Arrays.copyOf(BODY, BODY.length + 1); changed[changed.length - 1] = '\n';
        reject(() -> verifier.verify(changed, timestamp, signature));
        reject(() -> verifier.verify(BODY, timestamp, "not a signature"));
        reject(() -> verifier.verify(BODY, timestamp + "\r\n", signature));
        try { new SendGridWebhookVerifier("", CLOCK).verify(BODY, timestamp, signature); throw new AssertionError(); }
        catch (BusinessException expected) { require(expected.getErrorCode() == CommonErrorCode.FEATURE_NOT_READY, "미설정 시 실패"); }
    }

    static void expiresSignedRequests() {
        var verifier = verifier();
        String stale = Long.toString(CLOCK.instant().minusSeconds(86401).getEpochSecond());
        reject(() -> verifier.verify(BODY, stale, sign(BODY, stale)));
        String future = Long.toString(CLOCK.instant().plusSeconds(301).getEpochSecond());
        reject(() -> verifier.verify(BODY, future, sign(BODY, future)));
        String retry = Long.toString(CLOCK.instant().minusSeconds(3600).getEpochSecond());
        verifier.verify(BODY, retry, sign(BODY, retry));
    }

    static void handlesOnlyPermanentBounces() {
        var parser = new SendGridEvents(JsonMapper.builder().build());
        var hard = parser.read(BODY);
        require(hard.size() == 1 && hard.getFirst().outcome() == SendGridEvents.Outcome.BOUNCED, "영구 반송만 차단");
        String soft = new String(BODY, StandardCharsets.UTF_8).replace("\"type\":\"bounce\"", "\"type\":\"blocked\"");
        require(parser.read(soft.getBytes(StandardCharsets.UTF_8)).isEmpty(), "일시 거부는 영구 차단하지 않음");
        require(parser.read("[{\"event\":\"deferred\"},{\"event\":\"open\"}]".getBytes(StandardCharsets.UTF_8)).isEmpty(), "참여·대기 이벤트 미수집");
        String delivered = new String(BODY, StandardCharsets.UTF_8).replace("\"event\":\"bounce\"", "\"event\":\"delivered\"");
        require(parser.read(delivered.getBytes(StandardCharsets.UTF_8)).getFirst().outcome() == SendGridEvents.Outcome.DELIVERED, "전달 완료 통계");
        require(!hard.getFirst().toString().contains("member@example.com"), "예외·디버그 문자열에 주소 없음");
    }

    static void rejectsMalformedEvents() {
        var parser = new SendGridEvents(JsonMapper.builder().build());
        reject(() -> parser.read("{}".getBytes(StandardCharsets.UTF_8)));
        reject(() -> parser.read("[null]".getBytes(StandardCharsets.UTF_8)));
        reject(() -> parser.read("[{\"event\":\"bounce\",\"type\":\"bounce\"}]".getBytes(StandardCharsets.UTF_8)));
        String displayName = new String(BODY, StandardCharsets.UTF_8).replace("member@example.com", "Someone <member@example.com>");
        reject(() -> parser.read(displayName.getBytes(StandardCharsets.UTF_8)));
        String overflow = new String(BODY, StandardCharsets.UTF_8).replace("1794700800", "18446744073709551616");
        reject(() -> parser.read(overflow.getBytes(StandardCharsets.UTF_8)));
    }

    static void boundsAndErasesBody() {
        var observed = new AtomicReference<byte[]>();
        var service = new SendGridWebhookService(null, null, null, null, null, null, null, CLOCK) {
            @Override public void receive(byte[] body, String timestamp, String signature) {
                require(Arrays.equals(body, BODY), "원본 바이트 그대로 검증에 전달");
                observed.set(body);
            }
        };
        var controller = new SendGridWebhookController(service);
        Map<String, List<String>> headers = Map.of(SendGridWebhookVerifier.SIGNATURE_HEADER, List.of("signature"),
                SendGridWebhookVerifier.TIMESTAMP_HEADER, List.of("timestamp"));
        require(controller.receive(request(BODY, headers)).getStatusCode().value() == 204, "제공자에게 204 응답");
        require(Arrays.equals(observed.get(), new byte[BODY.length]), "사용한 원문 버퍼 지움");
        reject(() -> controller.receive(request(new byte[SendGridWebhookVerifier.MAX_BODY_BYTES + 1], headers)));
        reject(() -> controller.receive(request(BODY, Map.of(SendGridWebhookVerifier.SIGNATURE_HEADER, List.of("one", "two")))));
    }

    private static HttpServletRequest request(byte[] body, Map<String, List<String>> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getHeaders" -> Collections.enumeration(headers.getOrDefault(args[0], List.of()));
            case "getContentLengthLong" -> (long) body.length;
            case "getInputStream" -> new ServletInputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(body);
                public int read() { return input.read(); }
                public boolean isFinished() { return input.available() == 0; }
                public boolean isReady() { return true; }
                public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
            default -> throw new AssertionError("웹훅은 세션·쿼리·폼을 읽지 않습니다: " + method.getName());
        });
    }

    private static SendGridWebhookVerifier verifier() { return new SendGridWebhookVerifier(Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()), CLOCK); }
    private static KeyPair keys() {
        try { var generator = KeyPairGenerator.getInstance("EC"); generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair(); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static String sign(byte[] bytes, String timestamp) {
        try {
            var signature = Signature.getInstance("SHA256withECDSA"); signature.initSign(KEYS.getPrivate());
            signature.update(timestamp.getBytes(StandardCharsets.US_ASCII)); signature.update(bytes);
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private static void reject(Runnable task) { try { task.run(); throw new AssertionError("잘못된 webhook을 수락했습니다."); } catch (BusinessException expected) {} }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
