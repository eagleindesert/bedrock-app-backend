package com.bedrock.app.auth.security;

import com.bedrock.app.auth.service.AuthWorkGuard;
import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;

/** HttpSession 변경이 Redis에 저장되는 바깥 경계까지 같은 브라우저의 인증 요청을 직렬화한다. */
public class AuthRequestGuardFilter extends OncePerRequestFilter {
    private final AuthWorkGuard guard;
    private final ApiErrorWriter errors;
    public AuthRequestGuardFilter(AuthWorkGuard guard, ApiErrorWriter errors) { this.guard = guard; this.errors = errors; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("POST") && request.getRequestURI().startsWith("/api/v1/auth/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var cookies = request.getCookies();
        var sessions = cookies == null ? java.util.List.<jakarta.servlet.http.Cookie>of()
                : Arrays.stream(cookies).filter(cookie -> cookie.getName().equals("BEDROCK_SESSION")).toList();
        if (sessions.isEmpty()) { chain.doFilter(request, response); return; }
        if (sessions.size() != 1 || sessions.getFirst().getValue().length() > 256) {
            errors.write(response, Be1AuthAiErrorCode.WRITE_REJECTED_VALIDATION);
            return;
        }
        AuthWorkGuard.Lease lease;
        try { lease = guard.acquire("cookie:" + sessions.getFirst().getValue()); }
        catch (BusinessException exception) { errors.write(response, exception.getErrorCode()); return; }
        catch (RuntimeException exception) { errors.write(response, Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE); return; }
        try (lease) { chain.doFilter(request, response); }
    }
}
