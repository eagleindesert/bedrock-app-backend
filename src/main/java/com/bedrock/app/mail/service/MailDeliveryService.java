package com.bedrock.app.mail.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.domain.MailBounce;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.persistence.MailBounceRepository;
import com.bedrock.app.mail.persistence.MailCodeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.SendFailedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 코드 원문은 비동기 발송 중 메모리에만 존재한다. SMTP 응답/예외/주소/본문은 로그에 남기지 않는다. */
@Service("mailDeliveryService")
public class MailDeliveryService {
    private final JavaMailSender sender;
    private final TaskExecutor executor;
    private final ScheduledExecutorService retryScheduler;
    private final MailCodeRepository codes;
    private final MailBounceRepository bounces;
    private final MailStateTransactions transactions;
    private final MailRateLimiter limits;
    private final MeterRegistry metrics;
    private final Clock clock;
    private final String from;
    private final MailOperationalMonitor monitor;

    public MailDeliveryService(JavaMailSender sender, @Qualifier("applicationTaskExecutor") TaskExecutor executor,
            @Qualifier("mailRetryScheduler") ScheduledExecutorService retryScheduler, MailCodeRepository codes,
            MailBounceRepository bounces, MailStateTransactions transactions, MailRateLimiter limits,
            MeterRegistry metrics, @Qualifier("be1Clock") Clock clock, @Value("${bedrock.mail.from-address}") String from,
            MailOperationalMonitor monitor) {
        this.sender = sender;
        this.executor = executor;
        this.retryScheduler = retryScheduler;
        this.codes = codes;
        this.bounces = bounces;
        this.transactions = transactions;
        this.limits = limits;
        this.metrics = metrics;
        this.clock = clock;
        this.from = from;
        this.monitor = monitor;
    }

    public void submit(Message message) {
        try {
            executor.execute(() -> attempt(message, 0));
        } catch (RejectedExecutionException exception) {
            count("queue_full");
            throw new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE);
        }
    }

    /** 계정 알림도 코드와 같은 전역·주소별 SMTP 한도와 반송 차단을 공유한다. */
    public void submitNotification(String email, String subject, String body) {
        var notification = new Notification(email, subject, body);
        try { executor.execute(() -> notify(notification, 0)); }
        catch (RejectedExecutionException exception) { count("queue_full"); }
    }

    private void notify(Notification notification, int retry) {
        try {
            if (bounces.findById(notification.email()).map(address -> !address.canSend(clock.instant())).orElse(false)
                    || !limits.reserveRetry(notification.email())) {
                count("rate_limited");
                return;
            }
            var mail = new SimpleMailMessage();
            mail.setFrom(from);
            mail.setTo(notification.email());
            mail.setSubject(notification.subject());
            mail.setText(notification.body());
            sender.send(mail);
            count("sent");
        } catch (RuntimeException exception) {
            if (exception instanceof MailException mailFailure && isHardBounce(mailFailure, notification.email())) {
                try { recordHardBounce(notification.email()); }
                catch (RuntimeException ignored) { count("failed"); }
                return;
            }
            if (retry >= 2) { count("failed"); return; }
            try {
                retryScheduler.schedule(() -> {
                    try { executor.execute(() -> notify(notification, retry + 1)); }
                    catch (RejectedExecutionException ignored) { count("queue_full"); }
                }, retry == 0 ? 5 : 30, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) { count("queue_full"); }
        }
    }

    private record Notification(String email, String subject, String body) {
        @Override public String toString() { return "Notification[redacted]"; }
    }

    private void attempt(Message message, int retry) {
        try {
            var challenge = codes.findById(message.challengeId());
            if (challenge.isEmpty() || challenge.get().getConsumedAt() != null
                    || !clock.instant().isBefore(challenge.get().getExpiresAt())
                    || bounces.findById(message.email()).map(address -> !address.canSend(clock.instant())).orElse(false)) {
                count("cancelled");
                return;
            }
            if (retry > 0 && !limits.reserveRetry(message.email())) {
                count("rate_limited");
                return;
            }
            sender.send(toMail(message));
            count("sent");
        } catch (MailException exception) {
            if (isHardBounce(exception, message.email())) {
                try {
                    recordHardBounce(message.email());
                } catch (RuntimeException storageFailure) {
                    // 비동기 실행기의 기본 예외 처리기로 DB/SMTP 예외를 흘리지 않는다.
                    retry(message, retry);
                }
                return;
            }
            retry(message, retry);
        } catch (RuntimeException exception) {
            retry(message, retry);
        }
    }

    private void retry(Message message, int retry) {
        if (retry >= 2) {
            count("failed");
            return;
        }
        try {
            retryScheduler.schedule(() -> {
                try {
                    executor.execute(() -> attempt(message, retry + 1));
                } catch (RejectedExecutionException exception) {
                    count("queue_full");
                }
            }, retry == 0 ? 5 : 30, TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            count("queue_full");
        }
    }

    /** SMTP 또는 인증된 제공자 반송 연동에서만 호출한다. 외부에 무인증 반송 API를 노출하지 않는다. */
    public void recordHardBounce(String email) {
        boolean newlyBounced = transactions.locked(() -> {
            var address = bounces.findById(email).orElseGet(() -> MailBounce.builder().email(email).build());
            boolean first = !address.isBounced();
            address.recordHardBounce();
            bounces.saveAndFlush(address);
            return first;
        });
        if (newlyBounced) count("bounced");
    }

    private boolean isHardBounce(MailException exception, String email) {
        if (!(exception instanceof MailSendException failure)) return false;
        return Arrays.stream(failure.getMessageExceptions()).anyMatch(cause -> {
            if (!(cause instanceof SendFailedException sendFailure) || sendFailure.getInvalidAddresses() == null) return false;
            return Arrays.stream(sendFailure.getInvalidAddresses()).anyMatch(address -> address.toString().equalsIgnoreCase(email));
        });
    }

    private SimpleMailMessage toMail(Message message) {
        var mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.email());
        mail.setSubject("[베드락] " + message.purpose().description());
        String body = message.kind() == MailCodeService.DeliveryKind.ALREADY_REGISTERED
                ? "이미 가입된 주소입니다. 로그인하거나 비밀번호를 재설정해 주세요."
                : "인증 코드: " + message.code() + "\n시작한 화면에서 입력해 주세요. 코드는 10분 동안 유효합니다.";
        mail.setText(body + "\n\n요청한 작업: " + message.purpose().description()
                + "\n요청 시각: " + message.requestedAt() + "\n플랫폼: " + message.platform());
        return mail;
    }

    private void count(String outcome) {
        metrics.counter("bedrock.mail.delivery", "outcome", outcome).increment();
        monitor.record(outcome);
    }

    public record Message(UUID challengeId, String email, MailCodePurpose purpose, String platform,
            MailCodeService.DeliveryKind kind, String code, Instant requestedAt) {
        @Override public String toString() { return "MailMessage[redacted]"; }
    }
}
