package com.bedrock.app.mail;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.mail.domain.MailBounce;
import com.bedrock.app.mail.domain.MailCode;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.security.MailCodeProtector;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** JUnit과 네트워크 없는 독립 실행에서 함께 쓰는 F-04 계약 검증. */
public final class MailCodeContractChecks {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final String EMAIL = "account@example.com";
    private static final String BINDING = UUID.randomUUID().toString();
    private static final String CODE = "001234";

    public static void main(String[] args) {
        hmacBindsEveryPartOfTheChallenge();
        expiryAndSingleUse();
        fiveFailuresConsumeChallenge();
        resendBoundaries();
        addressLockoutAndBounce();
        missingSecretFailsClosed();
        System.out.println("Mail code contract checks passed (6 groups).");
    }

    static void hmacBindsEveryPartOfTheChallenge() {
        var protector = protector();
        UUID id = UUID.randomUUID();
        String mac = protector.protect(id, EMAIL, BINDING, MailCodePurpose.SIGNUP, CODE);
        require(mac.matches("[0-9a-f]{64}") && !mac.equals(CODE), "코드는 HMAC만 저장해야 한다.");
        require(protector.matches(mac, id, EMAIL, BINDING, MailCodePurpose.SIGNUP, CODE), "앞의 0을 보존한다.");
        require(!protector.matches(mac, UUID.randomUUID(), EMAIL, BINDING, MailCodePurpose.SIGNUP, CODE), "작업 ID 바인딩");
        require(!protector.matches(mac, id, "other@example.com", BINDING, MailCodePurpose.SIGNUP, CODE), "주소 바인딩");
        require(!protector.matches(mac, id, EMAIL, "other-session", MailCodePurpose.SIGNUP, CODE), "세션 바인딩");
        require(!protector.matches(mac, id, EMAIL, BINDING, MailCodePurpose.PASSWORD_RESET, CODE), "목적 바인딩");
        for (String invalid : new String[] {null, "1234", "123456", "１２３４５６", "001234\n", ""}) {
            require(!protector.matches(mac, id, EMAIL, BINDING, MailCodePurpose.SIGNUP, invalid), "잘못된 코드 형식/값 거부");
        }
        require(!protector.matches("disabled:" + mac, id, EMAIL, BINDING, MailCodePurpose.SIGNUP, CODE),
                "없는 계정과 기존 활성 계정에 대한 작업은 숫자가 같아도 실패해야 한다.");
        for (int attempt = 0; attempt < 100; attempt++) {
            require(protector.generateCode().matches("[0-9]{6}"), "암호학적 난수 코드는 숫자 여섯 자리다.");
        }
    }

    static void expiryAndSingleUse() {
        var beforeExpiry = challenge();
        require(beforeExpiry.verify(true, BINDING, MailCodePurpose.SIGNUP, NOW.plusSeconds(599)) == MailCode.Verification.VERIFIED,
                "10분 직전의 코드는 유효하다.");
        require(beforeExpiry.verify(true, BINDING, MailCodePurpose.SIGNUP, NOW.plusSeconds(599)) == MailCode.Verification.INVALID,
                "같은 코드는 한 번만 사용한다.");
        require(challenge().verify(true, BINDING, MailCodePurpose.SIGNUP, NOW.plusSeconds(600)) == MailCode.Verification.INVALID,
                "10분 경계부터 만료다.");
        var bound = challenge();
        require(bound.verify(true, "other-session", MailCodePurpose.SIGNUP, NOW) == MailCode.Verification.INVALID,
                "다른 세션의 코드는 사용할 수 없다.");
        require(bound.verify(true, BINDING, MailCodePurpose.PASSWORD_RESET, NOW) == MailCode.Verification.INVALID,
                "다른 목적의 코드는 사용할 수 없다.");
        require(bound.getConsumedAt() == null && bound.getAttemptCount() == 0, "남의 요청으로 코드를 소비하지 않는다.");
    }

