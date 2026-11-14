package com.bedrock.app.auth.service;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.security.AuthFlowCipher;
import com.bedrock.app.auth.security.OAuthGateway;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.service.MailCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Service("oauthAuthentication")
public class OAuthAuthentication {
    public static final String FLOW = "BE1_OAUTH_FLOW";
    private final AuthStore store;
    private final OAuthGateway gateway;
    private final AuthFlowCipher transientCipher;
    private final UserCryptoPort crypto;
    private final MailCodeService codes;
    private final AccountLifecycle lifecycle;
    private final EmailAuthentication authentication;
    private final AuthNotifications notifications;
    private final AuthWorkGuard guard;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public OAuthAuthentication(AuthStore store, OAuthGateway gateway, AuthFlowCipher transientCipher, UserCryptoPort crypto,
            MailCodeService codes, AccountLifecycle lifecycle, EmailAuthentication authentication,
            AuthNotifications notifications, AuthWorkGuard guard, ObjectMapper mapper, @Qualifier("be1Clock") Clock clock) {
        this.store = store;
        this.gateway = gateway;
        this.transientCipher = transientCipher;
        this.crypto = crypto;
        this.codes = codes;
        this.lifecycle = lifecycle;
        this.authentication = authentication;
        this.notifications = notifications;
        this.guard = guard;
        this.mapper = mapper;
        this.clock = clock;
    }

