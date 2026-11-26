package com.slotwise.availability.engine;

import com.slotwise.availability.domain.ExceptionType;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * A date-specific override. {@code start}/{@code end} are null for full-day
 * exceptions (HOLIDAY, full-day BLOCKED).
 */
public record DateException(LocalDate date, ExceptionType type, LocalTime start, LocalTime end) {

    public boolean closesWholeDay() {
        return type == ExceptionType.HOLIDAY || (type == ExceptionType.BLOCKED && start == null);
    }

    public TimeWindow window() {
        return new TimeWindow(start, end);
    }
}
