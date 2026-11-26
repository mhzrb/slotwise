package com.slotwise.availability.engine;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Everything about a provider's calendar that is independent of a particular booking. */
public record ScheduleRules(
        ZoneId zone,
        Duration slotInterval,
        Map<DayOfWeek, DayRule> weekly,
        List<DateException> exceptions) {

    public ScheduleRules {
        if (slotInterval.isNegative() || slotInterval.isZero()) {
            throw new IllegalArgumentException("slotInterval must be positive");
        }
        weekly = Map.copyOf(weekly);
        exceptions = List.copyOf(exceptions);
    }

    public List<DateException> exceptionsOn(LocalDate date) {
        return exceptions.stream().filter(e -> e.date().equals(date)).toList();
    }
}
