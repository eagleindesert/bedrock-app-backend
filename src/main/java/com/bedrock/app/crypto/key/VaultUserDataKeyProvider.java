package com.bedrock.app.crypto.key;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.crypto.config.VaultCryptoProperties;
import com.bedrock.app.auth.security.SafeHttp;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Transit KMS가 생성한 사용자별 DEK를 별도 KV v2 마운트에 보관한다.
 * 사용자 ID를 파생 문맥으로 묶으므로 Transit 키는 derived=true로 준비해야 한다.
 * KV에는 감싼 키만 쓰고, 애플리케이션 DB의 user_data_keys에는 키를 쓰지 않는다(기능 명세 4.4).
 * 키 저장소 백업은 7일, DB 백업은 35일로 별도 운영해야 한다.
 *
 * <p>삭제는 CAS로 삭제 표식을 먼저 쓰고 그 전의 모든 버전을 영구 파기한다.
 * 표식을 남겨 지연된 가입 재시도나 응답 유실 후 재시도가 키를 되살리지 못하게 한다.
 */
public final class VaultUserDataKeyProvider implements UserDataKeyProvider {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_RESPONSE_BYTES = 65_536;

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final VaultCryptoProperties properties;
    private final String keyId;

    public VaultUserDataKeyProvider(HttpClient client, ObjectMapper mapper,
            VaultCryptoProperties properties, String keyId) {
        properties.validate();
        if (!VaultCryptoProperties.validSegment(keyId)) {
            throw new IllegalArgumentException("KMS 키 식별자를 확인해 주세요.");
        }
        this.client = client;
        this.mapper = mapper;
        this.properties = properties;
        this.keyId = keyId;
    }

    @Override
    public void createIfAbsent(UUID userId) {
        StoredKey existing = read(userId);
        if (existing != null) {
            requireActive(existing);
            return;
        }
        JsonNode generated = json(send("POST", transitPath("datakey/wrapped"),
                Map.of("bits", 256, "context", context(userId))), 200);
        String wrapped = textAt(generated, "/data/ciphertext");
        requireWrappedKey(wrapped);
        var result = send("POST", keyPath("data", userId), Map.of(
                "options", Map.of("cas", 0),
                "data", Map.of("wrappedKey", wrapped, "state", "ACTIVE", "kmsKeyId", keyId)));
        if (result.status() == 200) return;

        // Vault CAS 경합은 400이다. 현재 키를 확인한 뒤에만 이미 완료된 생성으로 인정한다.
        if (result.status() == 400) {
            StoredKey winner = read(userId);
            if (winner != null) {
                requireActive(winner);
                return;
            }
        }
        throw unavailable();
    }

    @Override
    public byte[] load(UUID userId) {
        StoredKey stored = read(userId);
        if (stored == null) throw unavailable();
        requireActive(stored);
        var response = send("POST", transitPath("decrypt"),
                Map.of("ciphertext", stored.wrappedKey(), "context", context(userId)));
        try {
            String encoded = textAt(json(response, 200), "/data/plaintext");
            byte[] key = Base64.getDecoder().decode(encoded);
            if (key.length != 32) {
                Arrays.fill(key, (byte) 0);
                throw unavailable();
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw unavailable();
        } finally {
            Arrays.fill(response.body(), (byte) 0);
        }
    }

    @Override
    public void delete(UUID userId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            StoredKey stored = read(userId);
            if (stored != null && stored.deleted()) {
                destroyPreviousVersions(userId, stored.version());
                return;
            }
            int version = stored == null ? 0 : stored.version();
            var result = send("POST", keyPath("data", userId), Map.of(
                    "options", Map.of("cas", version), "data", Map.of("state", "DELETED")));
            if (result.status() == 400) continue;
            int deletedVersion = json(result, 200).at("/data/version").asInt(0);
            if (deletedVersion != version + 1) throw unavailable();
            destroyPreviousVersions(userId, deletedVersion);
            return;
        }
        throw unavailable();
    }

    private void destroyPreviousVersions(UUID userId, int deletedVersion) {
        // 작은 묶음으로 나눠 응답이 유실된 삭제도 반복할 수 있다. 이미 파기한 버전의 재파기는 안전하다.
        for (int start = 1; start < deletedVersion; start += 100) {
            var versions = new ArrayList<Integer>();
            for (int version = start; version < Math.min(start + 100, deletedVersion); version++) {
                versions.add(version);
            }
            var result = send("PUT", keyPath("destroy", userId), Map.of("versions", versions));
            if (result.status() != 204) throw unavailable();
        }
    }

    private StoredKey read(UUID userId) {
        var result = send("GET", keyPath("data", userId), null);
        if (result.status() == 404) return null;
        JsonNode body = json(result, 200);
        int version = body.at("/data/metadata/version").asInt(0);
        if (version < 1) throw unavailable();
        String state = textAt(body, "/data/data/state");
        if ("DELETED".equals(state)) return new StoredKey(version, null, true);
        if (!"ACTIVE".equals(state) || !keyId.equals(textAt(body, "/data/data/kmsKeyId"))) {
            throw unavailable();
        }
        String wrapped = textAt(body, "/data/data/wrappedKey");
        requireWrappedKey(wrapped);
        return new StoredKey(version, wrapped, false);
    }

    private VaultResponse send(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(properties.getAddress().resolve("/v1/" + path))
                    .timeout(properties.getTimeout())
                    .header("X-Vault-Token", properties.getToken())
                    .header("Content-Type", "application/json");
            var publisher = body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body));
            var response = SafeHttp.send(client, builder.method(method, publisher).build(), MAX_RESPONSE_BYTES);
            if (response.body().length > MAX_RESPONSE_BYTES) {
                Arrays.fill(response.body(), (byte) 0);
                throw unavailable();
            }
            return new VaultResponse(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | RuntimeException exception) {
            // HTTP/JSON 예외는 토큰·본문·키를 포함할 수 있다. 로그와 cause 모두 전달하지 않는다.
            throw unavailable();
        }
    }

    private JsonNode json(VaultResponse response, int status) {
        if (response.status() != status) throw unavailable();
        try {
            return mapper.readTree(response.body());
        } catch (RuntimeException exception) {
            throw unavailable();
        }
    }

    private String textAt(JsonNode node, String pointer) {
        var value = node.at(pointer);
        if (!value.isString() || value.asString().isBlank()) throw unavailable();
        return value.asString();
    }

    private String transitPath(String operation) {
        return properties.getTransitMount() + "/" + operation + "/" + keyId;
    }

    private String keyPath(String operation, UUID userId) {
        return properties.getKeyMount() + "/" + operation + "/users/" + userId;
    }

    private String context(UUID userId) {
        return Base64.getEncoder().encodeToString(("bedrock:user:" + userId).getBytes(StandardCharsets.UTF_8));
    }

    private void requireActive(StoredKey key) {
        if (key.deleted()) throw unavailable();
    }

    private void requireWrappedKey(String value) {
        if (!value.matches("vault:v[1-9][0-9]*:[A-Za-z0-9+/=]+")) throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE);
    }

    private record StoredKey(int version, String wrappedKey, boolean deleted) {
        @Override public String toString() { return "StoredKey[redacted]"; }
    }

    private record VaultResponse(int status, byte[] body) {
        @Override public String toString() { return "VaultResponse[status=" + status + "]"; }
    }
}
