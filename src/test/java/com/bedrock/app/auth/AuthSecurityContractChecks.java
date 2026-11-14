package com.bedrock.app.auth;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.security.AuthFlowCipher;
import com.bedrock.app.auth.security.OAuthGateway;
import com.bedrock.app.auth.security.PasswordService;
import com.bedrock.app.common.config.PasswordConfig;
import com.bedrock.app.common.error.BusinessException;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;

/** 네트워크/DB 없이 실행 가능하고 JUnit에서도 같은 계약을 검증한다. */
public final class AuthSecurityContractChecks {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    public static void main(String[] args) throws Exception {
        passwords(); flowCipher(); tokenVerification(); authoritativeEmails(); redirects();
        System.out.println("Auth security: 5 contract groups passed");
    }

    static void passwords() {
        var service = new PasswordService(new PasswordConfig().passwordEncoder(19456, 2, 1), value -> value.equals("breached-password"));
        String password = "가나다라마바사아".repeat(12);
        String hash = service.encodeVerifiedPassword(password);
        require(hash.startsWith("$argon2id$") && service.matches(password, hash), "64자 초과 Argon2id 검증");
        require(!service.matches(password, null) && !service.matches(null, hash) && !service.matches(password + "x", hash), "인증 없는 입력 거절");
        reject(() -> service.encodeVerifiedPassword("password"));
        reject(() -> service.encodeVerifiedPassword("breached-password"));
        reject(() -> service.encodeVerifiedPassword("🙂🙂🙂🙂"));
    }

    static void flowCipher() {
        byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        var cipher = new AuthFlowCipher(Base64.getEncoder().encodeToString(key));
        UUID flow = UUID.randomUUID();
        String envelope = cipher.encrypt(flow, "sample-refresh-token");
        require(cipher.decrypt(flow, envelope).equals("sample-refresh-token") && !envelope.contains("sample-refresh-token"), "임시 토큰 암호화");
        reject(() -> cipher.decrypt(UUID.randomUUID(), envelope));
        byte[] corrupted = Base64.getUrlDecoder().decode(envelope); corrupted[15] ^= 1;
        reject(() -> cipher.decrypt(flow, Base64.getUrlEncoder().encodeToString(corrupted)));
        reject(() -> new AuthFlowCipher("").encrypt(flow, "token"));
    }

