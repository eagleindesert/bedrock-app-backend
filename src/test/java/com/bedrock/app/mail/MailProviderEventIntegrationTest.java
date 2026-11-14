package com.bedrock.app.mail;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.persistence.MailProviderEventStore;
import com.bedrock.app.mail.service.MailStateTransactions;
import com.bedrock.app.support.PostgresIntegrationSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

class MailProviderEventIntegrationTest extends PostgresIntegrationSupport {
    private MailProviderEventStore events;
    private MailStateTransactions transactions;
    private JdbcTemplate jdbc;
    private final Instant now = Instant.parse("2026-11-15T00:00:00Z");

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM mail_provider_events"); jdbc.update("DELETE FROM mail_bounces");
        events = new MailProviderEventStore(jdbc);
        transactions = new MailStateTransactions(jdbc, new DataSourceTransactionManager(source));
    }

    @Test void replayDoesNotRepeatSuppressionAndChangedPayloadFails() {
        transactions.locked(() -> {
            assertThat(events.receive("a".repeat(64), "b".repeat(64), now)).isTrue();
            events.suppress("invalid@example.com", now);
            return null;
        });
        assertThat(transactions.locked(() -> events.receive("a".repeat(64), "b".repeat(64), now))).isFalse();
        assertThatThrownBy(() -> transactions.locked(() -> events.receive("a".repeat(64), "c".repeat(64), now)))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT bounced FROM mail_bounces WHERE email = 'invalid@example.com'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_provider_events", Integer.class)).isEqualTo(1);
    }

    @Test void receiptAndSuppressionRollbackTogether() {
        assertThatThrownBy(() -> transactions.locked(() -> {
            events.receive("a".repeat(64), "b".repeat(64), now);
            events.suppress("invalid@example.com", now);
            throw new IllegalStateException("simulate persistence failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_provider_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_bounces", Integer.class)).isZero();
    }

    @Test void cleanupExpiresReceiptsWithoutRemovingSuppression() {
        transactions.locked(() -> { events.receive("a".repeat(64), "b".repeat(64), now); events.suppress("invalid@example.com", now); return null; });
        transactions.locked(() -> { events.deleteReceivedBefore(now.plusSeconds(35L * 86400)); return null; });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_provider_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mail_bounces", Integer.class)).isEqualTo(1);
    }
}
