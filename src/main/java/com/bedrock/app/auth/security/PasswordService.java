package com.bedrock.app.auth.security;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service("be1PasswordService")
public class PasswordService {
    private static final Set<String> COMMON = Set.of("password", "password1", "password123", "12345678",
            "123456789", "qwerty123", "qwertyuiop", "11111111", "00000000", "abcdefgh", "iloveyou");
    private final PasswordEncoder encoder;
    private final CompromisedPasswordLookup compromised;
    private final String dummyHash;

    public PasswordService(PasswordEncoder encoder, CompromisedPasswordLookup compromised) {
        this.encoder = encoder;
        this.compromised = compromised;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** 가입/재설정에서 이메일 코드 확인 뒤에만 호출한다. 64자를 넘는 암호도 받는다. */
    public String encodeVerifiedPassword(String password) {
        if (password == null || password.codePointCount(0, password.length()) < 8 || password.length() > 4096
                || COMMON.contains(password.toLowerCase(Locale.ROOT))) {
            throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
        }
        if (compromised.isCompromised(password)) throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
        return encoder.encode(password);
    }

    public boolean matches(String password, String storedHash) {
        boolean bounded = password != null && password.length() <= 4096;
        // 존재하지 않는 계정/소셜 전용 계정/지나치게 긴 입력에도 해시 검증 한 번을 수행한다.
        boolean matched = encoder.matches(bounded ? password : "invalid-password-input", storedHash == null ? dummyHash : storedHash);
        return matched & bounded & storedHash != null;
    }

    public String upgradedHash(String password, String storedHash) {
        return encoder.upgradeEncoding(storedHash) ? encoder.encode(password) : storedHash;
    }
}
