package com.bedrock.app.common.filter;

import com.bedrock.app.common.auth.SessionPrincipal;
import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.error.Be2SyncItemErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

public class WebTabAccountFilter extends OncePerRequestFilter {
    public static final String ACCOUNT_HEADER = "X-Bedrock-Account-Id";
    private final ApiErrorWriter errorWriter;

    public WebTabAccountFilter(ApiErrorWriter errorWriter) {
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var accountIds = Collections.list(request.getHeaders(ACCOUNT_HEADER));
        if (!accountIds.isEmpty() && authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof SessionPrincipal principal
                && (accountIds.size() != 1
                    || !principal.userId().toString().equalsIgnoreCase(accountIds.getFirst()))) {
            errorWriter.write(response, Be2SyncItemErrorCode.ACCOUNT_MISMATCH);
            return;
        }
        chain.doFilter(request, response);
    }
}
