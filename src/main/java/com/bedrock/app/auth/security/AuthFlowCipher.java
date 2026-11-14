package com.bedrock.app.auth.security;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** 가입 전 Apple 교환 토큰을 Redis에 평문으로 남기지 않는 임시 봉투. */
@Component("authFlowCipher")
public class AuthFlowCipher {
    private final byte[] key;
    private final SecureRandom random = new SecureRandom();
    public AuthFlowCipher(@Value("${bedrock.auth.flow-key:}") String encodedKey) {
        if (encodedKey.isBlank()) { key = null; return; }
        try { key = Base64.getDecoder().decode(encodedKey); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("인증 흐름 키 설정이 올바르지 않습니다."); }
        if (key.length != 32) throw new IllegalStateException("인증 흐름 키는 32바이트여야 합니다.");
    }

    public String encrypt(UUID flowId, String plain) {
        byte[] input = plain.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            var cipher = cipher(Cipher.ENCRYPT_MODE, flowId, nonce);
            byte[] result = cipher.doFinal(input);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(12 + result.length).put(nonce).put(result).array());
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
        finally { Arrays.fill(input, (byte) 0); }
    }

    public String decrypt(UUID flowId, String encoded) {
        byte[] plain = null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            if (bytes.length < 28 || bytes.length > 32768) throw unavailable();
            plain = cipher(Cipher.DECRYPT_MODE, flowId, Arrays.copyOf(bytes, 12)).doFinal(bytes, 12, bytes.length - 12);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (BusinessException exception) { throw exception; }
        catch (Exception exception) { throw unavailable(); }
        finally { if (plain != null) Arrays.fill(plain, (byte) 0); }
    }

    private Cipher cipher(int mode, UUID flowId, byte[] nonce) throws Exception {
        if (key == null) throw new BusinessException(CommonErrorCode.FEATURE_NOT_READY);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("bedrock:oauth-flow:" + flowId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
    private BusinessException unavailable() { return new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE); }
}
