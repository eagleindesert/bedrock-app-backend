package com.bedrock.app.crypto.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.crypto.key.UserDataKeyProvider;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** AES-256-GCM 봉투 암호화. 사용자 ID와 형식 버전을 인증하고 평문 키를 캐시하지 않는다. */
public final class EnvelopeUserCrypto implements UserCryptoPort {
    private static final String PREFIX = "bedrock:v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final UserDataKeyProvider keys;
    private final SecureRandom random = new SecureRandom();

    public EnvelopeUserCrypto(UserDataKeyProvider keys) {
        this.keys = keys;
    }

    @Override
    public void createDataKeyForUser(UUID userId) {
        keys.createIfAbsent(Objects.requireNonNull(userId));
    }

    @Override
    public String encrypt(UUID userId, String plainText) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(plainText);
        byte[] key = keys.load(userId);
        byte[] plain = plainText.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, userId, key, nonce).doFinal(plain);
            byte[] envelope = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            // 제공자 예외에는 원문/키가 들어갈 수 있어 cause도 외부로 넘기지 않는다.
            throw new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE);
        } finally {
            Arrays.fill(key, (byte) 0);
            Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public String decrypt(UUID userId, String cipherText) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(cipherText);
        byte[] key = null;
        byte[] plain = null;
        try {
            if (!cipherText.startsWith(PREFIX)) {
                throw new GeneralSecurityException();
            }
            byte[] envelope = Base64.getUrlDecoder().decode(cipherText.substring(PREFIX.length()));
            if (envelope.length < NONCE_BYTES + TAG_BITS / 8) {
                throw new GeneralSecurityException();
            }
            key = keys.load(userId);
            byte[] nonce = Arrays.copyOf(envelope, NONCE_BYTES);
            plain = cipher(Cipher.DECRYPT_MODE, userId, key, nonce)
                    .doFinal(envelope, NONCE_BYTES, envelope.length - NONCE_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE);
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
            if (plain != null) Arrays.fill(plain, (byte) 0);
        }
    }

    @Override
    public void deleteDataKeyForUser(UUID userId) {
        keys.delete(Objects.requireNonNull(userId));
    }

    private Cipher cipher(int mode, UUID userId, byte[] key, byte[] nonce) throws GeneralSecurityException {
        if (key.length != 32) throw new GeneralSecurityException();
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD((PREFIX + userId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
