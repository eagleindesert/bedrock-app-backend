package com.bedrock.app.common;

import com.bedrock.app.common.domain.ItemTimeZones;
import com.bedrock.app.common.validation.IanaTimeZone;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 기능 명세 v0.0.6 F-12 · F-23 · F-26: 날짜는 보는 기기가 아니라 아이템의 시간대로 정한다. */
class ItemTimeZonesTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final ZoneId LOS_ANGELES = ZoneId.of("America/Los_Angeles");

    @Test
    void sameItemGivesSameDateOnDevicesInDifferentZones() {
        // 서울에서 만든 "월요일 09:00" 일정. 로스앤젤레스 기기에서는 일요일 16:00이다.
        Instant mondayNineInSeoul = Instant.parse("2026-11-16T00:00:00Z");
        ZoneId itemZone = ItemTimeZones.resolve("Asia/Seoul", LOS_ANGELES);

        assertThat(ItemTimeZones.dateOf(mondayNineInSeoul, itemZone)).isEqualTo(LocalDate.of(2026, 11, 16));
        assertThat(ItemTimeZones.wallTimeOf(mondayNineInSeoul, itemZone)).isEqualTo(LocalTime.of(9, 0));
        // 보는 기기의 시간대로 자르면 하루 앞 날짜가 되어 회차 정체성(recurrenceDate)이 갈린다.
        assertThat(ItemTimeZones.dateOf(mondayNineInSeoul, LOS_ANGELES)).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void occurrenceKeepsWallTimeAcrossDaylightSaving() {
        // 뉴욕 09:00 매주 일정은 서머타임이 끝난 뒤에도 09:00이다. UTC 순간은 한 시간 옮겨진다.
        ZoneId newYork = ZoneId.of("America/New_York");
        Instant before = ItemTimeZones.at(LocalDate.of(2026, 10, 26), LocalTime.of(9, 0), newYork);
        Instant after = ItemTimeZones.at(LocalDate.of(2026, 11, 2), LocalTime.of(9, 0), newYork);
        assertThat(before).isEqualTo(Instant.parse("2026-10-26T13:00:00Z"));
        assertThat(after).isEqualTo(Instant.parse("2026-11-02T14:00:00Z"));
    }

    @Test
    void floatingTimeFallsBackToTheGivenZone() {
        assertThat(ItemTimeZones.resolve(null, SEOUL)).isEqualTo(SEOUL);
    }

    @Test
    void acceptsOnlyIanaNames() {
        assertThat(ItemTimeZones.isIanaZoneId("Asia/Seoul")).isTrue();
        assertThat(ItemTimeZones.isIanaZoneId("+09:00")).isFalse();
        assertThat(ItemTimeZones.isIanaZoneId("KST")).isFalse();
        assertThat(ItemTimeZones.isIanaZoneId("")).isFalse();
        assertThatThrownBy(() -> ItemTimeZones.resolve("GMT+9", SEOUL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void annotationRejectsNonIanaValuesAndAllowsNull() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new Probe("Asia/Seoul"))).isEmpty();
            assertThat(validator.validate(new Probe(null))).isEmpty();
            assertThat(validator.validate(new Probe("+09:00"))).hasSize(1);
        }
    }

    private record Probe(@IanaTimeZone String timeZone) {
    }
}