    static void fiveFailuresConsumeChallenge() {
        var challenge = challenge();
        for (int attempt = 1; attempt <= 5; attempt++) {
            require(challenge.verify(false, BINDING, MailCodePurpose.SIGNUP, NOW) == MailCode.Verification.WRONG_CODE, "오답 판정");
            require(challenge.getAttemptCount() == attempt, "오답 횟수 누적");
        }
        require(challenge.getConsumedAt().equals(NOW), "다섯 번 실패하면 즉시 끝난다.");
        require(challenge.verify(true, BINDING, MailCodePurpose.SIGNUP, NOW) == MailCode.Verification.INVALID,
                "실패 한도 뒤에는 정답도 받지 않는다.");
        require(!challenge.canResend(NOW.plusSeconds(60)), "한도에 닿은 작업은 새로 시작해야 한다.");
    }

    static void resendBoundaries() {
        var challenge = challenge();
        require(!challenge.canResend(NOW.plusSeconds(59)), "60초 전에는 재발송하지 않는다.");
        require(challenge.canResend(NOW.plusSeconds(60)), "60초부터 재발송할 수 있다.");
        require(!challenge.canResend(NOW.plusSeconds(600)), "만료된 가입은 다시 시작한다.");
        challenge.invalidate(NOW.plusSeconds(70));
        require(!challenge.canResend(NOW.plusSeconds(71)), "새 작업을 시작하면 이전 작업을 재발송할 수 없다.");
    }

    static void addressLockoutAndBounce() {
        var address = MailBounce.builder().email(EMAIL).build();
        for (int attempt = 0; attempt < 99; attempt++) address.recordFailedCode(NOW);
        require(!address.isBlocked(NOW), "99회 실패까지 주소를 잠그지 않는다.");
        address.recordFailedCode(NOW);
        require(address.isBlocked(NOW) && !address.canSend(NOW), "100회 실패하면 발송·확인을 막는다.");
        address.recordFailedCode(NOW.plusSeconds(100));
        require(address.getBlockedUntil().equals(NOW.plus(Duration.ofHours(24))), "차단 중 요청으로 차단 시간을 늘리지 않는다.");
        require(address.isBlocked(NOW.plusSeconds(86_399)), "24시간 직전까지 차단한다.");
        require(!address.isBlocked(NOW.plusSeconds(86_400)), "24시간 뒤에 차단과 실패 횟수를 초기화한다.");
        require(address.getConsecutiveFailCount() == 0, "주소 실패 횟수 초기화");
        address.recordFailedCode(NOW.plusSeconds(86_401));
        address.recordVerifiedCode();
        require(address.getConsecutiveFailCount() == 0, "성공하면 연속 실패가 끊긴다.");
        address.recordHardBounce();
        require(!address.canSend(NOW.plus(Duration.ofDays(100))), "반송 주소에 다시 발송하지 않는다.");
    }

    static void missingSecretFailsClosed() {
        try {
            new MailCodeProtector("").generateCode();
            throw new AssertionError("키 없이 코드 기능이 동작하면 안 된다.");
        } catch (BusinessException exception) {
            require(exception.getErrorCode() == CommonErrorCode.FEATURE_NOT_READY, "미설정은 명시적으로 실패한다.");
        }
        try {
            new MailCodeProtector("sensitive-invalid-key");
            throw new AssertionError("잘못된 HMAC 키를 받으면 안 된다.");
        } catch (IllegalArgumentException exception) {
            require(!exception.getMessage().contains("sensitive-invalid-key") && exception.getCause() == null,
                    "설정 오류에 키 값을 노출하지 않는다.");
        }
    }

    private static MailCodeProtector protector() {
        return new MailCodeProtector(Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static MailCode challenge() {
        UUID id = UUID.randomUUID();
        return MailCode.builder().id(id).email(EMAIL).sessionBindingId(BINDING).purpose("SIGNUP")
                .codeHmac(protector().protect(id, EMAIL, BINDING, MailCodePurpose.SIGNUP, CODE))
                .expiresAt(NOW.plus(MailCode.LIFETIME)).resendAvailableAt(NOW.plus(MailCode.RESEND_DELAY))
                .createdAt(NOW).build();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
