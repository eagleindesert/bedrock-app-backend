package com.bedrock.app.mail.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 주소·본문 없는 최근 60분 반송률. 설정한 운영 메일과 경고 게이지로 알린다. */
@Component("mailOperationalMonitor")
public class MailOperationalMonitor {
    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>(
            "redis.call('HINCRBY', KEYS[1], ARGV[1], ARGV[2]); redis.call('EXPIRE', KEYS[1], 7200); return 1", Long.class);
    private static final DefaultRedisScript<List> COUNTS = new DefaultRedisScript<>("""
            local sent = 0
            local bounced = 0
            for _, key in ipairs(KEYS) do
                sent = sent + tonumber(redis.call('HGET', key, 'sent') or '0')
                bounced = bounced + tonumber(redis.call('HGET', key, 'bounced') or '0')
            end
            return {sent, bounced}
            """, List.class);
    private final StringRedisTemplate redis;
    private final MeterRegistry metrics;
    private final MailRateLimiter limits;
    private final JavaMailSender sender;
    private final Clock clock;
    private final String from;
    private final String alertAddress;
    private final double threshold;
    private final boolean providerEvents;
    private final AtomicInteger alert = new AtomicInteger();

    public MailOperationalMonitor(StringRedisTemplate redis, MeterRegistry metrics, MailRateLimiter limits, JavaMailSender sender,
            @Qualifier("be1Clock") Clock clock, @Value("${bedrock.mail.from-address}") String from,
            @Value("${bedrock.mail.alert-address:}") String alertAddress,
            @Value("${bedrock.mail.bounce-warning-ratio:0.05}") double threshold,
            @Value("${bedrock.mail.sendgrid.webhook.verification-key:}") String verificationKey) {
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold > 1) throw new IllegalArgumentException("반송 경고 비율은 0보다 크고 1 이하여야 합니다.");
        if (!alertAddress.isBlank()) {
            try { new jakarta.mail.internet.InternetAddress(alertAddress, true).validate(); }
            catch (jakarta.mail.internet.AddressException exception) { throw new IllegalArgumentException("운영 알림 주소 형식이 올바르지 않습니다."); }
        }
        this.redis = redis; this.metrics = metrics; this.limits = limits; this.sender = sender; this.clock = clock;
        this.from = from; this.alertAddress = alertAddress; this.threshold = threshold;
        this.providerEvents = !verificationKey.isBlank();
        metrics.gauge("bedrock.mail.bounce_warning", alert);
    }

    public void record(String outcome) {
        if (providerEvents && "sent".equals(outcome)) return;
        record(outcome, 1);
    }

    public void recordProvider(long delivered, long bounced) {
        if (!providerEvents) return;
        record("sent", delivered);
        record("bounced", bounced);
    }

    private void record(String outcome, long count) {
        if (count < 1 || !List.of("sent", "bounced").contains(outcome)) return;
        try { redis.execute(RECORD, List.of(key(clock.instant().getEpochSecond() / 60)), outcome, Long.toString(count)); }
        catch (RuntimeException ignored) { metrics.counter("bedrock.mail.monitor_failure").increment(); }
    }

    @Scheduled(fixedDelay = 60000, initialDelay = 60000, scheduler = "be1MaintenanceScheduler")
    public void inspect() {
        try {
            long minute = clock.instant().getEpochSecond() / 60;
            var keys = new ArrayList<String>();
            for (int i = 0; i < 60; i++) keys.add(key(minute - i));
            List<?> counts = redis.execute(COUNTS, keys);
            if (counts == null || counts.size() != 2) return;
            long sent = ((Number) counts.get(0)).longValue();
            long bounced = ((Number) counts.get(1)).longValue();
            boolean warning = sent + bounced >= 20 && (double) bounced / (sent + bounced) >= threshold;
            alert.set(warning ? 1 : 0);
            if (!warning || alertAddress.isBlank()) return;
            String alertKey = "bedrock:mail:{ops}:alert:" + minute / 60;
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(alertKey, "1", java.time.Duration.ofHours(1)))) return;
            if (!limits.reserveRetry(alertAddress)) return;
            var message = new SimpleMailMessage();
            message.setFrom(from); message.setTo(alertAddress); message.setSubject("[베드락 운영] 메일 반송률 경고");
            message.setText("최근 60분 메일 발송 결과 중 반송 비율이 경고 기준을 넘었습니다.\n성공: " + sent
                    + "\n반송: " + bounced + "\n발신 도메인 인증과 제공자 반송 상태를 확인해 주세요. 사용자 주소와 본문은 이 알림에 포함하지 않습니다.");
            try { sender.send(message); }
            catch (RuntimeException failure) { redis.delete(alertKey); metrics.counter("bedrock.mail.alert_failure").increment(); }
        } catch (RuntimeException ignored) { metrics.counter("bedrock.mail.monitor_failure").increment(); }
    }
    private String key(long minute) { return "bedrock:mail:{ops}:minute:" + minute; }
}
