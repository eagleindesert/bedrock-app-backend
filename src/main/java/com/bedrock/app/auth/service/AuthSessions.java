package com.bedrock.app.auth.service;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.common.auth.SessionAuthenticationService;
import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service("be1AuthSessions")
public class AuthSessions {
    public static final String PERSIST_COOKIE = "BE1_PERSIST_COOKIE";
    public static final String LAST_ACTIVITY = "BE1_LAST_USER_ACTIVITY";
    public static final String END_REASON = "BE1_SESSION_END_REASON";
    private static final Duration WEB_IDLE = Duration.ofMinutes(30);
    private static final Duration RETAINED_IDLE = Duration.ofDays(30);
    private static final String DEVICE_COOKIE = "BEDROCK_DEVICE_ID";

    private final AuthStore store;
    private final SessionAuthenticationService authentication;
    private final SessionRepository<?> repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean secureCookie;
    private final BetaMetrics beta;

    public AuthSessions(AuthStore store, SessionAuthenticationService authentication, SessionRepository<?> repository,
            StringRedisTemplate redis, ObjectMapper mapper, @Qualifier("be1Clock") Clock clock,
            @Value("${server.servlet.session.cookie.secure:true}") boolean secureCookie, BetaMetrics beta) {
        this.store = store;
        this.authentication = authentication;
        this.repository = repository;
        this.redis = redis;
        this.mapper = mapper;
        this.clock = clock;
        this.secureCookie = secureCookie;
        this.beta = beta;
    }

