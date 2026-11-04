package com.bedrock.app.common.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/** BE 1의 실제 자격 증명 검증이 성공한 뒤 호출하는 공통 세션 수명 관리. */
@Service("sessionAuthenticationService")
public class SessionAuthenticationService {
    private final CsrfTokenRepository csrfTokens;

    public SessionAuthenticationService(CsrfTokenRepository csrfTokens) {
        this.csrfTokens = csrfTokens;
    }

    public void signIn(SessionPrincipal principal, HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(true);
        request.changeSessionId();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        session.setAttribute(SessionPrincipal.SESSION_ATTRIBUTE, principal);
        csrfTokens.saveToken(null, request, response);
    }

    public void signOut(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
