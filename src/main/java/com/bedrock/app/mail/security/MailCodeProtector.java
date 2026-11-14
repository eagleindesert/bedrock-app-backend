package com.bedrock.app.mail.security;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.mail.domain.MailCodePurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** 코드 저장소 밖에서 주입한 256비트 이상의 키로 코드와 작업 맥락을 함께 인증한다. */
@Component("mailCodeProtector")
public final class MailCodeProtector {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public MailCodeProtector(@Value("${bedrock.mail.code-hmac-key:}") String encodedKey) {
        if (encodedKey.isBlank()) {
            key = null;
            return;
        }
        try {
            key = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("메일 코드 HMAC 키 설정을 확인해 주세요.");
        }
        if (key.length < 32) throw new IllegalArgumentException("메일 코드 HMAC 키는 256비트 이상이어야 합니다.");
    }

    public String generateCode() {
        requireConfigured();
        return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    public String protect(UUID challengeId, String email, String sessionBindingId, MailCodePurpose purpose, String code) {
        return HexFormat.of().formatHex(digest("mail-code-v1", challengeId.toString(), email,
                sessionBindingId, purpose.name(), code));
    }

    public boolean matches(String expectedMac, UUID challengeId, String email, String sessionBindingId,
            MailCodePurpose purpose, String code) {
        // 형식이 틀려도 HMAC 한 번과 고정 길이 비교를 수행한다.
        boolean wellFormed = code != null && code.matches("[0-9]{6}");
        byte[] actual = digest("mail-code-v1", challengeId.toString(), email, sessionBindingId,
                purpose.name(), wellFormed ? code : "invalid-code");
        byte[] expected = new byte[32];
        boolean validMac = expectedMac != null && expectedMac.matches("[0-9a-f]{64}");
        if (validMac) expected = HexFormat.of().parseHex(expectedMac);
        try {
            return MessageDigest.isEqual(expected, actual) & wellFormed & validMac;
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    /** 요청자 IP 등을 한도 저장소에 원문 대신 남길 때 사용한다. 코드와 용도를 분리한다. */
    public String requesterFingerprint(String requester) {
        return HexFormat.of().formatHex(digest("mail-requester-v1", requester));
    }

    private byte[] digest(String... fields) {
        requireConfigured();
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                mac.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                mac.update(bytes);
                Arrays.fill(bytes, (byte) 0);
            }
            return mac.doFinal();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("메일 코드를 처리할 수 없습니다.");
        }
    }

    private void requireConfigured() {
        if (key == null) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
    }
}
