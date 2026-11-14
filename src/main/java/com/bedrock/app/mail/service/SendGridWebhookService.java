package com.bedrock.app.mail.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.persistence.MailProviderEventStore;
import com.bedrock.app.mail.security.MailCodeProtector;
import com.bedrock.app.mail.security.SendGridWebhookVerifier;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Service("sendGridWebhookService")
public class SendGridWebhookService {
    private final SendGridWebhookVerifier verifier;
    private final SendGridEvents parser;
    private final MailProviderEventStore store;
    private final MailStateTransactions transactions;
    private final MailCodeProtector protector;
    private final MailOperationalMonitor monitor;
    private final MeterRegistry metrics;
    private final Clock clock;

    public SendGridWebhookService(SendGridWebhookVerifier verifier, SendGridEvents parser, MailProviderEventStore store,
            MailStateTransactions transactions, MailCodeProtector protector, MailOperationalMonitor monitor,
            MeterRegistry metrics, @Qualifier("be1Clock") Clock clock) {
        this.verifier = verifier;
        this.parser = parser;
        this.store = store;
        this.transactions = transactions;
        this.protector = protector;
        this.monitor = monitor;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void receive(byte[] body, String timestamp, String signature) {
        verifier.verify(body, timestamp, signature);
        var events = parser.read(body);
        if (events.isEmpty()) return;
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        long[] totals;
        try {
            totals = transactions.locked(() -> {
                long delivered = 0;
                long bounced = 0;
                for (var event : events) {
                    String id = protector.requesterFingerprint("sendgrid:event:" + event.id());
                    String payload = protector.requesterFingerprint("sendgrid:payload:" + event.id() + "\0"
                            + event.email() + "\0" + event.outcome() + "\0" + event.occurredAt());
                    if (!store.receive(id, payload, now)) continue;
                    if (event.outcome() == SendGridEvents.Outcome.BOUNCED) {
                        store.suppress(event.email(), now);
                        bounced++;
                    } else delivered++;
                }
                return new long[]{delivered, bounced};
            });
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE);
        }
        // DB 커밋 뒤에만 집계한다. 제공자가 같은 이벤트를 다시 보내도 반송과 통계를 중복 반영하지 않는다.
        monitor.recordProvider(totals[0], totals[1]);
        metrics.counter("bedrock.mail.provider_delivery", "outcome", "delivered").increment(totals[0]);
        metrics.counter("bedrock.mail.provider_delivery", "outcome", "bounced").increment(totals[1]);
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 3600000, scheduler = "be1MaintenanceScheduler")
    public void cleanup() {
        try {
            transactions.locked(() -> { store.deleteReceivedBefore(clock.instant().minus(Duration.ofDays(35))); return null; });
        } catch (RuntimeException exception) {
            metrics.counter("bedrock.mail.webhook_cleanup_failure").increment();
        }
    }
}
