package com.bedrock.app.crypto;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.crypto.config.VaultCryptoProperties;
import com.bedrock.app.crypto.key.UserDataKeyProvider;
import com.bedrock.app.crypto.key.VaultUserDataKeyProvider;
import com.bedrock.app.crypto.service.EnvelopeUserCrypto;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

/**
 * 네트워크와 외부 키 없이 실행하는 암호화 계약 검증.
 * JUnit에서도 같은 검증을 호출하며, 의존성 다운로드가 막힌 환경에서는 main으로 실행할 수 있다.
 */
public final class CryptoContractChecks {
    public static void main(String[] args) {
        envelopeIntegrity();
        keyLifetime();
        vaultCreation();
        vaultLoad();
        vaultDeletion();
        vaultFailure();
        configuration();
        System.out.println("Crypto contract checks passed (7 groups).");
    }

    static void envelopeIntegrity() {
        var keys = new TestKeys();
        var crypto = new EnvelopeUserCrypto(keys);
        UUID user = UUID.randomUUID();
        for (String text : List.of("", "회의 📝\n본문\t<태그>", "x".repeat(100_000))) {
            String first = crypto.encrypt(user, text);
            String second = crypto.encrypt(user, text);
            require(!first.equals(second), "같은 글도 다른 nonce로 암호화해야 한다.");
            require(text.equals(crypto.decrypt(user, first)), "UTF-8 원문을 보존해야 한다.");
        }
        String ciphertext = crypto.encrypt(user, "sensitive-input");
        require(!ciphertext.contains("sensitive-input"), "암호문에 원문을 넣으면 안 된다.");
        // 같은 키를 돌려주는 테스트 제공자에서도 AAD가 사용자를 구분해야 한다.
        expectUnavailable(() -> crypto.decrypt(UUID.randomUUID(), ciphertext));
        byte[] envelope = Base64.getUrlDecoder().decode(ciphertext.substring("bedrock:v1:".length()));
        for (int index : new int[] {0, 12, envelope.length - 1}) {
            byte[] changed = envelope.clone();
            changed[index] ^= 1;
            String tampered = "bedrock:v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(changed);
            expectUnavailable(() -> crypto.decrypt(user, tampered));
        }
        for (String invalid : List.of("plain text", "bedrock:v2:AAAA", "bedrock:v1:!", "bedrock:v1:AAAA")) {
            expectUnavailable(() -> crypto.decrypt(user, invalid));
        }
        require(keys.loaded.stream().allMatch(CryptoContractChecks::isZero), "사용한 키 배열은 지워야 한다.");
    }

    static void keyLifetime() {
        var keys = new TestKeys();
        var crypto = new EnvelopeUserCrypto(keys);
        UUID user = UUID.randomUUID();
        crypto.createDataKeyForUser(user);
        String encrypted = crypto.encrypt(user, "before deletion");
        crypto.createDataKeyForUser(user);
        require("before deletion".equals(crypto.decrypt(user, encrypted)), "생성 재시도는 기존 키를 바꾸면 안 된다.");
        crypto.deleteDataKeyForUser(user);
        expectUnavailable(() -> crypto.decrypt(user, encrypted));
        expectUnavailable(() -> crypto.encrypt(user, "after deletion"));
        expectUnavailable(() -> crypto.createDataKeyForUser(user));
        crypto.deleteDataKeyForUser(user);
    }

