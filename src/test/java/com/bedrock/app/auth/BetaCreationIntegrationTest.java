package com.bedrock.app.auth;

import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.persistence.BetaCreationStore;
import com.bedrock.app.auth.service.BetaMetrics;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.security.MailCodeProtector;
import com.bedrock.app.support.PostgresIntegrationSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BetaCreationIntegrationTest extends PostgresIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private final UUID user = UUID.randomUUID();
    private AuthStore accounts;
    private BetaCreationStore creations;
    private BetaMetrics beta;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private StringRedisTemplate redis;
    private SimpleMeterRegistry registry;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM auth_beta_creations"); jdbc.update("DELETE FROM users");
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        accounts = new AuthStore(jdbc, manager);
        accounts.create(user, "metrics@example.com", "test", null);
        creations = new BetaCreationStore(jdbc);
        redis = mock(StringRedisTemplate.class);
        registry = new SimpleMeterRegistry();
        beta = metrics(true, false);
    }

    @Test void recordsOnlyCommittedCountsAndDeduplicatesWriteIds() {
        UUID writeId = UUID.randomUUID();
        transaction.executeWithoutResult(status -> beta.recordCreatedItems(user, writeId, 3));
        transaction.executeWithoutResult(status -> beta.recordCreatedItems(user, writeId, 3));
        assertThat(creations.createdOn(LocalDate.parse("2026-11-15"))).isEqualTo(3);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> beta.recordCreatedItems(user, writeId, 4)))
                .isInstanceOf(BusinessException.class);
        var row = jdbc.queryForMap("SELECT subject_hmac, write_hmac, created_count FROM auth_beta_creations");
        assertThat(row.get("subject_hmac").toString()).matches("[0-9a-f]{64}").doesNotContain(user.toString());
        assertThat(row.get("write_hmac").toString()).doesNotContain(writeId.toString());
    }

    @Test void rollbackErasesTheEventAndDoesNotPublishActivity() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            beta.recordCreatedItems(user, UUID.randomUUID(), 2);
            throw new IllegalStateException("simulate item write rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(creations.hasEvents()).isFalse();
        verifyNoInteractions(redis);
    }

    @Test void rejectsUseOutsideItemTransactionAndInvalidCounts() {
        assertThatThrownBy(() -> beta.recordCreatedItems(user, UUID.randomUUID(), 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> beta.recordCreatedItems(user, UUID.randomUUID(), 0)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> beta.recordCreatedItems(UUID.randomUUID(), UUID.randomUUID(), 1)))
                .isInstanceOf(BusinessException.class);
        assertThat(creations.hasEvents()).isFalse();
    }

    @Test void disabledCollectionDoesNotWriteAndUnconnectedSourceIsUnknown() {
        var disabled = metrics(false, false);
        disabled.recordCreatedItems(user, UUID.randomUUID(), 1);
        assertThat(creations.hasEvents()).isFalse();
        assertThat(registry.get("bedrock.beta.daily_created_items").gauge().value()).isNaN();
        assertThat(registry.get("bedrock.beta.created_items_per_active_user").gauge().value()).isNaN();
    }

    @Test void deletionStillErasesPreviouslyCollectedEventsWhenDisabled() {
        transaction.executeWithoutResult(status -> beta.recordCreatedItems(user, UUID.randomUUID(), 2));
        metrics(false, false).erase(user);
        assertThat(creations.hasEvents()).isFalse();
    }

    @Test void expiresOldCountsAndKeepsCurrentDay() {
        creations.record("a".repeat(64), "b".repeat(64), 7, NOW.minusSeconds(36L * 86400));
        transaction.executeWithoutResult(status -> beta.recordCreatedItems(user, UUID.randomUUID(), 2));
        beta.cleanupCreationEvents();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_beta_creations", Integer.class)).isEqualTo(1);
        assertThat(creations.createdOn(LocalDate.parse("2026-11-15"))).isEqualTo(2);
    }

    private BetaMetrics metrics(boolean enabled, boolean linked) {
        registry = new SimpleMeterRegistry();
        return new BetaMetrics(redis, new MailCodeProtector("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
                Clock.fixed(NOW, ZoneOffset.UTC), enabled, registry, accounts, creations, linked);
    }
}
