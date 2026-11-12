package com.bedrock.app.common.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/**
 * 기능 명세 v0.0.6 F-12 · F-23 · F-26 · 4.2: 아이템의 시각과 날짜를 아이템의 시간대로 정한다.
 *
 * <p>서버가 날짜를 내야 하는 계산(반복 나누기 · 예외와 지운 회차 옮기기 · 적용 시작일 뒤 회차 판정 ·
 * 짝 없는 예외 판정 · 날짜 없는 아이템의 만든 날)은 보는 기기나 서버의 시간대가 아니라
 * {@code items.time_zone}을 써야 한다. 그래야 시간대가 다른 기기와 서버가 같은 회차를 같은 날짜로 부른다.
 * 종일 값 · 날짜만 고른 마감 · 시간표 시간대는 시간대 없는 날짜와 시각이라 여기를 거치지 않는다.
 */
public final class ItemTimeZones {
    private static final Set<String> IANA_ZONE_IDS = ZoneId.getAvailableZoneIds();

    private ItemTimeZones() {
    }

    /** IANA 시간대 이름인지 본다. {@code +09:00} 같은 고정 오프셋과 빈 문자열은 받지 않는다. */
    public static boolean isIanaZoneId(String value) {
        return value != null && IANA_ZONE_IDS.contains(value);
    }

    /**
     * 계산에 쓸 시간대. 아이템의 시간대가 비면 떠 있는 시각이라 보는 쪽의 시간대로 읽는다(4.2).
     * MVP의 앱은 늘 값을 넣으므로, 서버 계산에서 null을 만나면 계정의 기본 시간대를 넘긴다.
     */
    public static ZoneId resolve(String itemTimeZone, ZoneId fallback) {
        if (itemTimeZone == null) return fallback;
        if (!isIanaZoneId(itemTimeZone)) {
            throw new IllegalArgumentException("IANA 시간대가 아닙니다.");
        }
        return ZoneId.of(itemTimeZone);
    }

    /** 순간을 아이템 시간대의 날짜로 자른다. 반복의 기준 날짜와 날짜 없는 아이템의 만든 날에 쓴다. */
    public static LocalDate dateOf(Instant instant, ZoneId itemZone) {
        return instant.atZone(itemZone).toLocalDate();
    }

    /** 순간을 아이템 시간대의 벽시계 시각으로 읽는다. 반복 회차가 이 시각을 그대로 잇는다. */
    public static LocalTime wallTimeOf(Instant instant, ZoneId itemZone) {
        return instant.atZone(itemZone).toLocalTime();
    }

    /**
     * 아이템 시간대의 날짜와 벽시계 시각을 순간으로 바꾼다. 반복 회차의 시작을 낼 때 쓴다.
     * 일광 절약으로 없는 시각은 그 틈만큼 뒤로, 두 번 있는 시각은 앞의 것으로 정한다(java.time 규칙, RFC 5545 3.3.5와 같다).
     */
    public static Instant at(LocalDate date, LocalTime wallTime, ZoneId itemZone) {
        return ZonedDateTime.of(date, wallTime, itemZone).toInstant();
    }
}
