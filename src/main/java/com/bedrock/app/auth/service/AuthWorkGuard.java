package com.bedrock.app.auth.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/** 서로 다른 서버/탭이 같은 인증 작업을 동시에 소비하지 못하게 한다. */
@Component("authWorkGuard")
public class AuthWorkGuard {
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end return 0", Long.class);
    private final StringRedisTemplate redis;

    public AuthWorkGuard(StringRedisTemplate redis) { this.redis = redis; }

    public <T> T sessionWork(String sessionId, Supplier<T> work) {
        try (Lease ignored = acquire(sessionId)) { return work.get(); }
    }

    public Lease acquire(String identity) {
        String key = "bedrock:auth:work:" + AuthSessions.fingerprint(identity);
        String owner = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, owner, Duration.ofMinutes(30)))) {
            throw new BusinessException(Be1AuthAiErrorCode.RESOURCE_VERSION_CHANGED);
        }
        return new Lease(key, owner);
    }

    public void consumeOAuthChallenge(UUID flowId) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("bedrock:auth:consumed-oauth:" + flowId, "1", Duration.ofMinutes(20)))) {
            throw new BusinessException(Be1AuthAiErrorCode.INVALID_CREDENTIALS);
        }
    }

    public final class Lease implements AutoCloseable {
        private final String key;
        private final String owner;
        private Lease(String key, String owner) { this.key = key; this.owner = owner; }
        @Override public void close() {
            try { redis.execute(RELEASE, List.of(key), owner); }
            catch (RuntimeException ignored) { /* Redis 임대 만료가 복구한다. 완료된 인증 응답은 보존한다. */ }
        }
    }
}
