package com.bedrock.app.common.filter;

import com.bedrock.app.common.auth.SessionPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
@Order(SessionRepositoryFilter.DEFAULT_ORDER + 1)
public class EndpointAccessLogFilter extends OncePerRequestFilter {
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("ACCESS_LOGGER");
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long started = System.nanoTime();
        int status = 500;
        response.setHeader("Cache-Control", "no-store");
        try {
            chain.doFilter(request, response);
            status = response.getStatus();
        } finally {
            // 라우트 템플릿만 기록하여 경로에 담긴 토큰과 ;jsessionid도 배제한다.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            String endpoint = route == null ? "[unmapped]" : route.toString();
            String method = METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER";
            ACCESS_LOG.info("method={} uri={} status={} ip={} userId={} elapsedMs={}", method,
                    endpoint, status, clientIp(request), userId(request),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    private String clientIp(HttpServletRequest request) {
        // native RemoteIpValve가 신뢰하는 프록시의 X-Forwarded-For를 먼저 반영한다.
        String remote = request.getRemoteAddr();
        if ("127.0.0.1".equals(remote) || "::1".equals(remote)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            String realIp = request.getHeader("X-Real-IP");
            String candidate = forwarded != null ? forwarded.split(",", 2)[0].trim() : realIp;
            if (isIpLiteral(candidate)) {
                return candidate;
            }
        }
        return isIpLiteral(remote) ? remote : "unknown";
    }

    private boolean isIpLiteral(String value) {
        return value != null && value.length() <= 45 && value.matches("[0-9a-fA-F:.]+");
    }

    private String userId(HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            if (session != null && session.getAttribute(SessionPrincipal.SESSION_ATTRIBUTE)
                    instanceof SessionPrincipal principal) {
                return principal.userId().toString();
            }
        } catch (IllegalStateException ignored) {
            // 로그아웃으로 이미 무효화된 세션은 익명으로 기록한다.
        }
        return "anonymous";
    }
}
