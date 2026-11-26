package com.slotwise.availability.engine;

import com.slotwise.availability.domain.ExceptionType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlotCalculatorTest {

    private static final ZoneId TORONTO = ZoneId.of("America/Toronto");
    private static final LocalDate MONDAY = LocalDate.of(2030, 1, 7);
    private static final Instant LONG_AGO = Instant.parse("2000-01-01T00:00:00Z");

    private final SlotCalculator calculator = new SlotCalculator();

    @Nested
    class WorkingHours {

        @Test
        void generatesSlotsThatFitInsideWorkingHours() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY, day("09:00", "12:00")));

            List<Slot> slots = calculate(rules, MONDAY, MONDAY, 60, 0);

            assertThat(localStarts(slots)).containsExactly("09:00", "09:30", "10:00", "10:30", "11:00");
            assertThat(slots).allSatisfy(s -> assertThat(Duration.between(s.start(), s.end())).hasMinutes(60));
        }

        @Test
        void noSlotsOnDaysWithoutWorkingHours() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY, day("09:00", "12:00")));
            LocalDate tuesday = MONDAY.plusDays(1);

            assertThat(calculate(rules, tuesday, tuesday, 30, 0)).isEmpty();
        }

        @Test
        void differentWeekdaysUseDifferentSchedules() {
            ScheduleRules rules = rules(60, Map.of(
                    DayOfWeek.MONDAY, day("09:00", "11:00"),
                    DayOfWeek.TUESDAY, day("14:00", "16:00")));

            List<Slot> slots = calculate(rules, MONDAY, MONDAY.plusDays(1), 60, 0);

            assertThat(localDateTimes(slots)).containsExactly(
                    "2030-01-07T09:00", "2030-01-07T10:00", "2030-01-08T14:00", "2030-01-08T15:00");
        }

        @Test
        void serviceLongerThanWindowYieldsNothing() {
            ScheduleRules rules = rules(15, Map.of(DayOfWeek.MONDAY, day("09:00", "10:00")));
            assertThat(calculate(rules, MONDAY, MONDAY, 90, 0)).isEmpty();
        }

        @Test
        void slotsAreConvertedToUtcUsingProviderZone() {
            ScheduleRules rules = new ScheduleRules(ZoneId.of("Asia/Kolkata"), Duration.ofMinutes(60),
                    Map.of(DayOfWeek.MONDAY, day("09:00", "10:00")), List.of());

            List<Slot> slots = calculator.calculate(rules, query(MONDAY, MONDAY, 60, 0, List.of()));

            // UTC+05:30 -> 09:00 local is 03:30Z
            assertThat(slots).extracting(Slot::start).containsExactly(Instant.parse("2030-01-07T03:30:00Z"));
        }
    }

    @Nested
    class BreaksAndExceptions {

        @Test
        void breaksSplitTheDayAndRealignSlots() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY,
                    new DayRule(t("09:00"), t("12:00"), List.of(window("10:00", "10:30")))));

            assertThat(localStarts(calculate(rules, MONDAY, MONDAY, 30, 0)))
                    .containsExactly("09:00", "09:30", "10:30", "11:00", "11:30");
        }

        @Test
        void appointmentCannotSpanABreak() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY,
                    new DayRule(t("09:00"), t("12:00"), List.of(window("10:00", "10:30")))));

            assertThat(localStarts(calculate(rules, MONDAY, MONDAY, 60, 0)))
                    .containsExactly("09:00", "10:30", "11:00");
        }

        @Test
        void holidayClosesTheWholeDay() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY, day("09:00", "17:00")),
                    new DateException(MONDAY, ExceptionType.HOLIDAY, null, null),
                    new DateException(MONDAY, ExceptionType.EXTRA_HOURS, t("18:00"), t("19:00")));

            assertThat(calculate(rules, MONDAY, MONDAY, 30, 0)).isEmpty();
        }

        @Test
        void fullDayBlockClosesTheDay() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY, day("09:00", "17:00")),
                    new DateException(MONDAY, ExceptionType.BLOCKED, null, null));

            assertThat(calculate(rules, MONDAY, MONDAY, 30, 0)).isEmpty();
        }

        @Test
        void partialBlockRemovesOnlyThatRange() {
            ScheduleRules rules = rules(60, Map.of(DayOfWeek.MONDAY, day("09:00", "13:00")),
                    new DateException(MONDAY, ExceptionType.BLOCKED, t("10:00"), t("11:00")));

            assertThat(localStarts(calculate(rules, MONDAY, MONDAY, 60, 0)))
                    .containsExactly("09:00", "11:00", "12:00");
        }

        @Test
        void extraHoursOpenAnOtherwiseClosedDay() {
            LocalDate sunday = MONDAY.minusDays(1);
            ScheduleRules rules = rules(60, Map.of(DayOfWeek.MONDAY, day("09:00", "17:00")),
                    new DateException(sunday, ExceptionType.EXTRA_HOURS, t("10:00"), t("12:00")));

            assertThat(localStarts(calculate(rules, sunday, sunday, 60, 0))).containsExactly("10:00", "11:00");
        }

        @Test
        void extraHoursAdjacentToRegularHoursAreMerged() {
            ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY, day("09:00", "12:00")),
                    new DateException(MONDAY, ExceptionType.EXTRA_HOURS, t("12:00"), t("13:00")));

            // the 11:30-12:30 slot spans the boundary between regular and extra hours
            assertThat(localStarts(calculate(rules, MONDAY, MONDAY, 60, 0))).contains("11:30", "12:00")
                    .doesNotContain("12:30");
        }

        @Test
        void exceptionsOnlyAffectTheirOwnDate() {
            ScheduleRules rules = rules(60, Map.of(
                    DayOfWeek.MONDAY, day("09:00", "10:00"),
                    DayOfWeek.TUESDAY, day("09:00", "10:00")),
                    new DateException(MONDAY, ExceptionType.HOLIDAY, null, null));

            assertThat(localDateTimes(calculate(rules, MONDAY, MONDAY.plusDays(1), 60, 0)))
                    .containsExactly("2030-01-08T09:00");
        }
    }

    @Nested
    class ExistingAppointmentsAndBuffers {

        private final ScheduleRules rules = rules(15, Map.of(DayOfWeek.MONDAY, day("09:00", "12:00")));

        @Test
        void slotsOverlappingExistingAppointmentsAreRemoved() {
            // existing 10:00-10:30, buffer 10 -> occupies [10:00, 10:40)
            Interval busy = new Interval(at(MONDAY, "10:00"), at(MONDAY, "10:40"));

            List<Slot> slots = calculator.calculate(rules, query(MONDAY, MONDAY, 30, 0, List.of(busy)));

            assertThat(localStarts(slots))
                    .contains("09:00", "09:30", "10:45")
                    .doesNotContain("09:45", "10:00", "10:15", "10:30");
        }

        @Test
        void bufferOfNewAppointmentMustNotRunIntoNextAppointment() {
            Interval busy = new Interval(at(MONDAY, "10:00"), at(MONDAY, "10:30"));

            // 30 min + 15 min buffer: 09:15 would occupy [09:15, 10:00) -> ok; 09:30 -> [09:30, 10:15) -> overlap
            List<Slot> slots = calculator.calculate(rules, query(MONDAY, MONDAY, 30, 15, List.of(busy)));

            assertThat(localStarts(slots)).contains("09:15").doesNotContain("09:30", "09:45");
        }

        @Test
        void bufferMayExtendPastClosingTime() {
            List<Slot> slots = calculator.calculate(rules, query(MONDAY, MONDAY, 30, 30, List.of()));
            assertThat(localStarts(slots)).contains("11:30");
        }

        @Test
        void backToBackAppointmentsAreAllowed() {
            Interval busy = new Interval(at(MONDAY, "10:00"), at(MONDAY, "10:30"));

            List<Slot> slots = calculator.calculate(rules, query(MONDAY, MONDAY, 30, 0, List.of(busy)));

            assertThat(localStarts(slots)).contains("09:30", "10:30");
        }
    }

    @Nested
    class NoticeAndHorizon {

        private final ScheduleRules rules = rules(60, Map.of(DayOfWeek.MONDAY, day("09:00", "13:00")));

        @Test
        void slotsBeforeEarliestStartAreExcluded() {
            SlotQuery query = new SlotQuery(MONDAY, MONDAY, Duration.ofMinutes(60), Duration.ZERO,
                    at(MONDAY, "10:30"), null, List.of());

            assertThat(localStarts(calculator.calculate(rules, query))).containsExactly("11:00", "12:00");
        }

        @Test
        void slotsAfterLatestStartAreExcluded() {
            SlotQuery query = new SlotQuery(MONDAY, MONDAY.plusDays(7), Duration.ofMinutes(60), Duration.ZERO,
                    LONG_AGO, at(MONDAY, "10:00"), List.of());

            assertThat(localStarts(calculator.calculate(rules, query))).containsExactly("09:00", "10:00");
        }
    }

    @Nested
    class DaylightSavingTime {

        @Test
        void springForwardSkipsTheNonExistentHour() {
            LocalDate dstStart = LocalDate.of(2030, 3, 10); // 02:00 -> 03:00 in Toronto
            ScheduleRules rules = rules(60, Map.of(DayOfWeek.SUNDAY, day("01:00", "05:00")));

            List<Slot> slots = calculate(rules, dstStart, dstStart, 60, 0);

            // 01:00-05:00 local is only 3 elapsed hours on this day
            assertThat(localStarts(slots)).containsExactly("01:00", "03:00", "04:00");
            assertThat(slots).extracting(Slot::start).containsExactly(
                    Instant.parse("2030-03-10T06:00:00Z"),
                    Instant.parse("2030-03-10T07:00:00Z"),
                    Instant.parse("2030-03-10T08:00:00Z"));
        }

        @Test
        void fallBackOffersTheRepeatedHourTwiceAndKeepsRealDurations() {
            LocalDate dstEnd = LocalDate.of(2030, 11, 3); // 02:00 -> 01:00 in Toronto
            ScheduleRules rules = rules(60, Map.of(DayOfWeek.SUNDAY, day("00:00", "03:00")));

            List<Slot> slots = calculate(rules, dstEnd, dstEnd, 60, 0);

            // 00:00-03:00 local is 4 elapsed hours on this day
            assertThat(slots).hasSize(4);
            assertThat(slots).extracting(s -> s.start().atZone(TORONTO).toOffsetDateTime().toString())
                    .containsExactly("2030-11-03T00:00-04:00", "2030-11-03T01:00-04:00",
                            "2030-11-03T01:00-05:00", "2030-11-03T02:00-05:00");
            assertThat(slots).allSatisfy(s -> assertThat(Duration.between(s.start(), s.end())).hasMinutes(60));
        }

        @Test
        void sameLocalTimeMapsToDifferentUtcAcrossDstBoundary() {
            ScheduleRules rules = rules(60, Map.of(
                    DayOfWeek.SATURDAY, day("09:00", "10:00"),
                    DayOfWeek.MONDAY, day("09:00", "10:00")));
            LocalDate saturdayBefore = LocalDate.of(2030, 3, 9);
            LocalDate mondayAfter = LocalDate.of(2030, 3, 11);

            List<Slot> slots = calculate(rules, saturdayBefore, mondayAfter, 60, 0);

            assertThat(slots).extracting(Slot::start).containsExactly(
                    Instant.parse("2030-03-09T14:00:00Z"),  // EST, UTC-5
                    Instant.parse("2030-03-11T13:00:00Z")); // EDT, UTC-4
        }
    }

    @Nested
    class Check {

        private final ScheduleRules rules = rules(30, Map.of(DayOfWeek.MONDAY, day("09:00", "12:00")));
        private final Duration duration = Duration.ofMinutes(30);

        @Test
        void validSlotIsAvailable() {
            assertThat(check(at(MONDAY, "09:30"), List.of())).isEqualTo(SlotCheck.AVAILABLE);
        }

        @Test
        void misalignedOrOutOfHoursTimesAreNotOffered() {
            assertThat(check(at(MONDAY, "09:10"), List.of())).isEqualTo(SlotCheck.OUTSIDE_AVAILABILITY);
            assertThat(check(at(MONDAY, "08:30"), List.of())).isEqualTo(SlotCheck.OUTSIDE_AVAILABILITY);
            assertThat(check(at(MONDAY, "11:45"), List.of())).isEqualTo(SlotCheck.OUTSIDE_AVAILABILITY);
            assertThat(check(at(MONDAY.plusDays(1), "09:30"), List.of())).isEqualTo(SlotCheck.OUTSIDE_AVAILABILITY);
        }

        @Test
        void overlappingAppointmentIsAConflict() {
            Interval busy = new Interval(at(MONDAY, "09:15"), at(MONDAY, "09:45"));
            assertThat(check(at(MONDAY, "09:30"), List.of(busy))).isEqualTo(SlotCheck.CONFLICT);
        }

        @Test
        void tooSoonAndTooFarAhead() {
            Instant start = at(MONDAY, "09:30");
            assertThat(calculator.check(rules, start, duration, Duration.ZERO, start.plusSeconds(1), null, List.of()))
                    .isEqualTo(SlotCheck.TOO_SOON);
            assertThat(calculator.check(rules, start, duration, Duration.ZERO, LONG_AGO, start.minusSeconds(1),
                    List.of())).isEqualTo(SlotCheck.TOO_FAR_AHEAD);
        }

        private SlotCheck check(Instant start, List<Interval> busy) {
            return calculator.check(rules, start, duration, Duration.ZERO, LONG_AGO, null, busy);
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<Slot> calculate(ScheduleRules rules, LocalDate from, LocalDate to, int durationMinutes,
            int bufferMinutes) {
        return calculator.calculate(rules, query(from, to, durationMinutes, bufferMinutes, List.of()));
    }

    private static SlotQuery query(LocalDate from, LocalDate to, int durationMinutes, int bufferMinutes,
            List<Interval> busy) {
        return new SlotQuery(from, to, Duration.ofMinutes(durationMinutes), Duration.ofMinutes(bufferMinutes),
                LONG_AGO, null, busy);
    }

    private static ScheduleRules rules(int intervalMinutes, Map<DayOfWeek, DayRule> weekly,
            DateException... exceptions) {
        return new ScheduleRules(TORONTO, Duration.ofMinutes(intervalMinutes), weekly, List.of(exceptions));
    }

    private static DayRule day(String start, String end) {
        return new DayRule(t(start), t(end), List.of());
    }

    private static TimeWindow window(String start, String end) {
        return new TimeWindow(t(start), t(end));
    }

    private static LocalTime t(String value) {
        return LocalTime.parse(value);
    }

    private static Instant at(LocalDate date, String time) {
        return LocalDateTime.of(date, t(time)).atZone(TORONTO).toInstant();
    }

    private static List<String> localStarts(List<Slot> slots) {
        return slots.stream().map(s -> s.start().atZone(TORONTO).toLocalTime().toString()).toList();
    }

    private static List<String> localDateTimes(List<Slot> slots) {
        return slots.stream().map(s -> s.start().atZone(TORONTO).toLocalDateTime().toString()).toList();
    }

    @SuppressWarnings("unused")
    private static Instant utc(String isoLocal) {
        return LocalDateTime.parse(isoLocal).toInstant(ZoneOffset.UTC);
    }
}
