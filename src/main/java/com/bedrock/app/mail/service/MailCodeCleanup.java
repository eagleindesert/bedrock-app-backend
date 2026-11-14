package com.bedrock.app.mail.service;

import com.bedrock.app.mail.persistence.MailCodeRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/** 종료된 가입·코드의 주소와 HMAC을 불필요하게 계속 보관하지 않는다. */
@Component("mailCodeCleanup")
public class MailCodeCleanup {
    private final MailStateTransactions transactions;
    private final MailCodeRepository codes;
    private final Clock clock;
    private final MeterRegistry metrics;

    public MailCodeCleanup(MailStateTransactions transactions, MailCodeRepository codes,
            @Qualifier("be1Clock") Clock clock, MeterRegistry metrics) {
        this.transactions = transactions;
        this.codes = codes;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000, scheduler = "be1MaintenanceScheduler")
    public void removeExpiredChallenges() {
        try {
            transactions.locked(() -> {
                codes.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
                return null;
            });
        } catch (RuntimeException exception) {
            // 스케줄러의 기본 예외 로깅으로 SQL/바인딩 값이 새지 않게 한다.
            metrics.counter("bedrock.mail.cleanup", "outcome", "failed").increment();
        }
    }
}
