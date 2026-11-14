package com.bedrock.app.auth.service;

import com.bedrock.app.mail.security.MailCodeProtector;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.persistence.BetaCreationStore;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 원문·이메일·기기 정보 없이 HMAC 가명과 일별 집계를 보관한다. 35일 보관 정책이며 기본값은 수집 꺼짐이다. */
@Component("betaMetrics")
public class BetaMetrics {
    private static final DefaultRedisScript<Long> RECORD = new DefaultRedisScript<>("""
            redis.call('SADD', KEYS[1], ARGV[1])
            redis.call('EXPIREAT', KEYS[1], ARGV[2])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RETAINED = new DefaultRedisScript<>(
            "return redis.call('SINTERCARD', 2, KEYS[1], KEYS[2])", Long.class);
    private static final DefaultRedisScript<Long> ERASE = new DefaultRedisScript<>(
            "for _, key in ipairs(KEYS) do redis.call('SREM', key, ARGV[1]) end return 1", Long.class);
    private final StringRedisTemplate redis;
    private final MailCodeProtector protector;
    private final Clock clock;
    private final boolean enabled;
    private final MeterRegistry metrics;
    private final AuthStore accounts;
    private final BetaCreationStore creations;
    private final boolean creationSourceEnabled;
    private final AtomicLong dailyActive = new AtomicLong();
    private final AtomicLong registered = new AtomicLong();
    private final AtomicLong cohortSize = new AtomicLong();
    private final AtomicLong retained = new AtomicLong();
    private final AtomicLong dailyCreated = new AtomicLong();

    public BetaMetrics(StringRedisTemplate redis, MailCodeProtector protector, @Qualifier("be1Clock") Clock clock,
            @Value("${bedrock.metrics.beta.enabled:false}") boolean enabled, MeterRegistry metrics, AuthStore accounts,
            BetaCreationStore creations, @Value("${bedrock.metrics.beta.creation-source-enabled:false}") boolean creationSourceEnabled) {
        this.redis = redis; this.protector = protector; this.clock = clock; this.enabled = enabled; this.metrics = metrics;
        this.accounts = accounts;
        this.creations = creations;
        this.creationSourceEnabled = creationSourceEnabled;
        metrics.gauge("bedrock.beta.daily_active", dailyActive);
        metrics.gauge("bedrock.beta.registered_accounts", registered);
        metrics.gauge("bedrock.beta.daily_active_ratio", this,
                value -> value.registered.get() == 0 ? 0 : (double) value.dailyActive.get() / value.registered.get());
        metrics.gauge("bedrock.beta.day30_cohort_size", cohortSize);
        metrics.gauge("bedrock.beta.day30_retained", retained);
        // 모든 생성 경로의 생산자 연동을 켜기 전에는 '0개'라는 오해를 주지 않는다.
        metrics.gauge("bedrock.beta.daily_created_items", this,
                value -> value.enabled && value.creationSourceEnabled ? value.dailyCreated.get() : Double.NaN);
        metrics.gauge("bedrock.beta.created_items_per_active_user", this, BetaMetrics::creationsPerActiveUser);
    }

    public void signup(UUID userId, Instant createdAt) { afterCommit(() -> record(userId, "cohort", LocalDate.ofInstant(createdAt, ZoneOffset.UTC))); }
    public void activity(UUID userId) { afterCommit(() -> record(userId, "active", today())); }

    /**
     * BE2가 아이템 쓰기의 같은 DB 트랜잭션에서 호출할 집계 구현이다. 공통 생산자 계약은 아직 미연동이다.
     * writeId는 원래 단건/묶음 쓰기 ID이며, 재시도는 같은 ID와 개수를 전달해야 한다.
     * createdCount에는 사용자 작업으로 새로 만든 논리 아이템만 포함한다. 복구·반복 가상 회차·기존 항목 수정은 제외한다.
     */
    public void recordCreatedItems(UUID userId, UUID writeId, int createdCount) {
        if (!enabled) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("만들기 지표는 아이템 쓰기 트랜잭션 안에서 기록해야 합니다.");
        }
        if (userId == null || writeId == null || createdCount < 1 || createdCount > 10000) {
            throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
        }
        if (accounts.user(userId).filter(AuthStore.Account::active).isEmpty()) {
            throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND);
        }
        var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        creations.record(subject(userId), protector.requesterFingerprint("beta:creation:" + writeId), createdCount, now);
        afterCommit(() -> record(userId, "active", LocalDate.ofInstant(now, ZoneOffset.UTC)));
    }

    public void erase(UUID userId) {
        if (creations.hasEvents()) creations.erase(subject(userId));
        var keys = new java.util.ArrayList<String>();
        for (int day = 0; day < 35; day++) {
            keys.add(key("cohort", today().minusDays(day)));
            keys.add(key("active", today().minusDays(day)));
        }
        // 수집을 꺼도 앞서 수집한 가명은 물리 삭제할 때 제거한다.
        if (Long.valueOf(0).equals(redis.countExistingKeys(keys))) return;
        redis.execute(ERASE, keys, subject(userId));
    }

    private void afterCommit(Runnable work) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { work.run(); }
            });
        } else work.run();
    }

    private void record(UUID userId, String kind, LocalDate day) {
        if (!enabled || day.isBefore(today().minusDays(34)) || day.isAfter(today())) return;
        try {
            redis.execute(RECORD, List.of(key(kind, day)), subject(userId),
                    Long.toString(day.plusDays(35).atStartOfDay().toEpochSecond(ZoneOffset.UTC)));
        } catch (RuntimeException exception) { metrics.counter("bedrock.beta.collection_failure").increment(); }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000, scheduler = "be1MaintenanceScheduler")
    public void aggregate() {
        if (!enabled) return;
        try {
            LocalDate day = today();
            dailyActive.set(zero(redis.opsForSet().size(key("active", day))));
            registered.set(accounts.registeredUserCount());
            cohortSize.set(zero(redis.opsForSet().size(key("cohort", day.minusDays(30)))));
            retained.set(zero(redis.execute(RETAINED, List.of(key("cohort", day.minusDays(30)), key("active", day)))));
            if (creationSourceEnabled) dailyCreated.set(creations.createdOn(day));
        } catch (RuntimeException exception) { metrics.counter("bedrock.beta.collection_failure").increment(); }
    }

    @Scheduled(fixedDelay = 3600000, initialDelay = 60000, scheduler = "be1MaintenanceScheduler")
    public void cleanupCreationEvents() {
        try { creations.expireBefore(clock.instant().minus(java.time.Duration.ofDays(35))); }
        catch (RuntimeException exception) { metrics.counter("bedrock.beta.cleanup_failure").increment(); }
    }

    private double creationsPerActiveUser() {
        if (!enabled || !creationSourceEnabled) return Double.NaN;
        if (dailyActive.get() == 0) return dailyCreated.get() == 0 ? 0 : Double.NaN;
        return (double) dailyCreated.get() / dailyActive.get();
    }
    private String subject(UUID userId) { return protector.requesterFingerprint("beta:" + userId); }
    private String key(String kind, LocalDate day) { return "bedrock:beta:{daily}:" + kind + ":" + day; }
    private LocalDate today() { return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private long zero(Long value) { return value == null ? 0 : value; }
}
