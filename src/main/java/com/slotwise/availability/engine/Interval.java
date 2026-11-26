package com.slotwise.availability.engine;

import java.time.Instant;

/** A half-open range of absolute time {@code [start, end)}. */
public record Interval(Instant start, Instant end) {

    public Interval {
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end");
        }
    }

    public boolean overlaps(Interval other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
}
