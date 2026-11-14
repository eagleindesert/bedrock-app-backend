package com.bedrock.app.mail;

import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.security.MailCodeProtector;
import com.bedrock.app.mail.service.MailRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("database")
class MailRateLimiterIntegrationTest {
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    private static final MailCodeProtector PROTECTOR = new MailCodeProtector(Base64.getEncoder().encodeToString(new byte[32]));

    @BeforeAll
    static void startRedis() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "실제 Redis Lua 검증에는 Docker가 필요합니다.");
        REDIS.start();
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        connection.start();
        redis = new StringRedisTemplate(connection);
    }

    @AfterAll
    static void stopRedis() {
        if (connection != null) connection.destroy();
        REDIS.stop();
    }

    @BeforeEach
    void clearLimits() {
        try (var client = connection.getConnection()) { client.serverCommands().flushDb(); }
    }

    @Test
    void parallelRequestsCannotExceedFivePerAddressAndKeysContainNoAddresses() throws Exception {
        var limiter = new MailRateLimiter(redis, PROTECTOR, 5000);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var tasks = IntStream.range(0, 40).mapToObj(index -> workers.submit(() ->
                    limiter.reserveRequest("same@example.com", UUID.randomUUID().toString(), "requester-" + index))).toList();
            int allowed = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) allowed++;
            assertThat(allowed).isEqualTo(5);
        }
        assertThat(redis.keys("bedrock:mail:*"))
                .allSatisfy(key -> assertThat(key).doesNotContain("same@example.com", "requester-"));
    }

    @Test
    void retriesCountAgainstAddressAndServiceQuotas() {
        var limiter = new MailRateLimiter(redis, PROTECTOR, 3);
        assertThat(limiter.reserveRequest("one@example.com", "session", "requester")).isTrue();
        assertThat(limiter.reserveRetry("one@example.com")).isTrue();
        assertThat(limiter.reserveRetry("one@example.com")).isTrue();
        assertThat(limiter.reserveRetry("one@example.com")).isFalse();
        assertThatThrownBy(() -> limiter.reserveRequest("different@example.com", "other", "other"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void dailyLimitAppliesEvenWhenHourlyLimitHasExpired() {
        var limiter = new MailRateLimiter(redis, PROTECTOR, 5000);
        String key = "bedrock:mail:{limits}:address:" + PROTECTOR.requesterFingerprint("address:day@example.com");
        long twoHoursAgo = System.currentTimeMillis() - 7_200_000;
        for (int attempt = 0; attempt < 20; attempt++) redis.opsForZSet().add(key, "past-" + attempt, twoHoursAgo);
        assertThat(limiter.reserveRequest("day@example.com", "session", "requester")).isFalse();
    }
}
