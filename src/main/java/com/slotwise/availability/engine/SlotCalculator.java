package com.slotwise.availability.engine;

import com.slotwise.availability.domain.ExceptionType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure, stateless slot generation. No Spring, no database: every input is
 * passed in explicitly, which makes the rules exhaustively unit-testable.
 *
 * <h2>Algorithm (per provider-local date)</h2>
 * <ol>
 *   <li>HOLIDAY or full-day BLOCKED &rarr; no availability.</li>
 *   <li>Weekly working hours for that weekday, minus breaks.</li>
 *   <li>Plus EXTRA_HOURS windows (merged).</li>
 *   <li>Minus partial BLOCKED windows.</li>
 *   <li>Each local window is converted to absolute instants in the provider's
 *       zone, then candidates are generated every {@code slotInterval} of
 *       <em>elapsed</em> time from the window start while
 *       {@code start + duration <= windowEnd}.</li>
 *   <li>Candidates before {@code earliestStart}, after {@code latestStart}, or
 *       whose occupied range {@code [start, start + duration + buffer)}
 *       overlaps a busy interval are dropped.</li>
 * </ol>
 *
 * <h2>Daylight saving time</h2>
 * Window bounds are resolved with {@link ZonedDateTime#of}: a local time in a
 * spring-forward gap is shifted forward by the gap length, and an ambiguous
 * local time in a fall-back overlap resolves to the earlier offset. Because
 * stepping happens on the instant timeline, a 60-minute service always lasts
 * 60 real minutes, a window spanning the fall-back hour really is one hour
 * longer, and no slot is ever generated for a non-existent local time.
 */
public final class SlotCalculator {

    public List<Slot> calculate(ScheduleRules rules, SlotQuery query) {
        List<Slot> slots = new ArrayList<>();
        for (LocalDate date = query.from(); !date.isAfter(query.to()); date = date.plusDays(1)) {
            for (Slot candidate : candidates(rules, date, query.duration())) {
                if (candidate.start().isBefore(query.earliestStart())) {
                    continue;
                }
                if (query.latestStart() != null && candidate.start().isAfter(query.latestStart())) {
                    return slots; // candidates are chronological; nothing later can qualify
                }
                if (!conflicts(candidate.start(), query.duration(), query.buffer(), query.busy())) {
                    slots.add(candidate);
                }
            }
        }
        return slots;
    }

    /**
     * Validates a single requested start time with exactly the same rules used
     * to list slots, so "listed" and "bookable" can never disagree.
     */
    public SlotCheck check(ScheduleRules rules, Instant start, Duration duration, Duration buffer,
            Instant earliestStart, Instant latestStart, List<Interval> busy) {
        if (start.isBefore(earliestStart)) {
            return SlotCheck.TOO_SOON;
        }
        if (latestStart != null && start.isAfter(latestStart)) {
            return SlotCheck.TOO_FAR_AHEAD;
        }
        LocalDate localDate = start.atZone(rules.zone()).toLocalDate();
        boolean offered = candidates(rules, localDate, duration).stream().anyMatch(s -> s.start().equals(start));
        if (!offered) {
            return SlotCheck.OUTSIDE_AVAILABILITY;
        }
        return conflicts(start, duration, buffer, busy) ? SlotCheck.CONFLICT : SlotCheck.AVAILABLE;
    }

    /** Local availability windows for one date after applying breaks and exceptions. */
    public List<TimeWindow> localWindows(ScheduleRules rules, LocalDate date) {
        List<DateException> exceptions = rules.exceptionsOn(date);
        if (exceptions.stream().anyMatch(DateException::closesWholeDay)) {
            return List.of();
        }
        List<TimeWindow> windows = new ArrayList<>();
        DayRule rule = rules.weekly().get(date.getDayOfWeek());
        if (rule != null) {
            windows.addAll(rule.workingWindows());
        }
        exceptions.stream()
                .filter(e -> e.type() == ExceptionType.EXTRA_HOURS)
                .map(DateException::window)
                .forEach(windows::add);
        windows = TimeWindow.merge(windows);
        List<TimeWindow> blocks = exceptions.stream()
                .filter(e -> e.type() == ExceptionType.BLOCKED && e.start() != null)
                .map(DateException::window)
                .toList();
        return TimeWindow.subtract(windows, blocks);
    }

    /** All slot candidates of one date, ignoring notice, horizon and existing appointments. */
    private List<Slot> candidates(ScheduleRules rules, LocalDate date, Duration duration) {
        List<Slot> result = new ArrayList<>();
        for (TimeWindow window : localWindows(rules, date)) {
            Instant windowStart = toInstant(date, window.start(), rules.zone());
            Instant windowEnd = toInstant(date, window.end(), rules.zone());
            for (Instant start = windowStart; !start.plus(duration).isAfter(windowEnd);
                    start = start.plus(rules.slotInterval())) {
                result.add(new Slot(start, start.plus(duration)));
            }
        }
        return result;
    }

    private static boolean conflicts(Instant start, Duration duration, Duration buffer, List<Interval> busy) {
        Interval occupied = new Interval(start, start.plus(duration).plus(buffer));
        for (Interval b : busy) {
            if (b.overlaps(occupied)) {
                return true;
            }
        }
        return false;
    }

    static Instant toInstant(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toInstant();
    }
}