    public SignedIn signIn(UUID userId, AuthDtos.DeviceContext device, boolean requestedRemember,
            HttpServletRequest request, HttpServletResponse response) {
        boolean remember = device.platform() != AuthDtos.Platform.WEB || requestedRemember;
        String deviceIdentity = deviceIdentity(device, request, response);
        var result = store.locked(userId, () -> {
            var account = store.user(userId).orElseThrow();
            if (!account.active()) throw new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS);
            String deviceHash = fingerprint(deviceIdentity);
            boolean firstSeen = !store.knownDevice(userId, deviceHash);
            UUID deviceId = UUID.randomUUID();
            var principal = new SessionPrincipal(userId, deviceId, device.platform().name(), remember);
            try {
                if (remember) request.setAttribute(PERSIST_COOKIE, true);
                authentication.signIn(principal, request, response);
                var session = request.getSession();
                session.setMaxInactiveInterval((int) idle(remember).toSeconds());
                session.setAttribute(LAST_ACTIVITY, now().toEpochMilli());
                session.removeAttribute(END_REASON);
                session.removeAttribute("BE1_AUTH_FLOW");
                session.removeAttribute("BE1_OAUTH_FLOW");
                store.registerDevice(deviceId, userId, deviceHash, session.getId(), device, remember, now());
                return new SignedIn(view(request), firstSeen);
            } catch (RuntimeException exception) {
                authentication.signOut(request);
                throw exception;
            }
        });
        beta.activity(userId);
        return result;
    }

    public AuthDtos.SessionDto view(HttpServletRequest request) {
        var session = request.getSession(true);
        var principal = principal();
        if (principal == null) {
            var reason = request.getAttribute(END_REASON) instanceof AuthDtos.SessionEndReason value ? value
                    : session.getAttribute(END_REASON) instanceof AuthDtos.SessionEndReason value ? value : null;
            return new AuthDtos.SessionDto(false, null, null, false,
                    Instant.ofEpochMilli(session.getLastAccessedTime()).plus(WEB_IDLE), reason);
        }
        var account = store.user(principal.userId()).filter(AuthStore.Account::active)
                .orElseThrow(() -> new BusinessException(Be1AuthAiErrorCode.UNAUTHORIZED));
        var lastActivity = session.getAttribute(LAST_ACTIVITY) instanceof Long value ? Instant.ofEpochMilli(value) : now();
        return new AuthDtos.SessionDto(true, account.dto(), AuthDtos.Platform.valueOf(principal.platform()),
                principal.rememberMe(), lastActivity.plus(idle(principal.rememberMe())), null);
    }

    /** null이면 유효한 요청이다. 일반 웹은 사용자 조작 헤더가 있는 요청만 시계를 갱신한다. */
    public AuthDtos.SessionEndReason inspect(HttpServletRequest request) {
        var principal = principal();
        if (principal == null) {
            var reason = endedReason(request.getRequestedSessionId());
            if (reason != null) request.setAttribute(END_REASON, reason);
            return null;
        }
        var session = request.getSession(false);
        var device = store.device(principal.userId(), principal.deviceId()).orElse(null);
        var account = store.user(principal.userId()).orElse(null);
        AuthDtos.SessionEndReason reason = null;
        if (account == null || !account.active()) reason = AuthDtos.SessionEndReason.ACCOUNT_DELETED;
        else if (session == null || device == null || device.revokedAt() != null
                || !session.getId().equals(device.sessionId())) {
            reason = endedReason(request.getRequestedSessionId());
            if (reason == null) reason = AuthDtos.SessionEndReason.DEVICE_REVOKED;
        } else {
            var last = session.getAttribute(LAST_ACTIVITY) instanceof Long value ? Instant.ofEpochMilli(value) : null;
            if (last == null || !now().isBefore(last.plus(idle(principal.rememberMe())))) reason = AuthDtos.SessionEndReason.EXPIRED;
            else if (principal.rememberMe() || Collections.list(request.getHeaders("X-Bedrock-User-Activity")).equals(List.of("1"))) {
                session.setAttribute(LAST_ACTIVITY, now().toEpochMilli());
                store.touchDevice(principal.userId(), principal.deviceId(), now());
                if (Collections.list(request.getHeaders("X-Bedrock-User-Activity")).equals(List.of("1"))) beta.activity(principal.userId());
            }
        }
        if (reason != null) {
            authentication.signOut(request);
            request.getSession(true).setAttribute(END_REASON, reason);
            request.setAttribute(END_REASON, reason);
        }
        return reason;
    }

    public List<AuthDtos.DeviceDto> devices(SessionPrincipal principal) {
        Instant now = now();
        return store.devices(principal.userId()).stream()
                .filter(device -> now.isBefore(device.lastActiveAt().plus(idle(device.rememberMe()))))
                .map(device -> new AuthDtos.DeviceDto(device.id(), device.platform(), device.modelName(), device.lastActiveAt(),
                        device.id().equals(principal.deviceId()))).toList();
    }

    public void revoke(SessionPrincipal principal, UUID deviceId, boolean others) {
        store.locked(principal.userId(), () -> {
            if (others) {
                store.devices(principal.userId()).stream().filter(device -> !device.id().equals(principal.deviceId()))
                        .forEach(device -> endDevice(principal.userId(), device, AuthDtos.SessionEndReason.DEVICE_REVOKED));
            } else {
                var device = store.device(principal.userId(), deviceId)
                        .orElseThrow(() -> new BusinessException(Be1AuthAiErrorCode.RESOURCE_NOT_FOUND));
                endDevice(principal.userId(), device, AuthDtos.SessionEndReason.DEVICE_REVOKED);
            }
            return null;
        });
    }

    public void endAll(UUID userId, AuthDtos.SessionEndReason reason) {
        store.devices(userId).forEach(device -> endDevice(userId, device, reason));
    }

    private void endDevice(UUID userId, AuthStore.Device device, AuthDtos.SessionEndReason reason) {
        store.revokeDevice(userId, device.id(), now());
        if (device.sessionId() != null) {
            redis.opsForValue().set("bedrock:auth:ended:" + fingerprint(device.sessionId()), reason.name(), RETAINED_IDLE);
            repository.deleteById(device.sessionId());
        }
    }

    public void signOut(HttpServletRequest request) {
        var principal = principal();
        if (principal != null) store.revokeDevice(principal.userId(), principal.deviceId(), now());
        authentication.signOut(request);
    }

    public void unauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        var reason = request.getAttribute(END_REASON);
        response.setStatus(401);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(mapper.writeValueAsString(ApiResponse.fail("UNAUTHORIZED", "로그인이 필요합니다.",
                reason == null ? null : Map.of("sessionEndReason", reason))));
    }

    public static SessionPrincipal principal() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof SessionPrincipal principal ? principal : null;
    }

    private AuthDtos.SessionEndReason endedReason(String sessionId) {
        if (sessionId == null) return null;
        String value = redis.opsForValue().get("bedrock:auth:ended:" + fingerprint(sessionId));
        if (value == null) return null;
        try { return AuthDtos.SessionEndReason.valueOf(value); }
        catch (IllegalArgumentException exception) { return AuthDtos.SessionEndReason.UNKNOWN; }
    }

    private String deviceIdentity(AuthDtos.DeviceContext device, HttpServletRequest request, HttpServletResponse response) {
        if (device.platform() != AuthDtos.Platform.WEB) {
            return device.deviceId() == null || device.deviceId().isBlank() ? UUID.randomUUID().toString() : device.deviceId();
        }
        var cookies = request.getCookies() == null ? List.<Cookie>of() : Arrays.asList(request.getCookies());
        var matching = cookies.stream().filter(cookie -> DEVICE_COOKIE.equals(cookie.getName())).toList();
        if (matching.size() == 1 && matching.getFirst().getValue().matches("[0-9a-fA-F-]{36}")) return matching.getFirst().getValue();
        var cookie = new Cookie(DEVICE_COOKIE, UUID.randomUUID().toString());
        cookie.setHttpOnly(true);
        cookie.setSecure(secureCookie);
        cookie.setPath("/");
        cookie.setAttribute("SameSite", "Lax");
        cookie.setMaxAge(365 * 86400);
        response.addCookie(cookie);
        return cookie.getValue();
    }

    public static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("인증 정보를 처리할 수 없습니다."); }
    }

    private Duration idle(boolean remember) { return remember ? RETAINED_IDLE : WEB_IDLE; }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.SECONDS); }
    public record SignedIn(AuthDtos.SessionDto session, boolean firstSeenDevice) {}
}
