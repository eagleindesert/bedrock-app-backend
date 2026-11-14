package com.bedrock.app.mail.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class MailConfiguration {
    @Bean
    public Clock be1Clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService mailRetryScheduler() {
        return Executors.newSingleThreadScheduledExecutor(task -> {
            var thread = new Thread(task, "bedrock-mail-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 외부 키·Apple 정리 작업이 메일의 5초/30초 재시도를 붙잡지 않게 한다. */
    @Bean
    public ThreadPoolTaskScheduler be1MaintenanceScheduler(MeterRegistry metrics) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("bedrock-be1-maintenance-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(failure -> metrics.counter("bedrock.maintenance.failure").increment());
        return scheduler;
    }
}
