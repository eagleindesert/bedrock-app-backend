package com.bedrock.app.common.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 기능 명세 v0.0.6 F-23: 일정과 할 일 반복이 받는 RRULE 꼴과 회차 날짜 계산.
 *
 * <p>받는 꼴(부분 순서는 상관없고, 같은 부분을 두 번 쓰지 않는다):
 * <ul>
 *   <li>{@code FREQ=DAILY[;INTERVAL=n]}: n일마다</li>
 *   <li>{@code FREQ=WEEKLY[;INTERVAL=n][;BYDAY=MO,WE,...]}: 매주. BYDAY가 없으면 기준 날짜의 요일</li>
 *   <li>{@code FREQ=MONTHLY[;INTERVAL=n][;BYMONTHDAY=d]}: 매월 같은 날짜(d는 1~31). 없으면 기준 날짜의 일</li>
 *   <li>{@code FREQ=YEARLY[;INTERVAL=n][;BYMONTH=m;BYMONTHDAY=d]}: 매년 같은 날짜. 없으면 기준 날짜의 월 · 일</li>
 * </ul>
 * 앱이 쓰는 꼴은 BYMONTHDAY · BYMONTH를 빼고 기준 날짜를 따르는 꼴이다. 명시 꼴은 ICS에서 들어온 규칙을 받으려고 둔다.
 * 끝은 RecurrenceSpec.untilDate 칸이 맡으므로 UNTIL · COUNT는 받지 않는다.
 * n번째 요일(BYDAY=3TU) · 말일(BYMONTHDAY=-1) · BYSETPOS · 여러 BYMONTHDAY는 MVP 밖이라 받지 않는다(6장).
 *
 * <p>그 달에 없는 날은 건너뛴다(RFC 5545 3.3.10: 무효 날짜는 무시하고 세지 않는다).
 * 매달 31일은 31일이 있는 달에만, 2월 29일은 윤년에만 회차가 난다.
 *
 * <p>기준 날짜는 시작, 없으면 마감이다. 시각 값이면 아이템의 표준 시간대로 자른 날짜를 넘긴다(F-12 · F-23).
 * 기준 날짜는 규칙과 맞아야 하고({@link #isSynchronizedWith}), 맞으면 첫 회차다.
 */
public final class RecurrenceRule {
    public static final int MAX_LENGTH = 500;

    public enum Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

    private static final Map<String, DayOfWeek> DAY_CODES = Map.of(
            "MO", DayOfWeek.MONDAY, "TU", DayOfWeek.TUESDAY, "WE", DayOfWeek.WEDNESDAY,
            "TH", DayOfWeek.THURSDAY, "FR", DayOfWeek.FRIDAY, "SA", DayOfWeek.SATURDAY, "SU", DayOfWeek.SUNDAY);

    private final Frequency frequency;
    private final int interval;
    private final Set<DayOfWeek> byDay;
    private final Integer byMonth;
    private final Integer byMonthDay;

    private RecurrenceRule(Frequency frequency, int interval, Set<DayOfWeek> byDay, Integer byMonth, Integer byMonthDay) {
        this.frequency = frequency;
        this.interval = interval;
        this.byDay = byDay;
        this.byMonth = byMonth;
        this.byMonthDay = byMonthDay;
    }

    /** 받는 꼴이면 규칙을, 아니면 빈 값을 돌려준다. 거절 이유는 사용자 입력이라 담지 않는다. */
    public static Optional<RecurrenceRule> parse(String rrule) {
        try {
            return Optional.of(parseOrThrow(rrule));
        } catch (IllegalArgumentException rejected) {
            return Optional.empty();
        }
    }

    public static boolean isSupported(String rrule) {
        return parse(rrule).isPresent();
    }

    private static RecurrenceRule parseOrThrow(String rrule) {
        if (rrule == null || rrule.isEmpty() || rrule.length() > MAX_LENGTH) throw new IllegalArgumentException();
        var parts = new LinkedHashMap<String, String>();
        for (String part : rrule.split(";", -1)) {
            int equals = part.indexOf('=');
            if (equals <= 0 || equals == part.length() - 1) throw new IllegalArgumentException();
            if (parts.put(part.substring(0, equals), part.substring(equals + 1)) != null) throw new IllegalArgumentException();
        }
        Frequency frequency = Frequency.valueOf(require(parts.remove("FREQ")));
        int interval = parts.containsKey("INTERVAL") ? positive(parts.remove("INTERVAL"), 999) : 1;
        Set<DayOfWeek> byDay = EnumSet.noneOf(DayOfWeek.class);
        Integer byMonth = null;
        Integer byMonthDay = null;
        switch (frequency) {
            case DAILY -> { }
            case WEEKLY -> {
                String days = parts.remove("BYDAY");
                if (days != null) {
                    for (String code : days.split(",", -1)) {
                        DayOfWeek day = DAY_CODES.get(code);
                        if (day == null || !byDay.add(day)) throw new IllegalArgumentException();
                    }
                }
            }
            case MONTHLY -> {
                String day = parts.remove("BYMONTHDAY");
                if (day != null) byMonthDay = positive(day, 31);
            }
            case YEARLY -> {
                String month = parts.remove("BYMONTH");
                String day = parts.remove("BYMONTHDAY");
                if ((month == null) != (day == null)) throw new IllegalArgumentException();
                if (month != null) {
                    byMonth = positive(month, 12);
                    byMonthDay = positive(day, 31);
                    // 어느 해에도 없는 날(예: 4월 31일)은 회차가 하나도 나지 않으므로 받지 않는다.
                    if (byMonthDay > YearMonth.of(2024, byMonth).lengthOfMonth()) throw new IllegalArgumentException();
                }
            }
        }
        if (!parts.isEmpty()) throw new IllegalArgumentException();
        return new RecurrenceRule(frequency, interval, Set.copyOf(byDay), byMonth, byMonthDay);
    }

    private static String require(String value) {
        if (value == null) throw new IllegalArgumentException();
        return value;
    }

    /** 앞에 0을 붙이지 않은 1 이상 max 이하의 정수만 받는다. */
    private static int positive(String value, int max) {
        if (!value.matches("[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
        int number = Integer.parseInt(value);
        if (number > max) throw new IllegalArgumentException();
        return number;
    }

    public Frequency frequency() {
        return frequency;
    }

    public int interval() {
        return interval;
    }

    /** 기준 날짜가 이 규칙이 내는 자리에 있는지. 명시한 BYDAY · BYMONTH · BYMONTHDAY와 어긋나면 거절한다. */
    public boolean isSynchronizedWith(LocalDate anchor) {
        return switch (frequency) {
            case DAILY -> true;
            case WEEKLY -> byDay.isEmpty() || byDay.contains(anchor.getDayOfWeek());
            case MONTHLY -> byMonthDay == null || byMonthDay == anchor.getDayOfMonth();
            case YEARLY -> byMonth == null
                    || (byMonth == anchor.getMonthValue() && byMonthDay == anchor.getDayOfMonth());
        };
    }

    /**
     * 기준 날짜부터 규칙이 내는 회차 날짜 가운데 [from, to] 안의 것을 순서대로 돌려준다.
     * RDATE · EXDATE · untilDate는 부르는 쪽이 더하고 뺀다. untilDate가 있으면 to를 그 날짜로 줄여 넘긴다.
     */
    public List<LocalDate> occurrencesBetween(LocalDate anchor, LocalDate from, LocalDate to) {
        if (!isSynchronizedWith(anchor)) throw new IllegalArgumentException("기준 날짜가 규칙과 맞지 않습니다.");
        var dates = new ArrayList<LocalDate>();
        LocalDate start = from.isAfter(anchor) ? from : anchor;
        if (start.isAfter(to)) return dates;
        switch (frequency) {
            case DAILY -> {
                long skip = Math.floorMod(ChronoUnit.DAYS.between(anchor, start), interval);
                for (LocalDate date = skip == 0 ? start : start.plusDays(interval - skip);
                        !date.isAfter(to); date = date.plusDays(interval)) {
                    dates.add(date);
                }
            }
            case WEEKLY -> {
                Set<DayOfWeek> days = byDay.isEmpty() ? EnumSet.of(anchor.getDayOfWeek()) : byDay;
                // 주 간격은 기준 날짜가 든 주(월요일 시작, RFC 5545 WKST 기본값)부터 센다.
                LocalDate anchorWeek = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                for (LocalDate date = start; !date.isAfter(to); date = date.plusDays(1)) {
                    long weeks = ChronoUnit.WEEKS.between(anchorWeek, date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
                    if (weeks % interval == 0 && days.contains(date.getDayOfWeek())) dates.add(date);
                }
            }
            case MONTHLY -> {
                int day = byMonthDay != null ? byMonthDay : anchor.getDayOfMonth();
                YearMonth anchorMonth = YearMonth.from(anchor);
                for (YearMonth month = YearMonth.from(start); !month.atDay(1).isAfter(to); month = month.plusMonths(1)) {
                    if (ChronoUnit.MONTHS.between(anchorMonth, month) % interval != 0) continue;
                    if (day > month.lengthOfMonth()) continue; // 그 달에 없는 날은 건너뛴다
                    addIfWithin(dates, month.atDay(day), start, to);
                }
            }
            case YEARLY -> {
                int monthValue = byMonth != null ? byMonth : anchor.getMonthValue();
                int day = byMonthDay != null ? byMonthDay : anchor.getDayOfMonth();
                for (int year = start.getYear(); year <= to.getYear(); year++) {
                    if ((year - anchor.getYear()) % interval != 0) continue;
                    YearMonth month = YearMonth.of(year, monthValue);
                    if (day > month.lengthOfMonth()) continue; // 2월 29일은 윤년에만
                    addIfWithin(dates, month.atDay(day), start, to);
                }
            }
        }
        return dates;
    }

    private static void addIfWithin(List<LocalDate> dates, LocalDate date, LocalDate from, LocalDate to) {
        if (!date.isBefore(from) && !date.isAfter(to)) dates.add(date);
    }
}