    static void vaultCreation() {
        var client = new ScriptedClient();
        var vault = provider(client);
        UUID user = UUID.randomUUID();
        client.reply(404, "{}");
        client.reply(200, "{\"data\":{\"ciphertext\":\"vault:v1:AAAA\"}}");
        client.reply(200, "{\"data\":{\"version\":1}}");
        vault.createIfAbsent(user);
        require(client.paths().equals(List.of("/v1/bedrock-keys/data/users/" + user,
                "/v1/transit/datakey/wrapped/test-key", "/v1/bedrock-keys/data/users/" + user)), "KMS 생성과 외부 KV 저장 경로");
        var mapper = JsonMapper.builder().build();
        require(mapper.readTree(client.bodies.get(2)).at("/options/cas").asInt() == 0, "기존 키를 덮으면 안 된다.");
        require(!client.bodies.get(2).contains("plaintext"), "평문 키를 저장하면 안 된다.");
        require(client.requests.stream().allMatch(request -> request.timeout().orElseThrow().equals(Duration.ofSeconds(5))),
                "모든 키 요청에는 제한 시간이 필요하다.");
        require(client.requests.stream().allMatch(request -> request.headers().firstValue("X-Vault-Token").orElseThrow().equals("test-token")),
                "키 요청에는 별도 자격 증명을 전달한다.");

        client.reply(200, active(1));
        vault.createIfAbsent(user);
        require(client.requests.size() == 4, "이미 있는 키를 재생성하지 않는다.");

        client.reply(404, "{}");
        client.reply(200, "{\"data\":{\"ciphertext\":\"vault:v1:BBBB\"}}");
        client.reply(400, "{}");
        client.reply(200, active(1));
        vault.createIfAbsent(user);
        client.requireFinished();
    }

    static void vaultLoad() {
        var client = new ScriptedClient();
        var vault = provider(client);
        UUID user = UUID.randomUUID();
        byte[] expected = new byte[32];
        new SecureRandom().nextBytes(expected);
        client.reply(200, active(1));
        byte[] response = ("{\"data\":{\"plaintext\":\"" + Base64.getEncoder().encodeToString(expected) + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        client.reply(200, response);
        require(Arrays.equals(expected, vault.load(user)), "KMS가 연 키를 그대로 사용한다.");
        require(isZero(response), "평문 키가 든 응답 바이트를 지운다.");
        String context = Base64.getEncoder().encodeToString(("bedrock:user:" + user).getBytes(StandardCharsets.UTF_8));
        require(client.bodies.get(1).contains(context), "사용자별 KMS 문맥을 전달한다.");

        client.reply(200, active(1));
        client.reply(200, "{\"data\":{\"plaintext\":\"AAAA\"}}");
        expectUnavailable(() -> vault.load(user));
        client.reply(200, deleted(2));
        expectUnavailable(() -> vault.load(user));
        client.requireFinished();
    }

    static void vaultDeletion() {
        var client = new ScriptedClient();
        var vault = provider(client);
        UUID user = UUID.randomUUID();
        client.reply(200, active(2));
        client.reply(200, "{\"data\":{\"version\":3}}");
        client.reply(204, "");
        vault.delete(user);
        require(client.bodies.get(1).contains("\"cas\":2"), "동시 생성을 덮어쓰면 안 된다.");
        require(client.bodies.get(1).contains("DELETED") && !client.bodies.get(1).contains("wrappedKey"), "삭제 표식에 키를 남기지 않는다.");
        require(client.requests.get(2).method().equals("PUT")
                && client.paths().get(2).contains("/destroy/")
                && client.bodies.get(2).equals("{\"versions\":[1,2]}"), "키의 모든 과거 버전을 영구 파기한다.");

        client.reply(200, deleted(3));
        client.reply(204, "");
        vault.delete(user);
        client.reply(200, deleted(3));
        expectUnavailable(() -> vault.createIfAbsent(user));

        client.reply(404, "{}");
        client.reply(400, "{}");
        client.reply(200, active(1));
        client.reply(200, "{\"data\":{\"version\":2}}");
        client.reply(204, "");
        vault.delete(user);
        client.requireFinished();
    }

    static void vaultFailure() {
        var client = new ScriptedClient();
        var vault = provider(client);
        UUID user = UUID.randomUUID();
        for (int status : List.of(403, 500, 302)) {
            client.reply(status, "sensitive-input test-token");
            expectUnavailable(() -> vault.load(user));
        }
        client.reply(200, "sensitive-input invalid-json");
        expectUnavailable(() -> vault.load(user));
        client.reply(200, active(1));
        client.reply(200, "{\"data\":{\"version\":2}}");
        client.reply(500, "failed to destroy sensitive-input");
        expectUnavailable(() -> vault.delete(user));
        client.failure = new IOException("sensitive-input test-token");
        expectUnavailable(() -> vault.load(user));
        client.requireFinished();
    }

    static void configuration() {
        var settings = settings();
        settings.validate();
        for (String address : List.of("http://vault.example", "https://user:secret@vault.example",
                "https://vault.example?token=secret", "https://vault.example/path")) {
            settings.setAddress(URI.create(address));
            try {
                settings.validate();
                throw new AssertionError("안전하지 않은 Vault 주소를 허용했다.");
            } catch (IllegalArgumentException expected) {
                require(!expected.getMessage().contains("secret"), "설정 오류가 자격 증명을 노출했다.");
            }
        }
    }

    private static VaultUserDataKeyProvider provider(ScriptedClient client) {
        return new VaultUserDataKeyProvider(client, JsonMapper.builder().build(), settings(), "test-key");
    }

    private static VaultCryptoProperties settings() {
        var properties = new VaultCryptoProperties();
        properties.setAddress(URI.create("https://vault.example"));
        properties.setToken("test-token");
        return properties;
    }

    private static String active(int version) {
        return "{\"data\":{\"metadata\":{\"version\":" + version
                + "},\"data\":{\"state\":\"ACTIVE\",\"kmsKeyId\":\"test-key\",\"wrappedKey\":\"vault:v1:AAAA\"}}}";
    }

    private static String deleted(int version) {
        return "{\"data\":{\"metadata\":{\"version\":" + version + "},\"data\":{\"state\":\"DELETED\"}}}";
    }

    private static void expectUnavailable(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("실패해야 할 암호화 연산이 성공했다.");
        } catch (BusinessException exception) {
            require(exception.getErrorCode() == Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE, "암호화 오류 코드");
            require(exception.getCause() == null && exception.getDetails() == null
                    && !exception.getMessage().contains("sensitive-input")
                    && !exception.getMessage().contains("test-token"), "오류에 민감값을 남기면 안 된다.");
        }
    }

