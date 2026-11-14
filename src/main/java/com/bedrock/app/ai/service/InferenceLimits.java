package com.bedrock.app.ai.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 재시도도 외부 호출을 실제로 시작할 때만 과금한다. Redis TIME으로 서버 간 시간을 맞춘다. */
@Component("inferenceLimits")
public class InferenceLimits {
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            local t = redis.call('TIME')
            local now = t[1] * 1000 + math.floor(t[2] / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - 86400000)
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then return 0 end
            if redis.call('ZCOUNT', KEYS[1], '(' .. (now - 60000), '+inf') >= tonumber(ARGV[2]) then return 0 end
            if redis.call('ZCARD', KEYS[2]) >= 2 then return 0 end
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('EXPIRE', KEYS[1], 86400)
            redis.call('ZADD', KEYS[2], now + 65000, ARGV[3])
            redis.call('EXPIRE', KEYS[2], 66)
            return 1
            """, Long.class);
    private final StringRedisTemplate redis;
    private final int aiDaily;
    private final int ocrDaily;
    private final Semaphore capacity = new Semaphore(32);

    public InferenceLimits(StringRedisTemplate redis, @Value("${bedrock.ai.daily-user-limit:100}") int aiDaily,
            @Value("${bedrock.ocr.daily-user-limit:20}") int ocrDaily) {
        if (aiDaily < 1 || ocrDaily < 1) throw new IllegalArgumentException("추론 한도는 양수여야 합니다.");
        this.redis = redis; this.aiDaily = aiDaily; this.ocrDaily = ocrDaily;
    }

    public <T> T execute(UUID userId, boolean ocr, Supplier<T> work) {
        var code = ocr ? Be1AuthAiErrorCode.OCR_RATE_LIMITED : Be1AuthAiErrorCode.AI_RATE_LIMITED;
        if (!capacity.tryAcquire()) throw new BusinessException(code);
        String prefix = "bedrock:inference:{" + userId + "}:" + (ocr ? "ocr" : "ai");
        String permit = UUID.randomUUID().toString();
        boolean reserved = false;
        try {
            reserved = Long.valueOf(1).equals(redis.execute(RESERVE, List.of(prefix + ":calls", prefix + ":active"),
                    Integer.toString(ocr ? ocrDaily : aiDaily), Integer.toString(ocr ? 3 : 10), permit));
            if (!reserved) throw new BusinessException(code);
            return work.get();
        } finally {
            capacity.release();
            if (reserved) {
                try { redis.opsForZSet().remove(prefix + ":active", permit); }
                catch (RuntimeException ignored) { /* 65초 임대로 복구한다. 원래 응답을 덮어쓰지 않는다. */ }
            }
        }
    }
}
