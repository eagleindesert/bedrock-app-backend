package com.bedrock.app.auth.service;

import com.bedrock.app.auth.dto.AuthDtos;
import com.bedrock.app.auth.persistence.AuthStore;
import com.bedrock.app.auth.security.OAuthGateway;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.common.port.UserCryptoPort;
import com.bedrock.app.common.port.WorkspaceLifecyclePort;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service("accountLifecycle")
public class AccountLifecycle {
    private final AuthStore store;
    private final AuthSessions sessions;
    private final UserCryptoPort crypto;
    private final WorkspaceLifecyclePort workspace;
    private final OAuthGateway oauth;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final BetaMetrics beta;

    public AccountLifecycle(AuthStore store, AuthSessions sessions, UserCryptoPort crypto, WorkspaceLifecyclePort workspace,
            OAuthGateway oauth, ObjectMapper mapper, @Qualifier("be1Clock") Clock clock, MeterRegistry metrics, BetaMetrics beta) {
        this.store = store;
        this.sessions = sessions;
        this.crypto = crypto;
        this.workspace = workspace;
        this.oauth = oauth;
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
        this.beta = beta;
    }

    public AuthStore.Account create(String email, String name, String hash, String zone) {
        return create(UUID.randomUUID(), email, name, hash, zone);
    }

    /** 재전송에도 같은 ID를 쓴다. 키 생성 보상 기록은 별도 트랜잭션에 남긴다. */
    public AuthStore.Account create(UUID id, String email, String name, String hash, String zone) {
        store.planKeyCleanup(id);
        var result = store.keyCreationLock(id, () -> store.locked(email, () -> {
            var prior = store.user(id).orElse(null);
            if (prior != null) {
                if (!prior.active() || !prior.email().equals(email)) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
                store.finishKeyCleanup(id);
                return prior;
            }
            if (store.email(email).isPresent()) throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
            var account = store.create(id, email, name, hash);
            crypto.createDataKeyForUser(id);
            UUID workspaceId = workspace.initializeWorkspaceForNewUser(id, zone);
            store.bindWorkspace(id, workspaceId);
            store.finishKeyCleanup(id);
            return account;
        }));
        beta.signup(result.id(), result.createdAt());
        return result;
    }

    public AuthDtos.AccountDeletion delete(UUID userId) {
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        store.locked(userId, () -> {
            if (!store.user(userId).orElseThrow().active()) throw new BusinessException(Be1AuthAiErrorCode.UNAUTHORIZED);
            store.markDeleted(userId, now);
            sessions.endAll(userId, AuthDtos.SessionEndReason.ACCOUNT_DELETED);
            return null;
        });
        try { revokeApple(userId); }
        catch (RuntimeException exception) { metrics.counter("bedrock.auth.apple.revocation", "outcome", "retry").increment(); }
        return new AuthDtos.AccountDeletion(now, now.plusSeconds(30L * 86400));
    }

    public void revokeApple(UUID userId) {
        if (store.user(userId).isEmpty()) return;
        store.locked(userId, () -> {
            var account = store.user(userId).orElseThrow();
            // 후보 조회 뒤 복구가 먼저 끝났을 수 있다. 복구된 계정의 새 토큰은 해지하지 않는다.
            if (!account.deletionPending() && !"PURGING".equals(account.status())) return null;
            for (var link : store.oauthAccounts(userId)) {
                if (link.provider() != AuthDtos.Provider.APPLE || link.refreshTokenEnc() == null) continue;
                OAuthGateway.AppleGrant grant;
                try { grant = mapper.readValue(crypto.decrypt(userId, link.refreshTokenEnc()), OAuthGateway.AppleGrant.class); }
                catch (RuntimeException exception) { throw new BusinessException(Be1AuthAiErrorCode.CRYPTO_UNAVAILABLE); }
                oauth.revoke(grant);
                store.refreshToken(userId, link.id(), null);
            }
            return null;
        });
    }

    public void purge(UUID userId, boolean immediate) {
        if (store.user(userId).isEmpty()) return;
        store.workspaceLocked(userId, () -> {
            var account = store.user(userId).orElseThrow();
            if ("PURGING".equals(account.status())) return null;
            if (!"PURGING".equals(account.status()) && (!account.deletionPending()
                    || !immediate && clock.instant().isBefore(account.purgeAfter()))) {
                throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
            }
            revokeApple(userId);
            sessions.endAll(userId, AuthDtos.SessionEndReason.ACCOUNT_DELETED);
            workspace.purgeAllWorkspaceDataForUser(userId);
            store.markPurging(userId);
            return null;
        });
        // 이 지점부터 복구를 허용하지 않는다. 키 파기 실패는 PURGING 상태로 재시도한다.
        beta.erase(userId);
        crypto.deleteDataKeyForUser(userId);
        store.locked(userId, () -> { store.delete(userId); return null; });
    }

    @Scheduled(fixedDelayString = "${bedrock.auth.cleanup-delay-ms:60000}", initialDelay = 60000, scheduler = "be1MaintenanceScheduler")
    public void cleanup() {
        try {
            for (UUID id : store.pendingAppleRevocations()) runSafely(() -> revokeApple(id), "apple");
            for (UUID id : store.purgeCandidates(clock.instant())) runSafely(() -> purge(id, false), "purge");
            for (UUID id : store.staleKeyCreations(clock.instant().minusSeconds(3600))) {
                runSafely(() -> store.keyCreationLock(id, () -> {
                    if (store.user(id).isEmpty()) crypto.deleteDataKeyForUser(id);
                    store.finishKeyCleanup(id);
                    return null;
                }), "orphan_key");
            }
        } catch (RuntimeException exception) { metrics.counter("bedrock.auth.cleanup", "operation", "scan", "outcome", "failed").increment(); }
    }

    private void runSafely(Runnable task, String operation) {
        try { task.run(); metrics.counter("bedrock.auth.cleanup", "operation", operation, "outcome", "completed").increment(); }
        catch (RuntimeException exception) { metrics.counter("bedrock.auth.cleanup", "operation", operation, "outcome", "failed").increment(); }
    }
}
