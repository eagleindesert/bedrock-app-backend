package com.bedrock.app.auth.config;

import com.bedrock.app.auth.security.AuthSessionFilter;
import com.bedrock.app.auth.security.Be1RequestSizeFilter;
import com.bedrock.app.auth.security.AuthRequestGuardFilter;
import com.bedrock.app.auth.service.AuthWorkGuard;
import com.bedrock.app.auth.service.AuthSessions;
import com.bedrock.app.common.error.ApiErrorWriter;
import com.bedrock.app.common.error.CommonErrorCode;
import com.bedrock.app.common.filter.WebTabAccountFilter;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

/** 고정된 common 설정을 유지하면서 BE1 소관의 v1 인증·세션 정책을 적용한다. */
@Configuration(proxyBeanMethods = false)
public class AuthApiSecurity {
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<AuthRequestGuardFilter> authRequestGuardRegistration(
            AuthWorkGuard guard, ApiErrorWriter errors) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new AuthRequestGuardFilter(guard, errors));
        registration.setName("be1AuthRequestGuard");
        registration.setOrder(org.springframework.session.web.http.SessionRepositoryFilter.DEFAULT_ORDER - 1);
        registration.addUrlPatterns("/api/v1/auth/*");
        return registration;
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authV1Security(HttpSecurity http, ApiErrorWriter errors,
            CsrfTokenRepository csrfTokens, @Qualifier("corsConfigurationSource") CorsConfigurationSource corsConfigurationSource, AuthSessions sessions) throws Exception {
        http.securityMatcher("/api/v1/**")
                .cors(cors -> cors.configurationSource(request -> {
                    CorsConfiguration original = corsConfigurationSource.getCorsConfiguration(request);
                    if (original == null) return null;
                    var scoped = new CorsConfiguration(original);
                    scoped.addAllowedHeader("X-Bedrock-User-Activity");
                    return scoped;
                }))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler() {
                    @Override public String resolveCsrfTokenValue(jakarta.servlet.http.HttpServletRequest request,
                            org.springframework.security.web.csrf.CsrfToken token) {
                        // getParameter가 Tomcat multipart 파서를 호출하여 임시 파일을 만들지 않게 한다.
                        return request.getRequestURI().equals("/api/v1/ocr/timetables") ? request.getHeader(token.getHeaderName())
                                : super.resolveCsrfTokenValue(request, token);
                    }
                }))
                .requestCache(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable).logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/session", "/api/v1/auth/csrf", "/api/v1/auth/not-me", "/api/v1/auth/not-me.js").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/password-reset",
                                "/api/v1/auth/mail-codes/complete", "/api/v1/auth/mail-codes/resend", "/api/v1/auth/account-recovery",
                                "/api/v1/auth/oauth/GOOGLE/start", "/api/v1/auth/oauth/GOOGLE/complete",
                                "/api/v1/auth/oauth/APPLE/start", "/api/v1/auth/oauth/APPLE/complete",
                                "/api/v1/auth/not-me/inspect", "/api/v1/auth/not-me/revoke").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> sessions.unauthorized(request, response))
                        .accessDeniedHandler((request, response, exception) -> errors.write(response, CommonErrorCode.ACCESS_DENIED)))
                .addFilterBefore(new Be1RequestSizeFilter(errors), org.springframework.security.web.csrf.CsrfFilter.class)
                .addFilterAfter(new WebTabAccountFilter(errors), SecurityContextHolderFilter.class)
                .addFilterAfter(new AuthSessionFilter(sessions, errors), WebTabAccountFilter.class);
        return http.build();
    }

    @Bean
    public static BeanPostProcessor persistentSessionCookies() {
        return new BeanPostProcessor() {
            @Override public Object postProcessAfterInitialization(Object bean, String name) {
                if (bean instanceof DefaultCookieSerializer cookies) cookies.setRememberMeRequestAttribute(AuthSessions.PERSIST_COOKIE);
                return bean;
            }
        };
    }
}
