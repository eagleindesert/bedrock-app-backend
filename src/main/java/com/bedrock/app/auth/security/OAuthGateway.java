package com.bedrock.app.auth.security;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service("oauthGateway")
public class OAuthGateway {
    private final ObjectMapper mapper;
    private final Clock clock;
    private final HttpClient http;
    private final String googleWeb;
    private final String googleNative;
    private final String appleWeb;
    private final String appleNative;
    private final String appleWebSecret;
    private final String appleNativeSecret;
    private final List<String> redirects;
    private final Map<AuthDtos.Provider, KeySet> keys = new HashMap<>();

    @Autowired
    public OAuthGateway(ObjectMapper mapper, @Qualifier("be1Clock") Clock clock,
            @Value("${bedrock.auth.oauth.google.web-client-id:}") String googleWeb,
            @Value("${bedrock.auth.oauth.google.native-client-id:}") String googleNative,
            @Value("${bedrock.auth.oauth.apple.web-client-id:}") String appleWeb,
            @Value("${bedrock.auth.oauth.apple.native-client-id:}") String appleNative,
            @Value("${bedrock.auth.oauth.apple.web-client-secret:}") String appleWebSecret,
            @Value("${bedrock.auth.oauth.apple.native-client-secret:}") String appleNativeSecret,
            @Value("${bedrock.auth.oauth.redirect-uris:}") List<String> redirects) {
        this(mapper, clock, googleWeb, googleNative, appleWeb, appleNative, appleWebSecret, appleNativeSecret, redirects,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public OAuthGateway(ObjectMapper mapper, Clock clock, String googleWeb, String googleNative, String appleWeb,
            String appleNative, String appleWebSecret, String appleNativeSecret, List<String> redirects, HttpClient http) {
        this.mapper = mapper;
        this.clock = clock;
        this.googleWeb = googleWeb;
        this.googleNative = googleNative;
        this.appleWeb = appleWeb;
        this.appleNative = appleNative;
        this.appleWebSecret = appleWebSecret;
        this.appleNativeSecret = appleNativeSecret;
        this.redirects = redirects;
        this.http = http;
    }

    public String clientId(AuthDtos.Provider provider, AuthDtos.Platform platform) {
        String value = provider == AuthDtos.Provider.GOOGLE
                ? platform == AuthDtos.Platform.WEB ? googleWeb : googleNative
                : platform == AuthDtos.Platform.WEB ? appleWeb : appleNative;
        if (value.isBlank()) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        return value;
    }

    public String authorizationUrl(AuthDtos.Provider provider, AuthDtos.Platform platform, String redirect, String state, String nonce) {
        String client = clientId(provider, platform);
        if (redirect != null && !redirects.contains(redirect) || platform == AuthDtos.Platform.WEB && redirect == null) throw validation();
        if (platform != AuthDtos.Platform.WEB) return null;
        URI target;
        try { target = URI.create(redirect); }
        catch (IllegalArgumentException exception) { throw validation(); }
        if (!"https".equals(target.getScheme()) || target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null) throw validation();
        String endpoint = provider == AuthDtos.Provider.GOOGLE ? "https://accounts.google.com/o/oauth2/v2/auth" : "https://appleid.apple.com/auth/authorize";
        return endpoint + "?" + form(Map.of("client_id", client, "redirect_uri", redirect,
                "response_type", provider == AuthDtos.Provider.GOOGLE ? "id_token" : "code id_token",
                "scope", provider == AuthDtos.Provider.GOOGLE ? "openid email profile" : "name email",
                "response_mode", provider == AuthDtos.Provider.GOOGLE ? "fragment" : "form_post", "state", state, "nonce", nonce));
    }

    public Identity verify(AuthDtos.Provider provider, AuthDtos.Platform platform, String jwt, String expectedNonce) {
        try {
            if (jwt == null || jwt.length() > 32768) throw invalid();
            String[] parts = jwt.split("\\.", -1);
            if (parts.length != 3) throw invalid();
            JsonNode header = mapper.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"RS256".equals(header.path("alg").asText()) || header.has("crit") || !header.path("kid").isString()) throw invalid();
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key(provider, header.path("kid").asText()));
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(Base64.getUrlDecoder().decode(parts[2]))) throw invalid();
            JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
            String issuer = claims.path("iss").asText();
            boolean validIssuer = provider == AuthDtos.Provider.GOOGLE
                    ? List.of("accounts.google.com", "https://accounts.google.com").contains(issuer)
                    : "https://appleid.apple.com".equals(issuer);
            String audience = clientId(provider, platform);
            boolean validAudience = claims.path("aud").isString() && audience.equals(claims.path("aud").asText());
            if (!validIssuer || !validAudience || !claims.path("exp").isIntegralNumber() || !claims.path("exp").canConvertToLong()
                    || !claims.path("iat").isIntegralNumber() || !claims.path("iat").canConvertToLong()
                    || claims.path("exp").asLong() <= clock.instant().getEpochSecond()
                    || claims.path("iat").asLong() > clock.instant().plusSeconds(60).getEpochSecond()
                    || claims.has("nbf") && (!claims.path("nbf").isIntegralNumber() || !claims.path("nbf").canConvertToLong()
                    || claims.path("nbf").asLong() > clock.instant().getEpochSecond())) throw invalid();
            if (expectedNonce != null) {
                String nonce = claims.path("nonce").asText();
                boolean matches = constant(expectedNonce, nonce);
                if (platform != AuthDtos.Platform.WEB) matches |= constant(AuthSessionsHash.sha256(expectedNonce), nonce);
                if (!matches) throw invalid();
            }
            String subject = claims.path("sub").asText();
            String email = claims.path("email").asText().strip().toLowerCase(Locale.ROOT);
            if (!claims.path("sub").isString() || subject.isBlank() || subject.length() > 255
                    || !claims.path("email").isString() || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || email.length() > 320) throw invalid();
            boolean verified = claims.path("email_verified").isBoolean() && claims.path("email_verified").asBoolean()
                    || claims.path("email_verified").isString() && "true".equals(claims.path("email_verified").asText());
            String domain = email.substring(email.lastIndexOf('@') + 1);
            boolean authoritative = verified && (provider == AuthDtos.Provider.GOOGLE
                    ? "gmail.com".equals(domain) || claims.path("hd").isString() && !claims.path("hd").asText().isBlank()
                    : List.of("icloud.com", "me.com", "mac.com", "privaterelay.appleid.com").contains(domain));
            String name = claims.path("name").asText("");
            if (name.isBlank() || name.length() > 100) name = email.substring(0, Math.min(100, email.indexOf('@')));
            return new Identity(subject, email, name, authoritative);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    public AppleGrant exchange(AuthDtos.Platform platform, String code, String redirect, Identity expected) {
        if (code == null || code.isBlank() || code.length() > 16384) throw invalid();
        var fields = appleCredentials(platform);
        fields.put("grant_type", "authorization_code");
        fields.put("code", code);
        if (redirect != null) fields.put("redirect_uri", redirect);
        JsonNode result = applePost("token", fields);
        Identity verified = verify(AuthDtos.Provider.APPLE, platform, result.path("id_token").asText(), null);
        if (!verified.subject().equals(expected.subject()) || !verified.email().equals(expected.email())) throw invalid();
        String refresh = result.path("refresh_token").asText();
        if (refresh.isBlank() || refresh.length() > 16384) throw invalid();
        return new AppleGrant(clientId(AuthDtos.Provider.APPLE, platform), refresh);
    }

    public void revoke(AppleGrant grant) {
        AuthDtos.Platform platform = grant.clientId().equals(appleWeb) ? AuthDtos.Platform.WEB : AuthDtos.Platform.IOS;
        if (!grant.clientId().equals(clientId(AuthDtos.Provider.APPLE, platform))) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        var fields = appleCredentials(platform);
        fields.put("token", grant.refreshToken());
        fields.put("token_type_hint", "refresh_token");
        applePost("revoke", fields);
    }

    private Map<String, String> appleCredentials(AuthDtos.Platform platform) {
        String secret = platform == AuthDtos.Platform.WEB ? appleWebSecret : appleNativeSecret;
        if (secret.isBlank()) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        return new HashMap<>(Map.of("client_id", clientId(AuthDtos.Provider.APPLE, platform), "client_secret", secret));
    }

    private JsonNode applePost(String operation, Map<String, String> fields) {
        try {
            var request = HttpRequest.newBuilder(URI.create("https://appleid.apple.com/auth/" + operation)).timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
            var response = SafeHttp.send(http, request, 65536);
            if (response.statusCode() != 200) throw new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE);
            return response.body().length == 0 ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE); }
    }

    private synchronized PublicKey key(AuthDtos.Provider provider, String kid) {
        if (kid.isBlank() || kid.length() > 256) throw invalid();
        var cached = keys.get(provider);
        if (cached != null && clock.instant().isBefore(cached.loadedAt().plusSeconds(600)) && cached.values().containsKey(kid)) return cached.values().get(kid);
        if (cached != null && clock.instant().isBefore(cached.loadedAt().plusSeconds(30))) throw invalid();
        try {
            String endpoint = provider == AuthDtos.Provider.GOOGLE ? "https://www.googleapis.com/oauth2/v3/certs" : "https://appleid.apple.com/auth/keys";
            var response = SafeHttp.send(http, HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(3)).GET().build(), 131072);
            if (response.statusCode() != 200) throw invalid();
            Map<String, PublicKey> received = new HashMap<>();
            for (var key : mapper.readTree(response.body()).path("keys")) {
                if (!"RSA".equals(key.path("kty").asText()) || !"sig".equals(key.path("use").asText())
                        || key.has("alg") && !"RS256".equals(key.path("alg").asText())) continue;
                var modulus = new BigInteger(1, Base64.getUrlDecoder().decode(key.path("n").asText()));
                var exponent = new BigInteger(1, Base64.getUrlDecoder().decode(key.path("e").asText()));
                if (modulus.bitLength() < 2048 || modulus.bitLength() > 8192 || exponent.bitLength() > 32) continue;
                received.put(key.path("kid").asText(), KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            }
            keys.put(provider, new KeySet(clock.instant(), Map.copyOf(received)));
            if (!received.containsKey(kid)) throw invalid();
            return received.get(kid);
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw invalid(); }
        catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
    }
    public static boolean constant(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS); }
    private BusinessException validation() { return new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION); }
    public record Identity(String subject, String email, String name, boolean authoritativeEmail) implements java.io.Serializable {
        @Override public String toString() { return "Identity[redacted]"; }
    }
    public record AppleGrant(String clientId, String refreshToken) {
        @Override public String toString() { return "AppleGrant[redacted]"; }
    }
    private record KeySet(Instant loadedAt, Map<String, PublicKey> values) {}
    private static final class AuthSessionsHash {
        private static String sha256(String value) { return com.bedrock.app.auth.service.AuthSessions.fingerprint(value); }
    }
}
