package com.bedrock.app.auth.security;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Have I Been Pwned Pwned Passwords: https://haveibeenpwned.com/API/v3#PwnedPasswords
 * 원문/전체 해시 대신 SHA-1 앞 5자리만 보내며 응답 패딩을 요청한다.
 * SHA-1은 이 조회 프로토콜에만 쓰고 비밀번호 저장은 Argon2id로 한다.
 */
@Component("hibpPasswordLookup")
public class HibpPasswordLookup implements CompromisedPasswordLookup {
    private final HttpClient client;

    @Autowired
    public HibpPasswordLookup() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    public HibpPasswordLookup(HttpClient client) { this.client = client; }

    @Override
    public boolean isCompromised(String password) {
        byte[] input = password.getBytes(StandardCharsets.UTF_8);
        byte[] received = null;
        try {
            String hash = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(input));
            var request = HttpRequest.newBuilder(URI.create("https://api.pwnedpasswords.com/range/" + hash.substring(0, 5)))
                    .timeout(Duration.ofSeconds(3)).header("Add-Padding", "true")
                    .header("User-Agent", "Bedrock-Password-Check").GET().build();
            var response = SafeHttp.send(client, request, 1_000_000);
            received = response.body();
            String text = new String(received, StandardCharsets.US_ASCII);
            if (response.statusCode() != 200 || text.isBlank()) throw unavailable();
            boolean found = false;
            for (String line : text.lines().toList()) {
                if (!line.matches("[0-9A-F]{35}:[0-9]{1,15}")) throw unavailable();
                if (line.startsWith(hash.substring(5) + ":") && Long.parseLong(line.substring(36)) > 0) found = true;
            }
            return found;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
            throw unavailable();
        } finally {
            Arrays.fill(input, (byte) 0);
            if (received != null) Arrays.fill(received, (byte) 0);
        }
    }

    private BusinessException unavailable() { return new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE); }
}
