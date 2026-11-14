package com.bedrock.app.auth;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.security.AuthFlowCipher;
import com.bedrock.app.auth.security.OAuthGateway;
import com.bedrock.app.auth.security.PasswordService;
import com.bedrock.app.auth.service.*;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.common.port.WorkspaceLifecyclePort;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.service.MailCodeService;
import com.bedrock.app.support.PostgresIntegrationSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 외부 연결 실패를 주입하되 복구 상태와 제공자 연결의 롤백은 실제 PostgreSQL로 검사한다. */
class AuthRecoveryIntegrationTest extends PostgresIntegrationSupport {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private static final AuthDtos.DeviceContext DEVICE = new AuthDtos.DeviceContext(AuthDtos.Platform.WEB, "test", "Asia/Seoul", null);
    private final UUID originalUser = UUID.randomUUID();
    private AuthStore accounts;
    private AccountLifecycle lifecycle;
    private EmailAuthentication email;
    private OAuthAuthentication oauth;
    private AuthSessions sessions;
    private AuthNotifications notifications;
    private UserCryptoPort crypto;
    private WorkspaceLifecyclePort workspace;
    private OAuthGateway gateway;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).outOfOrder(true).load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM auth_key_cleanup");
        accounts = new AuthStore(jdbc, new DataSourceTransactionManager(source));
        accounts.create(originalUser, "restore@example.com", "test", "old-hash");
        accounts.bindWorkspace(originalUser, UUID.randomUUID());
        accounts.markDeleted(originalUser, NOW.minusSeconds(60));
        sessions = mock(AuthSessions.class);
        notifications = mock(AuthNotifications.class);
        crypto = mock(UserCryptoPort.class);
        workspace = mock(WorkspaceLifecyclePort.class);
        gateway = mock(OAuthGateway.class);
        when(workspace.initializeWorkspaceForNewUser(any(), anyString())).thenReturn(UUID.randomUUID());
        when(sessions.signIn(any(), any(), anyBoolean(), any(), any())).thenAnswer(invocation -> {
            var account = accounts.user(invocation.getArgument(0)).orElseThrow();
            return new AuthSessions.SignedIn(new AuthDtos.SessionDto(true, account.dto(), AuthDtos.Platform.WEB,
                    false, NOW.plusSeconds(1800), null), false);
        });
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        var mapper = JsonMapper.builder().build();
        var guard = new AuthWorkGuard(null) {
            @Override public <T> T sessionWork(String sessionId, Supplier<T> work) { return work.get(); }
        };
        lifecycle = new AccountLifecycle(accounts, sessions, crypto, workspace, gateway, mapper, clock,
                new SimpleMeterRegistry(), mock(BetaMetrics.class));
        var passwords = mock(PasswordService.class);
        when(passwords.encodeVerifiedPassword(anyString())).thenReturn("new-hash");
        var codes = mock(MailCodeService.class);
        email = new EmailAuthentication(accounts, codes, passwords, sessions, lifecycle, notifications, guard, clock);
        oauth = new OAuthAuthentication(accounts, gateway, mock(AuthFlowCipher.class), crypto, codes, lifecycle,
                email, notifications, guard, mapper, clock);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @Test void oauthConnectionFailureRollsBackRestorationAndCanBeRetried() {
        var flow = oauthFlow();
        failProviderNotification();
        assertThatThrownBy(() -> oauth.recover(action(AuthDtos.RecoveryAction.RESTORE), request, response)).isInstanceOf(BusinessException.class);
        assertThat(accounts.user(originalUser).orElseThrow().deletionPending()).isTrue();
        assertThat(accounts.oauthAccounts(originalUser)).isEmpty();
        assertThat(request.getSession().getAttribute(OAuthAuthentication.FLOW)).isEqualTo(flow);
        doNothing().when(notifications).newProvider(any(), any(), any(), any());
        assertThat(oauth.recover(action(AuthDtos.RecoveryAction.RESTORE), request, response).nextStep()).isEqualTo(AuthDtos.NextStep.AUTHENTICATED);
        assertThat(accounts.user(originalUser).orElseThrow().active()).isTrue();
        assertThat(accounts.oauthAccounts(originalUser)).hasSize(1);
    }

    @Test void restartKeepsOriginalProofUntilTheReplacementConnectionSucceeds() {
        var flow = oauthFlow();
        failProviderNotification();
        assertThatThrownBy(() -> oauth.recover(action(AuthDtos.RecoveryAction.RESTART), request, response)).isInstanceOf(BusinessException.class);
        assertThat(accounts.user(originalUser)).isEmpty();
        assertThat(accounts.user(flow.id()).orElseThrow().active()).isTrue();
        assertThat(request.getSession().getAttribute(OAuthAuthentication.FLOW)).isEqualTo(flow);
        doNothing().when(notifications).newProvider(any(), any(), any(), any());
        var result = oauth.recover(action(AuthDtos.RecoveryAction.RESTART), request, response);
        assertThat(result.session().user().id()).isEqualTo(flow.id());
        verify(workspace, times(1)).initializeWorkspaceForNewUser(eq(flow.id()), anyString());
        verify(crypto, times(1)).deleteDataKeyForUser(originalUser);
    }

    @Test void passwordResetFailureDoesNotLeaveARestoredAccountOrChangedPassword() {
        var flow = emailFlow(EmailAuthentication.FlowKind.RESET);
        doThrow(unavailable()).when(sessions).endAll(originalUser, AuthDtos.SessionEndReason.PASSWORD_RESET);
        assertThatThrownBy(() -> email.recovery(action(AuthDtos.RecoveryAction.RESTORE), request, response)).isInstanceOf(BusinessException.class);
        var retained = accounts.user(originalUser).orElseThrow();
        assertThat(retained.deletionPending()).isTrue();
        assertThat(retained.passwordHash()).isEqualTo("old-hash");
        assertThat(request.getSession().getAttribute(EmailAuthentication.FLOW)).isEqualTo(flow);
        doNothing().when(sessions).endAll(originalUser, AuthDtos.SessionEndReason.PASSWORD_RESET);
        assertThat(email.recovery(action(AuthDtos.RecoveryAction.RESTORE), request, response).nextStep())
                .isEqualTo(AuthDtos.NextStep.PASSWORD_RESET_COMPLETED);
        assertThat(accounts.user(originalUser).orElseThrow().passwordHash()).isEqualTo("new-hash");
        assertThat(request.getSession().getAttribute(EmailAuthentication.FLOW)).isNull();
        verify(sessions, never()).signIn(any(), any(), anyBoolean(), any(), any());
    }

    @Test void loginFailureRollsBackEmailRestoration() {
        emailFlow(EmailAuthentication.FlowKind.LOGIN_RECOVERY);
        doThrow(unavailable()).when(sessions).signIn(any(), any(), anyBoolean(), any(), any());
        assertThatThrownBy(() -> email.recovery(action(AuthDtos.RecoveryAction.RESTORE), request, response)).isInstanceOf(BusinessException.class);
        assertThat(accounts.user(originalUser).orElseThrow().deletionPending()).isTrue();
    }

    @Test void staleAppleCleanupDoesNotRevokeARecoveredAccountsNewGrant() {
        accounts.linkOAuth(originalUser, AuthDtos.Provider.APPLE, "apple-subject", "restore@example.com", "new-encrypted-grant");
        assertThat(accounts.pendingAppleRevocations()).contains(originalUser);
        accounts.locked(originalUser, () -> { accounts.restore(originalUser); return null; });
        lifecycle.revokeApple(originalUser);
        verifyNoInteractions(gateway, crypto);
        assertThat(accounts.oauthAccounts(originalUser).getFirst().refreshTokenEnc()).isEqualTo("new-encrypted-grant");
    }

    private OAuthAuthentication.Flow oauthFlow() {
        var flow = new OAuthAuthentication.Flow(UUID.randomUUID(), AuthDtos.Provider.GOOGLE, DEVICE, false, null,
                "nonce", "state", NOW.plusSeconds(600), OAuthAuthentication.Phase.VERIFIED,
                new OAuthGateway.Identity("google-subject", "restore@example.com", "test", true), null,
                originalUser, "restore@example.com", MailCodePurpose.ACCOUNT_RESTORE);
        request.getSession().setAttribute(OAuthAuthentication.FLOW, flow);
        return flow;
    }

    private EmailAuthentication.Flow emailFlow(EmailAuthentication.FlowKind kind) {
        var flow = new EmailAuthentication.Flow(UUID.randomUUID(), kind, "restore@example.com", "test", DEVICE, false,
                originalUser, "old-hash", kind == EmailAuthentication.FlowKind.RESET ? MailCodePurpose.PASSWORD_RESET : MailCodePurpose.ACCOUNT_RESTORE,
                MailCodeService.DeliveryKind.CODE, NOW.plusSeconds(600), true);
        request.getSession().setAttribute(EmailAuthentication.FLOW, flow);
        return flow;
    }

    private AuthDtos.RecoveryRequest action(AuthDtos.RecoveryAction action) { return new AuthDtos.RecoveryRequest(action, true, null, "new-password"); }
    private void failProviderNotification() { doThrow(unavailable()).when(notifications).newProvider(any(), any(), any(), any()); }
    private BusinessException unavailable() { return new BusinessException(CommonErrorCode.FEATURE_NOT_READY); }
}
