package com.bedrock.app.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void localDefaultProfileLoadsDatabaseAndRedisCredentials() throws IOException {
        Path settings = writeSettings("""
                DB_HOST=database.local
                DB_PORT=5544
                POSTGRES_DB=workspace
                DB_NAME=legacy_name
                DB_USERNAME=local_user
                DB_PASSWORD=local-password
                REDIS_PASSWORD=redis-password
                """);

        runner(settings).withPropertyValues("spring.profiles.default=local").run(context -> {
            assertThat(context).hasNotFailed();
            var environment = context.getEnvironment();
            assertThat(environment.getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://database.local:5544/workspace");
            assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("local_user");
            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("local-password");
            assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("redis-password");
            assertThat(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class))
                    .isFalse();
        });
    }

    @Test
    void legacyDatabaseNamesAndExplicitUrlRemainSupported() throws IOException {
        Path settings = writeSettings("""
                DB_NAME=legacy_db
                DB_USER=legacy_user
                DB_PASSWORD=legacy-password
                """);
        var local = runner(settings).withPropertyValues("spring.profiles.active=local");

        local.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://localhost:5432/legacy_db");
            assertThat(context.getEnvironment().getProperty("spring.datasource.username"))
                    .isEqualTo("legacy_user");
        });
        local.withPropertyValues("DB_URL=jdbc:postgresql://override:5433/explicit_db").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo("jdbc:postgresql://override:5433/explicit_db");
        });
    }

    @Test
    void environmentOverridesImportedCredentials() throws IOException {
        Path settings = writeSettings("DB_PASSWORD=file-password\n");

        runner(settings).withPropertyValues("spring.profiles.active=local")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("testEnvironment", Map.of(
                                "DB_PASSWORD", "environment-password",
                                "COOKIE_SECURE", "true"))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.datasource.password"))
                            .isEqualTo("environment-password");
                    assertThat(context.getEnvironment()
                            .getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
                });
    }

    @Test
    void missingLocalFileUsesComposeDefaults() {
        runner(directory.resolve("missing.properties"))
                .withPropertyValues("spring.profiles.default=local").run(context -> {
                    assertThat(context).hasNotFailed();
                    var environment = context.getEnvironment();
                    assertThat(environment.getProperty("spring.datasource.url"))
                            .isEqualTo("jdbc:postgresql://localhost:5432/bedrock");
                    assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("bedrock");
                    assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("bedrock");
                    assertThat(environment.getProperty("spring.data.redis.password")).isEqualTo("bedrock");
                });
    }

    @Test
    void deploymentDoesNotImportLocalFileOrDisableSecureCookies() throws IOException {
        Path settings = writeSettings("""
                DB_PASSWORD=local-only-password
                COOKIE_SECURE=false
                """);

        runner(settings).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("DB_PASSWORD")).isNull();
            assertThat(context.getEnvironment()
                    .getProperty("server.servlet.session.cookie.secure", Boolean.class)).isTrue();
        });
    }

    private Path writeSettings(String contents) throws IOException {
        return Files.writeString(directory.resolve("local.properties"), contents);
    }

    private ApplicationContextRunner runner(Path settings) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    // 개발자의 실제 환경변수와 .env에 접근하지 않고 설정 해석만 검증한다.
                    var sources = context.getEnvironment().getPropertySources();
                    sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                })
                .withPropertyValues(
                        "spring.config.location=classpath:/application.yml",
                        "BEDROCK_ENV_FILE=" + settings)
                .withInitializer(new ConfigDataApplicationContextInitializer());
    }
}
