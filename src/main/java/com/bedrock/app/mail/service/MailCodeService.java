package com.bedrock.app.mail.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.domain.MailBounce;
import com.bedrock.app.mail.domain.MailCode;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.persistence.MailBounceRepository;
import com.bedrock.app.mail.persistence.MailCodeRepository;
import com.bedrock.app.mail.security.MailCodeProtector;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** F-04 공통 코드 흐름. 비밀번호 검증과 계정 변경은 VerifiedChallenge를 받은 뒤에만 진행한다. */
@Service("mailCodeService")
public class MailCodeService {
    private final MailCodeRepository codes;
    private final MailBounceRepository bounces;
    private final MailStateTransactions transactions;
    private final MailCodeProtector protector;
    private final MailRateLimiter limits;
    private final MailDeliveryService delivery;
    private final Clock clock;

    public MailCodeService(MailCodeRepository codes, MailBounceRepository bounces,
            MailStateTransactions transactions, MailCodeProtector protector, MailRateLimiter limits,
            MailDeliveryService delivery, @Qualifier("be1Clock") Clock clock) {
        this.codes = codes;
        this.bounces = bounces;
        this.transactions = transactions;
        this.protector = protector;
        this.limits = limits;
        this.delivery = delivery;
        this.clock = clock;
    }

    public Challenge start(IssueCommand command) {
        return issue(command, false);
    }

    /** command는 서버가 세션에 보관한 원래 작업에서 복원하며 클라이언트가 목적/주소를 바꾸지 않는다. */
    public Challenge resend(IssueCommand command) {
        return issue(command, true);
    }

    private Challenge issue(IssueCommand command, boolean resend) {
        command.validate();
        String email = command.email().strip().toLowerCase(Locale.ROOT);
        if (resend) {
            // 60초 전의 반복 클릭이 발송 한도를 소모하지 않게 먼저 검사한다.
            // 예약 뒤에도 잠금 안에서 다시 검사해 동시 재발송 두 건이 모두 발급되지 않게 한다.
            transactions.locked(() -> {
                requireResendAllowed(command, email, clock.instant());
                return null;
            });
        }
        String code = protector.generateCode();
        UUID id = UUID.randomUUID();
        String protectedCode = protector.protect(id, email, command.sessionBinding(), command.purpose(), code);
        boolean addressQuota = limits.reserveRequest(email, command.sessionBinding(), command.requester());
        return transactions.locked(() -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            if (resend) requireResendAllowed(command, email, now);
            codes.invalidateSession(command.sessionBinding(), now);
            if (command.purpose() == MailCodePurpose.SIGNUP) codes.invalidatePendingSignup(email, now);
            var address = bounces.findById(email).orElseGet(() -> MailBounce.builder().email(email).build());
            boolean canDeliver = address.canSend(now) && addressQuota;
            bounces.save(address);
            boolean canVerify = canDeliver && command.deliveryKind() == DeliveryKind.CODE;
            var challenge = MailCode.builder().id(id).email(email).sessionBindingId(command.sessionBinding())
                    .purpose(command.purpose().name()).codeHmac(canVerify ? protectedCode : "disabled:" + protectedCode)
                    .resendAvailableAt(now.plus(MailCode.RESEND_DELAY)).expiresAt(now.plus(MailCode.LIFETIME))
                    .createdAt(now).build();
            codes.saveAndFlush(challenge);
            if (canDeliver && command.deliveryKind() != DeliveryKind.NONE) {
                var message = new MailDeliveryService.Message(id, email, command.purpose(), command.platform(),
                        command.deliveryKind(), code, now);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { delivery.submit(message); }
                });
            }
            // 실제 메일 종류/발송 여부/주소별 차단은 응답에 넣지 않는다.
            return new Challenge("MAIL_CODE_REQUIRED", challenge.getExpiresAt(), challenge.getResendAvailableAt());
        });
    }

    private void requireResendAllowed(IssueCommand command, String email, Instant now) {
        var previous = codes.findFirstBySessionBindingIdAndConsumedAtIsNullOrderByCreatedAtDesc(command.sessionBinding())
                .orElseThrow(() -> new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_CODE));
        if (!previous.getEmail().equals(email) || !previous.getPurpose().equals(command.purpose().name())
                || !now.isBefore(previous.getExpiresAt()) || previous.getAttemptCount() >= MailCode.MAX_ATTEMPTS) {
            throw new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_CODE);
        }
        if (!previous.canResend(now)) throw new BusinessException(Be1AuthAiErrorCode.MAIL_CODE_RATE_LIMITED);
    }

    public VerifiedChallenge verify(String sessionBinding, MailCodePurpose purpose, String candidate) {
        VerifiedChallenge result = transactions.locked(() -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            var pending = codes.findFirstBySessionBindingIdAndConsumedAtIsNullOrderByCreatedAtDesc(sessionBinding);
            if (pending.isEmpty()) {
                protector.matches(null, new UUID(0, 0), "", sessionBinding, purpose, candidate);
                return null;
            }
            MailCode challenge = pending.get();
            boolean matches = protector.matches(challenge.getCodeHmac(), challenge.getId(), challenge.getEmail(),
                    sessionBinding, purpose, candidate);
            var address = bounces.findById(challenge.getEmail())
                    .orElseGet(() -> MailBounce.builder().email(challenge.getEmail()).build());
            boolean blocked = address.isBlocked(now);
            var outcome = blocked ? MailCode.Verification.INVALID : challenge.verify(matches, sessionBinding, purpose, now);
            if (outcome == MailCode.Verification.WRONG_CODE) address.recordFailedCode(now);
            if (outcome == MailCode.Verification.VERIFIED) address.recordVerifiedCode();
            bounces.save(address);
            codes.saveAndFlush(challenge);
            return outcome == MailCode.Verification.VERIFIED
                    ? new VerifiedChallenge(challenge.getId(), challenge.getEmail(), purpose) : null;
        });
        // 예외를 트랜잭션 밖에서 던져 오답 횟수와 다섯 번째 오답의 폐기가 롤백되지 않게 한다.
        if (result == null) throw new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_CODE);
        return result;
    }

    public void cancelPendingSignup(String email) {
        transactions.locked(() -> {
            codes.invalidatePendingSignup(email.strip().toLowerCase(Locale.ROOT), clock.instant());
            return null;
        });
    }

    public enum DeliveryKind { CODE, ALREADY_REGISTERED, NONE }

    public record Challenge(String nextStep, Instant expiresAt, Instant resendAvailableAt) {}

    public record VerifiedChallenge(UUID id, String email, MailCodePurpose purpose) {
        @Override public String toString() { return "VerifiedChallenge[id=" + id + "]"; }
    }

    public record IssueCommand(String email, String sessionBinding, String requester, String platform,
            MailCodePurpose purpose, DeliveryKind deliveryKind) {
        void validate() {
            if (email == null || email.length() > 320 || !email.strip().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                    || sessionBinding == null || !sessionBinding.matches("[A-Za-z0-9_-]{22,128}")
                    || requester == null || requester.isBlank() || requester.length() > 128
                    || platform == null || !Set.of("IOS", "IPADOS", "WEB").contains(platform)
                    || purpose == null || deliveryKind == null) {
                throw new IllegalArgumentException("메일 코드 요청 형식을 확인해 주세요.");
            }
        }
        @Override public String toString() { return "IssueCommand[redacted]"; }
    }
}
