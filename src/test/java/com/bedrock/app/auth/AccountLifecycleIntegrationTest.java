package com.bedrock.app.auth;

import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.security.OAuthGateway;
import com.bedrock.app.auth.service.AccountLifecycle;
import com.bedrock.app.auth.service.AuthSessions;
import com.bedrock.app.auth.service.BetaMetrics;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.common.port.WorkspaceLifecyclePort;
import com.bedrock.app.support.PostgresIntegrationSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccountLifecycleIntegrationTest extends PostgresIntegrationSupport {
    private AuthStore accounts;
    private AccountLifecycle lifecycle;
    private JdbcTemplate jdbc;
    private UserCryptoPort crypto;
    private WorkspaceLifecyclePort workspace;
    private Clock clock;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(source); jdbc.update("DELETE FROM users"); jdbc.update("DELETE FROM auth_key_cleanup");
        accounts = new AuthStore(jdbc, new DataSourceTransactionManager(source));
        crypto = mock(UserCryptoPort.class); workspace = mock(WorkspaceLifecyclePort.class); clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.now());
        when(workspace.initializeWorkspaceForNewUser(any(), anyString())).thenReturn(UUID.randomUUID());
        lifecycle = new AccountLifecycle(accounts, mock(AuthSessions.class), crypto, workspace, mock(OAuthGateway.class),
                JsonMapper.builder().build(), clock, new SimpleMeterRegistry(), mock(BetaMetrics.class));
    }

    @Test void workspaceFailureRollsBackAccountAndLeavesDurableKeyCleanup() {
        UUID userId = UUID.randomUUID();
        when(workspace.initializeWorkspaceForNewUser(any(), anyString())).thenThrow(new BusinessException(CommonErrorCode.FEATURE_NOT_READY));
        assertThatThrownBy(() -> lifecycle.create(userId, "failed@example.com", "test", "hash", "Asia/Seoul")).isInstanceOf(BusinessException.class);
        assertThat(accounts.user(userId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_key_cleanup WHERE user_id = ?", Integer.class, userId)).isEqualTo(1);
        when(clock.instant()).thenReturn(Instant.now().plusSeconds(7200));
        lifecycle.cleanup();
        verify(crypto).deleteDataKeyForUser(userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_key_cleanup", Integer.class)).isZero();
    }

    @Test void keyDeletionFailureLeavesIrreversibleStateForRetry() {
        UUID userId = lifecycle.create("purge@example.com", "test", "hash", "UTC").id();
        lifecycle.delete(userId);
        doThrow(new BusinessException(CommonErrorCode.FEATURE_NOT_READY)).when(crypto).deleteDataKeyForUser(userId);
        assertThatThrownBy(() -> lifecycle.purge(userId, true)).isInstanceOf(BusinessException.class);
        assertThat(accounts.user(userId).orElseThrow().status()).isEqualTo("PURGING");
        doNothing().when(crypto).deleteDataKeyForUser(userId);
        lifecycle.purge(userId, false);
        assertThat(accounts.user(userId)).isEmpty();
        verify(workspace, times(1)).purgeAllWorkspaceDataForUser(userId);
        verify(crypto, times(2)).deleteDataKeyForUser(userId);
    }

    @Test void ordinaryCleanupCannotPurgeAnActiveOrUnexpiredAccount() {
        UUID userId = lifecycle.create("active@example.com", "test", "hash", "UTC").id();
        assertThatThrownBy(() -> lifecycle.purge(userId, false)).isInstanceOf(BusinessException.class);
        lifecycle.delete(userId);
        assertThatThrownBy(() -> lifecycle.purge(userId, false)).isInstanceOf(BusinessException.class);
        verify(crypto, never()).deleteDataKeyForUser(userId);
    }

    @Test void usesTheWorkspaceIdReturnedByThePortForTheCommonLock() {
        UUID workspaceId = UUID.randomUUID();
        when(workspace.initializeWorkspaceForNewUser(any(), anyString())).thenReturn(workspaceId);
        UUID userId = lifecycle.create("workspace-lock@example.com", "test", "hash", "UTC").id();
        assertThat(jdbc.queryForObject("SELECT home_workspace_id FROM users WHERE id = ?", UUID.class, userId)).isEqualTo(workspaceId);
        accounts.workspaceLocked(userId, () -> {
            try (var other = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                    var statement = other.prepareStatement("SELECT pg_try_advisory_xact_lock(hashtext(?))")) {
                statement.setString(1, workspaceId.toString());
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isFalse();
                }
            } catch (java.sql.SQLException exception) { throw new IllegalStateException("워크스페이스 잠금 검증 실패", exception); }
            return null;
        });
    }

    @Test void missingWorkspaceBindingFailsBeforeSynchronizedWrites() {
        UUID userId = UUID.randomUUID();
        accounts.create(userId, "legacy@example.com", "test", null);
        assertThatThrownBy(() -> accounts.workspaceLocked(userId, () -> { throw new AssertionError("must not write"); }))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.FEATURE_NOT_READY));
    }
}
