package com.bedrock.app.mail.config;

import com.bedrock.app.mail.api.SendGridWebhookController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;

/** 이 한 경로는 쿠키 대신 제공자 서명으로 인증한다. 다른 API의 CSRF·세션 정책은 유지한다. */
@Configuration(proxyBeanMethods = false)
public class MailWebhookSecurity {
    @Bean
    @Order(0)
    public SecurityFilterChain mailProviderSecurity(HttpSecurity http) throws Exception {
        http.securityMatcher(SendGridWebhookController.PATH)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, SendGridWebhookController.PATH).permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
