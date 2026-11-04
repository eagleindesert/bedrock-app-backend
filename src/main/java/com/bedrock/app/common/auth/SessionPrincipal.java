package com.bedrock.app.common.auth;

import java.io.Serializable;
import java.util.UUID;

/**
 * Redis 세션에 저장되는 인증 주체.
 * 모든 컨트롤러는 @CurrentUser SessionPrincipal 로 현재 사용자를 꺼낸다.
 */
public record SessionPrincipal(
        UUID userId,
        UUID deviceId,
        String platform,     // IOS | IPADOS | WEB
        boolean rememberMe
) implements Serializable {
    public static final String SESSION_ATTRIBUTE = "SESSION_PRINCIPAL";

    public SessionPrincipal {
        java.util.Objects.requireNonNull(userId, "userId");
        java.util.Objects.requireNonNull(deviceId, "deviceId");
        if (!java.util.Set.of("IOS", "IPADOS", "WEB").contains(platform)) {
            throw new IllegalArgumentException("지원하지 않는 플랫폼입니다.");
        }
    }
}
