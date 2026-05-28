package com.bedrock.app.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration
@EnableRedisHttpSession
public class RedisConfig {

    @Bean
    public CookieSerializer cookieSerializer(
            @org.springframework.beans.factory.annotation.Value("${app.cookie.same-site:Lax}") String sameSite,
            @org.springframework.beans.factory.annotation.Value("${app.cookie.secure:false}") boolean secure) {
        
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setCookiePath("/");
        serializer.setSameSite(sameSite); 
        serializer.setUseSecureCookie(secure); 
        serializer.setUseHttpOnlyCookie(true);
        return serializer;
    }
}
