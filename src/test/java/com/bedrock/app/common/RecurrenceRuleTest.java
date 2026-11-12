package com.bedrock.app.common;

import com.bedrock.app.common.domain.RecurrenceRule;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 기능 명세 v0.0.6 F-23: n일마다 · 매주 · 매월 · 매년, 그 달에 없는 날은 건너뛴다(RFC 5545 3.3.10). */
class RecurrenceRuleTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "FREQ=DAILY", "FREQ=DAILY;INTERVAL=3", "FREQ=WEEKLY", "FREQ=WEEKLY;BYDAY=MO",
            "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE,FR", "BYDAY=TU;FREQ=WEEKLY",
            "FREQ=MONTHLY", "FREQ=MONTHLY;BYMONTHDAY=31", "FREQ=MONTHLY;INTERVAL=3;BYMONTHDAY=15",
            "FREQ=YEARLY", "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29", "FREQ=YEARLY;BYMONTH=3;BYMONTHDAY=2"})
    void acceptsTheMvpForms(String rrule) {
        assertThat(RecurrenceRule.isSupported(rrule)).as(rrule).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "FREQ=HOURLY", "FREQ=daily", "FREQ=WEEKLY;FREQ=WEEKLY", "FREQ=DAILY;INTERVAL=0",
            "FREQ=DAILY;INTERVAL=01", "FREQ=DAILY;COUNT=10", "FREQ=DAILY;UNTIL=20261231",
            "FREQ=WEEKLY;BYDAY=MO,MO", "FREQ=WEEKLY;BYMONTHDAY=1", "FREQ=MONTHLY;BYDAY=3TU",
            "FREQ=MONTHLY;BYMONTHDAY=-1", "FREQ=MONTHLY;BYMONTHDAY=1,15", "FREQ=MONTHLY;BYMONTHDAY=32",
            "FREQ=MONTHLY;BYSETPOS=-1;BYDAY=MO", "FREQ=YEARLY;BYMONTH=3", "FREQ=YEARLY;BYMONTHDAY=2",
            "FREQ=YEARLY;BYMONTH=4;BYMONTHDAY=31", "FREQ=YEARLY;BYMONTH=13;BYMONTHDAY=1", "FREQ=DAILY;"})
    void rejectsOtherForms(String rrule) {
        assertThat(RecurrenceRule.isSupported(rrule)).as(rrule).isFalse();
    }

    @Test
    void monthlyOnThe31stSkipsShortMonths() {
        var rule = RecurrenceRule.parse("FREQ=MONTHLY").orElseThrow();
        assertThat(rule.occurrencesBetween(date("2026-01-31"), date("2026-01-01"), date("2026-12-31")))
                .containsExactly(date("2026-01-31"), date("2026-03-31"), date("2026-05-31"), date("2026-07-31"),
                        date("2026-08-31"), date("2026-10-31"), date("2026-12-31"));
    }

    @Test
    void yearlyOnFebruary29thOnlyInLeapYears() {
        var rule = RecurrenceRule.parse("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29").orElseThrow();
        assertThat(rule.occurrencesBetween(date("2024-02-29"), date("2024-01-01"), date("2033-12-31")))
                .containsExactly(date("2024-02-29"), date("2028-02-29"), date("2032-02-29"));
    }

    @Test
    void intervalsCountFromTheAnchor() {
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY;INTERVAL=2;BYMONTHDAY=15").orElseThrow()
                .occurrencesBetween(date("2026-01-15"), date("2026-02-01"), date("2026-07-31")))
                .containsExactly(date("2026-03-15"), date("2026-05-15"), date("2026-07-15"));
        assertThat(RecurrenceRule.parse("FREQ=DAILY;INTERVAL=3").orElseThrow()
                .occurrencesBetween(date("2026-11-01"), date("2026-11-05"), date("2026-11-12")))
                .containsExactly(date("2026-11-07"), date("2026-11-10"));
        // 2026-11-16은 월요일. 격주 월 · 수.
        assertThat(RecurrenceRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE").orElseThrow()
                .occurrencesBetween(date("2026-11-16"), date("2026-11-16"), date("2026-12-06")))
                .containsExactly(date("2026-11-16"), date("2026-11-18"), date("2026-11-30"), date("2026-12-02"));
        assertThat(RecurrenceRule.parse("FREQ=YEARLY").orElseThrow()
                .occurrencesBetween(date("2026-03-02"), date("2026-01-01"), date("2028-12-31")))
                .containsExactly(date("2026-03-02"), date("2027-03-02"), date("2028-03-02"));
    }

    @Test
    void anchorMustMatchAnExplicitRule() {
        var rule = RecurrenceRule.parse("FREQ=MONTHLY;BYMONTHDAY=15").orElseThrow();
        assertThat(rule.isSynchronizedWith(date("2026-01-15"))).isTrue();
        assertThat(rule.isSynchronizedWith(date("2026-01-16"))).isFalse();
        assertThatThrownBy(() -> rule.occurrencesBetween(date("2026-01-16"), date("2026-01-01"), date("2026-03-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY").orElseThrow().isSynchronizedWith(date("2026-01-16"))).isTrue();
    }

    @Test
    void nothingBeforeTheAnchor() {
        assertThat(RecurrenceRule.parse("FREQ=MONTHLY").orElseThrow()
                .occurrencesBetween(date("2026-06-10"), date("2026-01-01"), date("2026-05-31"))).isEmpty();
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value);
    }
}
