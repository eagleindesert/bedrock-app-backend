package com.bedrock.app.common.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdScalarDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

@Configuration(proxyBeanMethods = false)
public class JacksonConfig {
    @Bean
    public JsonMapperBuilderCustomizer jsonMapperCustomizer() {
        return builder -> builder.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .addModule(instantSecondsModule());
    }

    /**
     * API의 Instant는 UTC 초 단위 {@code YYYY-MM-DDTHH:mm:ssZ} 한 꼴이다(기능 명세 4.2: 문자열 비교가 시각 비교와 같게).
     * 받을 때는 밀리초 · 오프셋이 붙어 와도 읽고 초 아래를 버린다. 보낼 때는 늘 초 단위로 쓴다.
     * 저장할 때 자르는 것은 {@link com.bedrock.app.common.domain.InstantSecondsConverter}가 맡는다.
     */
    static SimpleModule instantSecondsModule() {
        return new SimpleModule("BedrockInstantSeconds")
                .addSerializer(Instant.class, new InstantSecondsSerializer())
                .addDeserializer(Instant.class, new InstantSecondsDeserializer());
    }

    static final class InstantSecondsSerializer extends StdSerializer<Instant> {
        InstantSecondsSerializer() {
            super(Instant.class);
        }

        @Override
        public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
            generator.writeString(DateTimeFormatter.ISO_INSTANT.format(value.truncatedTo(ChronoUnit.SECONDS)));
        }
    }

    static final class InstantSecondsDeserializer extends StdScalarDeserializer<Instant> {
        InstantSecondsDeserializer() {
            super(Instant.class);
        }

        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            }
            String text = parser.getValueAsString();
            try {
                return OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        .toInstant().truncatedTo(ChronoUnit.SECONDS);
            } catch (DateTimeParseException rejected) {
                // 받은 값은 메시지에 싣지 않는다.
                return (Instant) context.handleWeirdStringValue(Instant.class, "", "ISO 8601 시각이 아닙니다.");
            }
        }
    }
}
