package com.bedrock.app.common;

import com.bedrock.app.common.config.JacksonConfig;
import com.bedrock.app.common.domain.InstantSecondsConverter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** API와 저장의 Instant는 초 단위 한 꼴이다. */
class InstantPrecisionTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(JacksonConfig.class);

    @Test
    void jsonReadsMillisecondsAndOffsetsThenWritesSeconds() {
        runner.run(context -> {
            var json = context.getBean(JsonMapper.class);
            assertThat(json.readValue("\"2026-11-12T08:30:15.987Z\"", Instant.class))
                    .isEqualTo(Instant.parse("2026-11-12T08:30:15Z"));
            assertThat(json.readValue("\"2026-11-12T17:30:15.5+09:00\"", Instant.class))
                    .isEqualTo(Instant.parse("2026-11-12T08:30:15Z"));
            assertThat(json.writeValueAsString(Instant.parse("2026-11-12T08:30:15.987654Z")))
                    .isEqualTo("\"2026-11-12T08:30:15Z\"");
            assertThat(json.writeValueAsString(new Probe(Instant.parse("2026-11-12T00:00:00Z"))))
                    .isEqualTo("{\"at\":\"2026-11-12T00:00:00Z\"}");
            assertThatThrownBy(() -> json.readValue("\"2026-11-12 08:30\"", Instant.class))
                    .hasMessageNotContaining("08:30");
            assertThatThrownBy(() -> json.readValue("1762936215", Instant.class));
        });
    }

    @Test
    void storageDropsFractionalSeconds() {
        var converter = new InstantSecondsConverter();
        assertThat(converter.convertToDatabaseColumn(Instant.parse("2026-11-12T23:59:59.999Z")))
                .isEqualTo(Instant.parse("2026-11-12T23:59:59Z"));
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    private record Probe(Instant at) {
    }
}
