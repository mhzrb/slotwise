package com.slotwise.availability.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * @param from          first provider-local date (inclusive)
 * @param to            last provider-local date (inclusive)
 * @param duration      service duration (must fit inside working hours)
 * @param buffer        blocked time after the service (may extend past working hours)
 * @param earliestStart no slot may start before this instant (now + minimum notice)
 * @param latestStart   no slot may start after this instant (booking horizon)
 * @param busy          occupied intervals {@code [start, blockedUntil)} of existing appointments
 */
public record SlotQuery(
        LocalDate from,
        LocalDate to,
        Duration duration,
        Duration buffer,
        Instant earliestStart,
        Instant latestStart,
        List<Interval> busy) {

    public SlotQuery {
        busy = List.copyOf(busy);
    }
}
