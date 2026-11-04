package com.bedrock.app.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordConfig {
    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${bedrock.security.argon2.memory-kb:19456}") int memory,
            @Value("${bedrock.security.argon2.iterations:2}") int iterations,
            @Value("${bedrock.security.argon2.parallelism:1}") int parallelism) {
        if (memory < 19456 || iterations < 2 || parallelism < 1) {
            throw new IllegalArgumentException("Argon2id 비용이 공통 보안 기준보다 낮습니다.");
        }
        return new Argon2PasswordEncoder(16, 32, parallelism, memory, iterations);
    }
}