    public AuthDtos.OAuthChallenge start(AuthDtos.Provider provider, AuthDtos.OAuthStartRequest input, HttpServletRequest request) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            String nonce = randomToken();
            String state = randomToken();
            String url = gateway.authorizationUrl(provider, input.device().platform(), input.redirectUri(), state, nonce);
            Instant expires = clock.instant().plusSeconds(600);
            var flow = new Flow(UUID.randomUUID(), provider, input.device(), input.rememberMe(), input.redirectUri(),
                    nonce, state, expires, Phase.CHALLENGE, null, null, null, null, MailCodePurpose.OAUTH_FIRST_LINK);
            request.getSession().setAttribute(FLOW, flow);
            request.getSession().removeAttribute(EmailAuthentication.FLOW);
            return new AuthDtos.OAuthChallenge(provider, url, nonce, state, expires);
        });
    }

    public AuthDtos.AuthStep complete(AuthDtos.Provider provider, AuthDtos.OAuthCompleteRequest input,
            HttpServletRequest request, HttpServletResponse response) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (flow.provider() != provider || flow.phase() != Phase.CHALLENGE) throw invalid();
            guard.consumeOAuthChallenge(flow.id());
            // 같은 nonce/state를 실패 후에도 다시 사용할 수 없다.
            request.getSession().setAttribute(FLOW, flow.phase(Phase.CONSUMED));
            if (flow.device().platform() == AuthDtos.Platform.WEB && !OAuthGateway.constant(flow.state(), input.state())
                    || provider == AuthDtos.Provider.APPLE && !OAuthGateway.constant(flow.nonce(), input.nonce())) throw invalid();
            var identity = gateway.verify(provider, flow.device().platform(), input.idToken(), flow.nonce());
            String encryptedGrant = null;
            if (provider == AuthDtos.Provider.APPLE) {
                var grant = gateway.exchange(flow.device().platform(), input.authorizationCode(), flow.redirectUri(), identity);
                encryptedGrant = transientCipher.encrypt(flow.id(), mapper.writeValueAsString(grant));
            }
            var linked = store.oauth(provider, identity.subject()).orElse(null);
            var account = linked == null ? store.email(identity.email()).orElse(null) : store.user(linked.userId()).orElseThrow(this::invalid);
            if (account != null && !account.active() && !account.deletionPending()) throw invalid();
            boolean needsMail = account != null && account.deletionPending() || linked == null && !identity.authoritativeEmail();
            flow = flow.identity(identity, encryptedGrant, account == null ? null : account.id(),
                    linked == null ? identity.email() : account.email(), needsMail ? Phase.MAIL_REQUIRED : Phase.VERIFIED,
                    account != null && account.deletionPending() ? MailCodePurpose.ACCOUNT_RESTORE : MailCodePurpose.OAUTH_FIRST_LINK);
            request.getSession().setAttribute(FLOW, flow);
            if (needsMail) {
                var challenge = codes.start(command(flow, request));
                request.getSession().setAttribute(FLOW, flow.expires(challenge.expiresAt()));
                return AuthDtos.AuthStep.mail(challenge);
            }
            return finish(flow, request, response);
        });
    }

    public boolean hasFlow(HttpServletRequest request) {
        return request.getSession(false) != null && request.getSession().getAttribute(FLOW) instanceof Flow;
    }

    public AuthDtos.AuthStep completeMail(AuthDtos.MailCodeRequest input, HttpServletRequest request, HttpServletResponse response) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (flow.phase() == Phase.MAIL_REQUIRED) {
                var verified = codes.verify(binding(request), flow.purpose(), input.code());
                if (!verified.email().equals(flow.email())) throw invalid();
                flow = flow.phase(Phase.VERIFIED).expires(clock.instant().plusSeconds(600));
                request.getSession().setAttribute(FLOW, flow);
            }
            if (flow.phase() != Phase.VERIFIED) throw invalid();
            var account = flow.originalUserId() == null ? null : store.user(flow.originalUserId()).orElseThrow(this::invalid);
            if (account != null && account.deletionPending()) return AuthDtos.AuthStep.recovery(false);
            return finish(flow, request, response);
        });
    }

    public MailCodeService.Challenge resend(HttpServletRequest request) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (flow.phase() != Phase.MAIL_REQUIRED) throw invalid();
            var challenge = codes.resend(command(flow, request));
            request.getSession().setAttribute(FLOW, flow.expires(challenge.expiresAt()));
            return challenge;
        });
    }

    public AuthDtos.AuthStep recover(AuthDtos.RecoveryRequest input, HttpServletRequest request, HttpServletResponse response) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (!input.confirmed() || flow.phase() != Phase.VERIFIED || flow.purpose() != MailCodePurpose.ACCOUNT_RESTORE || flow.originalUserId() == null) throw invalid();
            if (input.action() == AuthDtos.RecoveryAction.RESTORE) {
                UUID restoringId = flow.originalUserId();
                return store.locked(restoringId, () -> {
                    var account = store.user(restoringId).orElseThrow(this::invalid);
                    if (!account.deletionPending() || !clock.instant().isBefore(account.purgeAfter())) throw invalid();
                    store.restore(account.id());
                    // 제공자 연결·키 암호화·로그인 실패 시 복구 상태도 함께 롤백한다.
                    return finish(flow, request, response);
                });
            }
            lifecycle.purge(flow.originalUserId(), true);
            var replacement = lifecycle.create(flow.id(), flow.email(), flow.identity().name(), null, flow.device().initialTimeZone());
            var replacementFlow = flow.identity(flow.identity(), flow.grantEnc(), replacement.id(), flow.email(), Phase.VERIFIED, MailCodePurpose.OAUTH_FIRST_LINK);
            // 완료 전에는 원래 복구 흐름을 유지한다. 후속 연결이 실패해도 같은 ID로 새로 시작하기를 재시도한다.
            return finish(replacementFlow, request, response);
        });
    }

    private AuthDtos.AuthStep finish(Flow flow, HttpServletRequest request, HttpServletResponse response) {
        if (flow.phase() != Phase.VERIFIED || flow.identity() == null) throw invalid();
        return store.locked(flow.email(), () -> {
            var linked = store.oauth(flow.provider(), flow.identity().subject()).orElse(null);
            var account = linked != null ? store.user(linked.userId()).orElseThrow(this::invalid)
                    : store.email(flow.email()).orElse(null);
            if (flow.originalUserId() != null && (account == null || !flow.originalUserId().equals(account.id()))) throw invalid();
            if (account == null) account = lifecycle.create(flow.id(), flow.email(), flow.identity().name(), null, flow.device().initialTimeZone());
            if (!account.active()) throw invalid();
            String refresh = flow.grantEnc() == null ? null : crypto.encrypt(account.id(), transientCipher.decrypt(flow.id(), flow.grantEnc()));
            if (linked == null) {
                UUID oauthId = store.linkOAuth(account.id(), flow.provider(), flow.identity().subject(), flow.identity().email(), refresh);
                notifications.newProvider(account.id(), oauthId, flow.provider(), flow.device().platform());
            } else if (refresh != null) store.refreshToken(account.id(), linked.id(), refresh);
            return authentication.signIn(account.id(), flow.device(), flow.rememberMe(), request, response);
        });
    }

    private Flow current(HttpServletRequest request) {
        if (!(request.getSession().getAttribute(FLOW) instanceof Flow flow) || !clock.instant().isBefore(flow.expiresAt())) throw invalid();
        return flow;
    }
    private MailCodeService.IssueCommand command(Flow flow, HttpServletRequest request) {
        return new MailCodeService.IssueCommand(flow.email(), binding(request), request.getRemoteAddr(), flow.device().platform().name(), flow.purpose(), MailCodeService.DeliveryKind.CODE);
    }
    private String binding(HttpServletRequest request) { return AuthSessions.fingerprint(request.getSession().getId()); }
    private String randomToken() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private BusinessException invalid() { return new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS); }
    public enum Phase { CHALLENGE, CONSUMED, MAIL_REQUIRED, VERIFIED }
    public record Flow(UUID id, AuthDtos.Provider provider, AuthDtos.DeviceContext device, boolean rememberMe, String redirectUri,
            String nonce, String state, Instant expiresAt, Phase phase, OAuthGateway.Identity identity, String grantEnc,
            UUID originalUserId, String email, MailCodePurpose purpose) implements Serializable {
        Flow phase(Phase value) { return new Flow(id, provider, device, rememberMe, redirectUri, nonce, state, expiresAt, value, identity, grantEnc, originalUserId, email, purpose); }
        Flow expires(Instant value) { return new Flow(id, provider, device, rememberMe, redirectUri, nonce, state, value, phase, identity, grantEnc, originalUserId, email, purpose); }
        Flow identity(OAuthGateway.Identity value, String grant, UUID userId, String address, Phase step, MailCodePurpose mailPurpose) {
            return new Flow(id, provider, device, rememberMe, redirectUri, nonce, state, expiresAt, step, value, grant, userId, address, mailPurpose);
        }
        @Override public String toString() { return "OAuthFlow[id=" + id + "]"; }
    }
}