    static void tokenVerification() throws Exception {
        var pair = key(); var gateway = gateway(pair);
        var claims = claims("https://accounts.google.com", "google-web", "nonce", "sample@gmail.com", true);
        require(gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, claims, "RS256"), "nonce").authoritativeEmail(), "Google 서명·이메일 검증");
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, claims, "none"), "nonce"));
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, claims, "RS256"), "different"));
        var expired = new HashMap<>(claims); expired.put("exp", NOW.getEpochSecond());
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, expired, "RS256"), "nonce"));
        var wrongAudience = new HashMap<>(claims); wrongAudience.put("aud", "other-app");
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, wrongAudience, "RS256"), "nonce"));
        var wrongIssuer = new HashMap<>(claims); wrongIssuer.put("iss", "https://attacker.invalid");
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, wrongIssuer, "RS256"), "nonce"));
        for (String field : List.of("exp", "iat", "nbf")) {
            var overflow = new HashMap<>(claims);
            long timestamp = "exp".equals(field) ? NOW.plusSeconds(600).getEpochSecond() : NOW.minusSeconds(10).getEpochSecond();
            overflow.put(field, java.math.BigInteger.ONE.shiftLeft(64).add(java.math.BigInteger.valueOf(timestamp)));
            reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, overflow, "RS256"), "nonce"));
        }
        var numericSubject = new HashMap<>(claims); numericSubject.put("sub", 12345);
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, numericSubject, "RS256"), "nonce"));
        var attackerKey = key();
        reject(() -> gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(attackerKey, claims, "RS256"), "nonce"));
    }

    static void authoritativeEmails() throws Exception {
        var pair = key(); var gateway = gateway(pair);
        for (Object verification : List.of(true, "true")) {
            var claims = claims("https://appleid.apple.com", "apple-web", "nonce", "sample@privaterelay.appleid.com", verification);
            require(gateway.verify(AuthDtos.Provider.APPLE, AuthDtos.Platform.WEB, jwt(pair, claims, "RS256"), "nonce").authoritativeEmail(), "Apple true와 문자열 true");
        }
        for (Object verification : List.of(false, "TRUE", "1", 1)) {
            var claims = claims("https://appleid.apple.com", "apple-web", "nonce", "sample@icloud.com", verification);
            require(!gateway.verify(AuthDtos.Provider.APPLE, AuthDtos.Platform.WEB, jwt(pair, claims, "RS256"), "nonce").authoritativeEmail(), "Apple 확인 우회 거절");
        }
        var empty = claims("https://appleid.apple.com", "apple-web", "nonce", "", true);
        reject(() -> gateway.verify(AuthDtos.Provider.APPLE, AuthDtos.Platform.WEB, jwt(pair, empty, "RS256"), "nonce"));
        var thirdParty = claims("https://accounts.google.com", "google-web", "nonce", "sample@example.com", true);
        require(!gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, thirdParty, "RS256"), "nonce").authoritativeEmail(), "제3자 이메일은 추가 코드");
        thirdParty.put("hd", "example.com");
        require(gateway.verify(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, jwt(pair, thirdParty, "RS256"), "nonce").authoritativeEmail(), "확인된 Workspace 이메일");
    }

    static void redirects() throws Exception {
        var gateway = gateway(key());
        String url = gateway.authorizationUrl(AuthDtos.Provider.APPLE, AuthDtos.Platform.WEB, "https://app.example/callback", "state", "nonce");
        require(url.contains("response_mode=form_post") && url.contains("nonce=nonce"), "Apple 웹 form_post와 nonce");
        reject(() -> gateway.authorizationUrl(AuthDtos.Provider.GOOGLE, AuthDtos.Platform.WEB, "https://attacker.invalid", "state", "nonce"));
    }

    private static OAuthGateway gateway(KeyPair pair) {
        var key = (RSAPublicKey) pair.getPublic();
        String jwks = MAPPER.writeValueAsString(Map.of("keys", List.of(Map.of("kid", "test-key", "alg", "RS256", "use", "sig", "kty", "RSA",
                "n", base64(unsigned(key.getModulus().toByteArray())), "e", base64(unsigned(key.getPublicExponent().toByteArray()))))));
        return new OAuthGateway(MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), "google-web", "google-native", "apple-web", "apple-native",
                "test-secret", "test-secret", List.of("https://app.example/callback"), new KeysClient(jwks));
    }
    private static Map<String, Object> claims(String issuer, String audience, String nonce, String email, Object verified) {
        return new HashMap<>(Map.of("iss", issuer, "aud", audience, "sub", "test-subject", "exp", NOW.plusSeconds(600).getEpochSecond(),
                "iat", NOW.minusSeconds(10).getEpochSecond(), "nonce", nonce, "email", email, "email_verified", verified));
    }
    private static KeyPair key() throws Exception { var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
    private static String jwt(KeyPair pair, Map<String, Object> claims, String algorithm) {
        try {
            String body = base64(MAPPER.writeValueAsBytes(Map.of("alg", algorithm, "kid", "test-key"))) + "." + base64(MAPPER.writeValueAsBytes(claims));
            var signature = Signature.getInstance("SHA256withRSA"); signature.initSign(pair.getPrivate()); signature.update(body.getBytes(StandardCharsets.US_ASCII));
            return body + "." + base64(signature.sign());
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
    private static byte[] unsigned(byte[] value) { return value.length > 1 && value[0] == 0 ? Arrays.copyOfRange(value, 1, value.length) : value; }
    private static String base64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void reject(Runnable task) {
        try { task.run(); throw new AssertionError("거절할 인증 입력을 받아들였습니다."); }
        catch (BusinessException exception) { require(exception.getCause() == null && exception.getDetails() == null, "민감한 예외 원인을 반환하지 않는다"); }
    }

    private static final class KeysClient extends HttpClient {
        private final byte[] keys;
        KeysClient(String keys) { this.keys = keys.getBytes(StandardCharsets.UTF_8); }
        @SuppressWarnings("unchecked")
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            require(Set.of("www.googleapis.com", "appleid.apple.com").contains(request.uri().getHost()), "제공자 고정 주소");
            return (HttpResponse<T>) new HttpResponse<byte[]>() {
                public int statusCode() { return 200; } public byte[] body() { return keys.clone(); }
                public HttpRequest request() { return request; } public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
                public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
                public Optional<SSLSession> sslSession() { return Optional.empty(); } public URI uri() { return request.uri(); }
                public Version version() { return Version.HTTP_1_1; }
            };
        }
        public Optional<CookieHandler> cookieHandler() { return Optional.empty(); } public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(3)); }
        public Redirect followRedirects() { return Redirect.NEVER; } public Optional<ProxySelector> proxy() { return Optional.empty(); }
        public SSLContext sslContext() { throw new UnsupportedOperationException(); } public SSLParameters sslParameters() { return new SSLParameters(); }
        public Optional<Authenticator> authenticator() { return Optional.empty(); } public Version version() { return Version.HTTP_1_1; }
        public Optional<Executor> executor() { return Optional.empty(); }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return CompletableFuture.completedFuture(send(request, handler));
        }
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) { throw new UnsupportedOperationException(); }
    }
}
