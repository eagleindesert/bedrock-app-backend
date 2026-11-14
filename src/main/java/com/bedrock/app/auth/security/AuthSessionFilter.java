package com.bedrock.app.auth.security;

import com.bedrock.app.auth.service.AuthSessions;
import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.error.Be1AuthAiErrorCode;
import com.bedrock.app.common.error.BusinessException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 보안 체인 안에서만 등록한다. 서블릿 필터 빈으로도 등록해 두 번 실행하지 않는다. */
public class AuthSessionFilter extends OncePerRequestFilter {
    private final AuthSessions sessions;
    private final ApiErrorWriter errors;

    public AuthSessionFilter(AuthSessions sessions, ApiErrorWriter errors) { this.sessions = sessions; this.errors = errors; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            var principal = AuthSessions.principal();
            boolean bootstrap = request.getRequestURI().equals("/api/v1/auth/session") || request.getRequestURI().equals("/api/v1/auth/csrf");
            boolean confirmation = request.getRequestURI().equals("/api/v1/auth/not-me")
                    || request.getRequestURI().equals("/api/v1/auth/not-me.js")
                    || request.getRequestURI().equals("/api/v1/auth/not-me/inspect")
                    || request.getRequestURI().equals("/api/v1/auth/not-me/revoke");
            if (principal != null && "WEB".equals(principal.platform()) && !bootstrap && !confirmation
                    && request.getHeader("X-Bedrock-Account-Id") == null) {
                errors.write(response, Be1AuthAiErrorCode.ACCOUNT_MISMATCH);
                return;
            }
            if (sessions.inspect(request) != null && !bootstrap && !confirmation) {
                sessions.unauthorized(request, response);
                return;
            }
        } catch (BusinessException exception) {
            errors.write(response, exception.getErrorCode());
            return;
        } catch (RuntimeException exception) {
            errors.write(response, Be1AuthAiErrorCode.TEMPORARY_UNAVAILABLE);
            return;
        }
        chain.doFilter(request, response);
    }
}
