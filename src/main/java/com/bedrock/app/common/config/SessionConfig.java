package com.bedrock.app.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/** Redis 저장소와 30분 TTL은 Boot 자동설정과 spring.session 설정으로 관리한다. */
@Configuration(proxyBeanMethods = false)
public class SessionConfig {
    @Bean
    public CookieSerializer cookieSerializer(@Value("${server.servlet.session.cookie.secure:true}") boolean secure) {
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("BEDROCK_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(secure);
        serializer.setSameSite("Lax");
        // Domain을 지정하지 않아 호스트 전용 쿠키로 발급한다.
        return serializer;
    }
}
