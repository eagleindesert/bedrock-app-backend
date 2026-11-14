package com.bedrock.app.auth.service;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.security.PasswordService;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.domain.MailCodePurpose;
import com.bedrock.app.mail.service.MailCodeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service("emailAuthentication")
public class EmailAuthentication {
    public static final String FLOW = "BE1_AUTH_FLOW";
    private final AuthStore store;
    private final MailCodeService codes;
    private final PasswordService passwords;
    private final AuthSessions sessions;
    private final AccountLifecycle lifecycle;
    private final AuthNotifications notifications;
    private final AuthWorkGuard guard;
    private final Clock clock;

    public EmailAuthentication(AuthStore store, MailCodeService codes, PasswordService passwords, AuthSessions sessions,
            AccountLifecycle lifecycle, AuthNotifications notifications, AuthWorkGuard guard, @Qualifier("be1Clock") Clock clock) {
        this.store = store;
        this.codes = codes;
        this.passwords = passwords;
        this.sessions = sessions;
        this.lifecycle = lifecycle;
        this.notifications = notifications;
        this.guard = guard;
        this.clock = clock;
    }

    public MailCodeService.Challenge signup(AuthDtos.SignupRequest input, HttpServletRequest request) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            String email = normalize(input.email());
            var existing = store.email(email).orElse(null);
            var kind = existing != null && !existing.deletionPending() ? MailCodeService.DeliveryKind.ALREADY_REGISTERED : MailCodeService.DeliveryKind.CODE;
            var flow = new Flow(UUID.randomUUID(), FlowKind.SIGNUP, email, input.name(), input.device(), false,
                    existing == null ? null : existing.id(), null, MailCodePurpose.SIGNUP, kind, null, false);
            return start(flow, request);
        });
    }

    public MailCodeService.Challenge reset(AuthDtos.PasswordResetRequest input, HttpServletRequest request) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            String email = normalize(input.email());
            var existing = store.email(email).orElse(null);
            var flow = new Flow(UUID.randomUUID(), FlowKind.RESET, email, null, input.device(), false,
                    existing == null ? null : existing.id(), null, MailCodePurpose.PASSWORD_RESET,
                    existing == null || "PURGING".equals(existing.status()) ? MailCodeService.DeliveryKind.NONE : MailCodeService.DeliveryKind.CODE,
                    null, false);
            return start(flow, request);
        });
    }

    public AuthDtos.AuthStep login(AuthDtos.LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            String email = normalize(input.email());
            var observed = store.email(email).orElse(null);
            String observedHash = observed == null ? null : observed.passwordHash();
            boolean matched = passwords.matches(input.password(), observedHash);
            var verified = store.locked(email, () -> {
                var current = store.email(email).orElse(null);
                Instant now = clock.instant();
                boolean unchanged = current != null && observed != null && current.id().equals(observed.id())
                        && Objects.equals(current.passwordHash(), observedHash);
                boolean locked = current != null && (current.failedLogins() >= 100
                        || current.lockedUntil() != null && now.isBefore(current.lockedUntil()));
                if (!matched || !unchanged || locked || !(current.active() || current.deletionPending())) {
                    int count = current == null ? 0 : current.failedLogins();
                    Instant until = current == null ? null : current.lockedUntil();
                    if (!matched && current != null) {
                        count = Math.min(100, count + 1);
                        if (count % 10 == 0) until = now.plusSeconds(Math.min(3600L, 60L << Math.min(6, count / 10 - 1)));
                    }
                    store.loginFailures(email, count, until);
                    return null;
                }
                store.loginFailures(email, 0, null);
                return current;
            });
            if (verified == null) throw new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS);
            if (verified.deletionPending()) {
                var flow = new Flow(UUID.randomUUID(), FlowKind.LOGIN_RECOVERY, email, verified.name(), input.device(), input.rememberMe(),
                        verified.id(), verified.passwordHash(), MailCodePurpose.ACCOUNT_RESTORE, MailCodeService.DeliveryKind.CODE, null, false);
                return AuthDtos.AuthStep.mail(start(flow, request));
            }
            String upgraded = passwords.upgradedHash(input.password(), verified.passwordHash());
            return store.locked(email, () -> {
                var current = store.user(verified.id()).orElseThrow(() -> new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS));
                if (!current.active() || !Objects.equals(current.passwordHash(), verified.passwordHash())) {
                    throw new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS);
                }
                if (!upgraded.equals(verified.passwordHash())) store.password(current.id(), upgraded);
                return signIn(verified.id(), input.device(), input.rememberMe(), request, response);
            });
        });
    }

    public AuthDtos.AuthStep complete(AuthDtos.MailCodeRequest input, HttpServletRequest request, HttpServletResponse response) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (!flow.verified()) {
                var verified = codes.verify(binding(request), flow.purpose(), input.code());
                if (!verified.email().equals(flow.email())) throw new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_CODE);
                flow = flow.verifiedUntil(clock.instant().plusSeconds(600));
                request.getSession().setAttribute(FLOW, flow);
            }
            var account = store.email(flow.email()).orElse(null);
            if (account != null && account.deletionPending()) {
                if (flow.originalUserId() != null && !flow.originalUserId().equals(account.id())) throw invalidCode();
                if (flow.originalUserId() == null) request.getSession().setAttribute(FLOW, flow.withOriginal(account.id()));
                return AuthDtos.AuthStep.recovery(flow.kind() == FlowKind.RESET);
            }
            if (flow.kind() == FlowKind.SIGNUP) {
                if (flow.originalUserId() != null || account != null && !account.id().equals(flow.id())) throw invalidCode();
                String hash = passwords.encodeVerifiedPassword(input.password());
                var created = lifecycle.create(flow.id(), flow.email(), flow.name(), hash, flow.device().initialTimeZone());
                return signIn(created.id(), flow.device(), flow.rememberMe(), request, response);
            }
            if (flow.kind() == FlowKind.RESET) {
                if (account == null || !account.active() || !account.id().equals(flow.originalUserId())) throw invalidCode();
                String hash = passwords.encodeVerifiedPassword(input.password());
                resetPassword(account.id(), hash, flow.device().platform());
                request.getSession().removeAttribute(FLOW);
                return AuthDtos.AuthStep.passwordReset();
            }
            throw invalidCode();
        });
    }

    public AuthDtos.AuthStep recovery(AuthDtos.RecoveryRequest input, HttpServletRequest request, HttpServletResponse response) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (!flow.verified()) throw invalidCode();
            if (!input.confirmed() || flow.kind() == FlowKind.RESET && input.action() != AuthDtos.RecoveryAction.RESTORE) {
                throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
            }
            if (flow.originalUserId() == null) throw invalidCode();
            var account = store.user(flow.originalUserId()).orElse(null);
            if (account != null && !account.deletionPending() && !"PURGING".equals(account.status())) throw invalidCode();
            String passwordHash = flow.kind() == FlowKind.LOGIN_RECOVERY ? flow.authenticatedPasswordHash()
                    : passwords.encodeVerifiedPassword(input.password());
            if (input.action() == AuthDtos.RecoveryAction.RESTART) {
                String name = input.name() == null ? flow.name() == null ? account == null ? null : account.name() : flow.name() : input.name();
                validateName(name);
                lifecycle.purge(flow.originalUserId(), true);
                UUID userId = lifecycle.create(flow.id(), flow.email(), name, passwordHash, flow.device().initialTimeZone()).id();
                return signIn(userId, flow.device(), flow.rememberMe(), request, response);
            }
            if (account == null) throw invalidCode();
            UUID userId = account.id();
            var restored = store.locked(userId, () -> {
                var current = store.user(userId).orElseThrow();
                if (!current.deletionPending() || !clock.instant().isBefore(current.purgeAfter())) throw invalidCode();
                store.restore(userId);
                if (flow.kind() == FlowKind.RESET) {
                    // 복구·비밀번호 변경·기기 종료를 같은 DB 트랜잭션으로 처리한다.
                    resetPassword(userId, passwordHash, flow.device().platform());
                    return AuthDtos.AuthStep.passwordReset();
                }
                if (passwordHash != null) store.password(userId, passwordHash);
                return signIn(userId, flow.device(), flow.rememberMe(), request, response);
            });
            if (flow.kind() == FlowKind.RESET) request.getSession().removeAttribute(FLOW);
            return restored;
        });
    }

    public MailCodeService.Challenge resend(HttpServletRequest request) {
        return guard.sessionWork(request.getSession(true).getId(), () -> {
            Flow flow = current(request);
            if (flow.verified()) throw invalidCode();
            var challenge = codes.resend(command(flow, request));
            request.getSession().setAttribute(FLOW, flow.expiresAt(challenge.expiresAt()));
            return challenge;
        });
    }

    private MailCodeService.Challenge start(Flow flow, HttpServletRequest request) {
        var challenge = codes.start(command(flow, request));
        request.getSession().setAttribute(FLOW, flow.expiresAt(challenge.expiresAt()));
        request.getSession().removeAttribute("BE1_OAUTH_FLOW");
        return challenge;
    }

    private MailCodeService.IssueCommand command(Flow flow, HttpServletRequest request) {
        return new MailCodeService.IssueCommand(flow.email(), binding(request), request.getRemoteAddr(), flow.device().platform().name(),
                flow.purpose(), flow.deliveryKind());
    }

    public AuthDtos.AuthStep signIn(UUID userId, AuthDtos.DeviceContext device, boolean remember,
            HttpServletRequest request, HttpServletResponse response) {
        var signed = sessions.signIn(userId, device, remember, request, response);
        if (signed.firstSeenDevice()) notifications.newDevice(userId, device.platform());
        return AuthDtos.AuthStep.authenticated(signed.session());
    }

    private void resetPassword(UUID userId, String hash, AuthDtos.Platform platform) {
        store.locked(userId, () -> {
            if (!store.user(userId).orElseThrow().active()) throw invalidCode();
            store.password(userId, hash);
            sessions.endAll(userId, AuthDtos.SessionEndReason.PASSWORD_RESET);
            return null;
        });
        notifications.passwordReset(userId, platform);
    }

    private Flow current(HttpServletRequest request) {
        if (!(request.getSession().getAttribute(FLOW) instanceof Flow flow) || flow.expiresAt() == null
                || !clock.instant().isBefore(flow.expiresAt())) throw invalidCode();
        return flow;
    }

    private String binding(HttpServletRequest request) { return AuthSessions.fingerprint(request.getSession().getId()); }
    private String normalize(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private BusinessException invalidCode() { return new BusinessException(Be1AuthAiErrorCode.INVALID_MAIL_CODE); }
    private void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) throw new BusinessException(Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
    }

    public enum FlowKind { SIGNUP, RESET, LOGIN_RECOVERY }
    public record Flow(UUID id, FlowKind kind, String email, String name, AuthDtos.DeviceContext device, boolean rememberMe,
            UUID originalUserId, String authenticatedPasswordHash, MailCodePurpose purpose, MailCodeService.DeliveryKind deliveryKind,
            Instant expiresAt, boolean verified) implements Serializable {
        Flow expiresAt(Instant expiry) { return new Flow(id, kind, email, name, device, rememberMe, originalUserId, authenticatedPasswordHash, purpose, deliveryKind, expiry, verified); }
        Flow verifiedUntil(Instant expiry) { return new Flow(id, kind, email, name, device, rememberMe, originalUserId, authenticatedPasswordHash, purpose, deliveryKind, expiry, true); }
        Flow withOriginal(UUID userId) { return new Flow(id, kind, email, name, device, rememberMe, userId, authenticatedPasswordHash, purpose, deliveryKind, expiresAt, verified); }
        @Override public String toString() { return "AuthFlow[id=" + id + "]"; }
    }
}
