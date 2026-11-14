package com.bedrock.app.mail.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** 공통 명세의 mail_codes 저장 모델. 기능별 검증과 변경 연산은 담당 패키지에서 구현한다. */
@Entity
@Table(name = "mail_codes")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class MailCode {
    public static final Duration LIFETIME = Duration.ofMinutes(10);
    public static final Duration RESEND_DELAY = Duration.ofSeconds(60);
    public static final int MAX_ATTEMPTS = 5;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "email", nullable = false, length = 320)
    private String email;

    @Column(name = "purpose", nullable = false, length = 30)
    private String purpose;

    @Column(name = "session_binding_id", nullable = false, length = 128)
    private String sessionBindingId;

    @Column(name = "code_hmac", nullable = false, length = 128)
    private String codeHmac;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private Integer attemptCount = 0;

    @Column(name = "resend_available_at", nullable = false)
    private Instant resendAvailableAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** HMAC 비교는 호출자가 항상 수행하고, 여기서는 수명과 1회 소비를 판정한다. */
    public Verification verify(boolean macMatches, String binding, MailCodePurpose expectedPurpose, Instant now) {
        Objects.requireNonNull(now);
        if (!sessionBindingId.equals(binding) || !purpose.equals(expectedPurpose.name())) {
            return Verification.INVALID;
        }
        if (consumedAt != null || !now.isBefore(expiresAt) || attemptCount >= MAX_ATTEMPTS) {
            return Verification.INVALID;
        }
        // 인증할 수 없는 작업은 HMAC 앞에 disabled:를 붙여 저장한다.
        // 코드 원문이나 되돌릴 수 있는 값을 저장하지 않고 기존 칼럼으로 구분한다.
        if (!macMatches || codeHmac.startsWith("disabled:")) {
            attemptCount++;
            if (attemptCount >= MAX_ATTEMPTS) consumedAt = now;
            return Verification.WRONG_CODE;
        }
        consumedAt = now;
        return Verification.VERIFIED;
    }

    public boolean canResend(Instant now) {
        return consumedAt == null && attemptCount < MAX_ATTEMPTS
                && now.isBefore(expiresAt) && !now.isBefore(resendAvailableAt);
    }

    public void invalidate(Instant now) {
        if (consumedAt == null) consumedAt = now;
    }

    public enum Verification { VERIFIED, WRONG_CODE, INVALID }
}
