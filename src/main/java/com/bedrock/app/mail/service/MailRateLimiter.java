package com.bedrock.app.mail.service;

import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import com.bedrock.app.mail.security.MailCodeProtector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Redis TIME과 원자 Lua 연산으로 서버가 여럿이어도 이동 구간 한도를 공유한다. */
@Service("mailRateLimiter")
public class MailRateLimiter {
    private static final String PREFIX = "bedrock:mail:{limits}:";
    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>();
    static {
        SCRIPT.setLocation(new ClassPathResource("mail/request-limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redis;
    private final MailCodeProtector protector;
    private final int hourlyGlobalLimit;

    public MailRateLimiter(StringRedisTemplate redis, MailCodeProtector protector,
            @Value("${bedrock.mail.hourly-global-limit:5000}") int hourlyGlobalLimit) {
        if (hourlyGlobalLimit < 1) throw new IllegalArgumentException("메일 발송 한도는 양수여야 합니다.");
        this.redis = redis;
        this.protector = protector;
        this.hourlyGlobalLimit = hourlyGlobalLimit;
    }

    /** 주소 한도는 응답에 드러내지 않는다. 요청자/세션/전체 한도만 429로 알린다. */
    public boolean reserveRequest(String email, String sessionBinding, String requester) {
        Long result = redis.execute(SCRIPT, List.of(PREFIX + "global",
                        PREFIX + "address:" + protector.requesterFingerprint("address:" + email),
                        PREFIX + "session:" + protector.requesterFingerprint("session:" + sessionBinding),
                        PREFIX + "requester:" + protector.requesterFingerprint("requester:" + requester)),
                UUID.randomUUID().toString(), Integer.toString(hourlyGlobalLimit), "REQUEST");
        if (result == null) throw new BusinessException(Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE);
        if (result < 0) throw new BusinessException(Be1AuthAiErrorCode.MAIL_CODE_RATE_LIMITED);
        return result == 1;
    }

    /** 최초 발송은 요청 때 예약한다. 재시도도 서비스 전체 발송 상한에 포함한다. */
    public boolean reserveRetry(String email) {
        Long result = redis.execute(SCRIPT, List.of(PREFIX + "global",
                        PREFIX + "address:" + protector.requesterFingerprint("address:" + email)),
                UUID.randomUUID().toString(), Integer.toString(hourlyGlobalLimit), "RETRY");
        return Long.valueOf(1).equals(result);
    }
}