    private static boolean isZero(byte[] bytes) {
        for (byte value : bytes) if (value != 0) return false;
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class TestKeys implements UserDataKeyProvider {
        private final byte[] key = new byte[32];
        private final List<byte[]> loaded = new ArrayList<>();
        private boolean deleted;

        TestKeys() { new SecureRandom().nextBytes(key); }
        @Override public void createIfAbsent(UUID userId) { if (deleted) throw unavailable(); }
        @Override public byte[] load(UUID userId) {
            if (deleted) throw unavailable();
            byte[] copy = key.clone();
            loaded.add(copy);
            return copy;
        }
        @Override public void delete(UUID userId) { deleted = true; Arrays.fill(key, (byte) 0); }
        private BusinessException unavailable() { return new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE); }
    }

    private static final class ScriptedClient extends HttpClient {
        private final ArrayDeque<Reply> replies = new ArrayDeque<>();
        private final List<HttpRequest> requests = new ArrayList<>();
        private final List<String> bodies = new ArrayList<>();
        private IOException failure;

        void reply(int status, String body) { reply(status, body.getBytes(StandardCharsets.UTF_8)); }
        void reply(int status, byte[] body) { replies.add(new Reply(status, body)); }
        List<String> paths() { return requests.stream().map(request -> request.uri().getPath()).toList(); }
        void requireFinished() { require(replies.isEmpty(), "예상한 Vault 요청을 모두 실행해야 한다."); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            if (failure != null) throw failure;
            requests.add(request);
            var body = new ByteArrayOutputStream();
            request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer bytes) { while (bytes.hasRemaining()) body.write(bytes.get()); }
                @Override public void onError(Throwable error) { throw new AssertionError(error); }
                @Override public void onComplete() {}
            });
            bodies.add(body.toString(StandardCharsets.UTF_8));
            Reply reply = replies.removeFirst();
            return (HttpResponse<T>) new HttpResponse<byte[]>() {
                @Override public int statusCode() { return reply.status(); }
                @Override public byte[] body() { return reply.body(); }
                @Override public HttpRequest request() { return request; }
                @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
                @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true); }
                @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
                @Override public URI uri() { return request.uri(); }
                @Override public Version version() { return Version.HTTP_1_1; }
            };
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(5)); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            try { return CompletableFuture.completedFuture(send(request, handler)); }
            catch (IOException exception) { return CompletableFuture.failedFuture(exception); }
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { throw new UnsupportedOperationException(); }
        private record Reply(int status, byte[] body) {}
    }
}
