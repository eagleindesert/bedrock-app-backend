package com.bedrock.app.common.domain;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 칸 키(fieldKey). item_field_versions · item_field_writes · conflict_candidates의 행 정체성이고,
 * 기기가 baseFieldVersions의 키로 싣는다. 이름은 저장된 행과 기기 줄에 쌓이므로 바꾸지 않는다.
 *
 * <p>{@link #DUE_AT}은 due_at · due_date 두 열을 한 칸으로 다룬다. 값 꼴은
 * {@code {"dueAt": Instant|null, "dueDate": LocalDate|null}}이고 둘 중 하나만 null이 아니거나 둘 다 null이다.
 * PATCH는 두 값을 함께 바꾼다.
 */
public final class FieldKeys {
    public static final String NAME = "NAME";
    public static final String BODY = "BODY";
    public static final String LOCATION = "LOCATION";
    public static final String TAGS = "TAGS";
    public static final String COLOR = "COLOR";
    public static final String COLLECTION = "COLLECTION";
    public static final String HAS_SCHEDULE = "HAS_SCHEDULE";
    public static final String START_AT = "START_AT";
    public static final String END_AT = "END_AT";
    public static final String START_DATE = "START_DATE";
    public static final String END_DATE = "END_DATE";
    public static final String IS_ALL_DAY = "IS_ALL_DAY";
    public static final String TIME_ZONE = "TIME_ZONE";
    public static final String HAS_TODO = "HAS_TODO";
    public static final String TODO_STATUS = "TODO_STATUS";
    public static final String DUE_AT = "DUE_AT";
    public static final String PRIORITY = "PRIORITY";
    public static final String ESTIMATED_MINUTES = "ESTIMATED_MINUTES";
    public static final String ALARMS = "ALARMS";
    public static final String HAS_NOTE = "HAS_NOTE";
    public static final String RECURRENCE_RULE = "RECURRENCE_RULE";
    public static final String TIMETABLE_SLOTS = "TIMETABLE_SLOTS";
    /** 시간표 기간(periodStart · periodEnd) 한 칸. */
    public static final String TIMETABLE_PERIOD = "TIMETABLE_PERIOD";
    /** 정리 대기 표시(is_pending_inbox). */
    public static final String PENDING_INBOX = "PENDING_INBOX";

    public static final Set<String> FIXED = Set.of(NAME, BODY, LOCATION, TAGS, COLOR, COLLECTION,
            HAS_SCHEDULE, START_AT, END_AT, START_DATE, END_DATE, IS_ALL_DAY, TIME_ZONE,
            HAS_TODO, TODO_STATUS, DUE_AT, PRIORITY, ESTIMATED_MINUTES, ALARMS, HAS_NOTE,
            RECURRENCE_RULE, TIMETABLE_SLOTS, TIMETABLE_PERIOD, PENDING_INBOX);

    private static final String EXCEPTION_PREFIX = "EXCEPTION:";
    private static final String EXTRA_FIELD_PREFIX = "EXTRA_FIELD:";
    /** 확장 속성 키: 등록표의 이름(영문자로 시작, 영숫자 · 밑줄, 최대 64자). */
    private static final Pattern EXTRA_FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private FieldKeys() {
    }

    /** 일반 반복 회차 예외: {@code EXCEPTION:YYYY-MM-DD}. */
    public static String exception(LocalDate recurrenceDate) {
        return EXCEPTION_PREFIX + recurrenceDate;
    }

    /** 시간표 회차 예외: {@code EXCEPTION:YYYY-MM-DD:<slotId>}. */
    public static String exception(LocalDate recurrenceDate, UUID slotId) {
        return slotId == null ? exception(recurrenceDate) : EXCEPTION_PREFIX + recurrenceDate + ":" + slotId;
    }

    /** 확장 속성 하나: {@code EXTRA_FIELD:<키>}. 키마다 따로 버전 · 충돌을 다룬다. */
    public static String extraField(String name) {
        if (!EXTRA_FIELD_NAME.matcher(name).matches()) throw new IllegalArgumentException("확장 속성 키 꼴이 아닙니다.");
        return EXTRA_FIELD_PREFIX + name;
    }

    /** 기기가 보낸 칸 키가 정해진 꼴인지 본다. */
    public static boolean isValid(String key) {
        if (key == null) return false;
        if (FIXED.contains(key)) return true;
        if (key.startsWith(EXTRA_FIELD_PREFIX)) {
            return EXTRA_FIELD_NAME.matcher(key.substring(EXTRA_FIELD_PREFIX.length())).matches();
        }
        if (key.startsWith(EXCEPTION_PREFIX)) {
            String rest = key.substring(EXCEPTION_PREFIX.length());
            String date = rest.length() > 10 ? rest.substring(0, 10) : rest;
            try {
                if (!LocalDate.parse(date).toString().equals(date)) return false;
            } catch (DateTimeParseException rejected) {
                return false;
            }
            if (rest.length() == 10) return true;
            if (rest.charAt(10) != ':') return false;
            try {
                String slot = rest.substring(11);
                return UUID.fromString(slot).toString().equals(slot);
            } catch (IllegalArgumentException rejected) {
                return false;
            }
        }
        return false;
    }
}
