package com.bedrock.app.common.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 모든 엔티티의 Instant를 초 단위로 잘라 저장한다(API 명세의 Instant 꼴, 기능 명세 4.2).
 * 사람이 고르는 시각(시작 · 끝 · 마감 · 완료 시각)에 초 아래는 뜻이 없고, 기기 · 서버가 한 꼴로 비교하게 하려는 것이다.
 * DB 기본값 NOW()로 채운 열은 여기를 거치지 않으므로, 응답은 JacksonConfig가 초 단위로 쓴다.
 */
@Converter(autoApply = true)
public class InstantSecondsConverter implements AttributeConverter<Instant, Instant> {
    @Override
    public Instant convertToDatabaseColumn(Instant attribute) {
        return attribute == null ? null : attribute.truncatedTo(ChronoUnit.SECONDS);
    }

    @Override
    public Instant convertToEntityAttribute(Instant dbData) {
        return dbData == null ? null : dbData.truncatedTo(ChronoUnit.SECONDS);
    }
}
