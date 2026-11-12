package com.bedrock.app.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(dateTimeProviderRef = "secondsDateTimeProvider")
public class PersistenceConfig {
    /** 생성 · 수정 시각도 저장 값과 같게 초 단위로 찍는다(InstantSecondsConverter). */
    @Bean
    public DateTimeProvider secondsDateTimeProvider() {
        return () -> Optional.of(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }
}
