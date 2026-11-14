package com.bedrock.app.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/** 같은 DB/HMAC 경로에 더해 짧은 계정 조회 시간차를 공개 응답에 드러내지 않는다. */
@Component("publicAuthTiming")
public class PublicAuthTiming {
    private final long minimumNanos;
    private final SecureRandom random = new SecureRandom();
    public PublicAuthTiming(@Value("${bedrock.auth.public-flow-minimum-ms:250}") long minimumMillis) {
        if (minimumMillis < 250 || minimumMillis > 2000) throw new IllegalArgumentException("공개 인증 최소 응답 시간은 250~2000ms입니다.");
        minimumNanos = minimumMillis * 1_000_000;
    }
    public <T> T execute(Supplier<T> operation) {
        long deadline = System.nanoTime() + minimumNanos + random.nextInt(51) * 1_000_000L;
        try { return operation.get(); }
        finally {
            while (!Thread.currentThread().isInterrupted()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                LockSupport.parkNanos(remaining);
            }
        }
    }
}
