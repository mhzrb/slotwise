package com.slotwise.availability.engine;

import java.time.LocalTime;
import java.util.List;

/** Regular working hours for one weekday, with optional breaks. */
public record DayRule(LocalTime start, LocalTime end, List<TimeWindow> breaks) {

    public DayRule {
        breaks = breaks == null ? List.of() : List.copyOf(breaks);
    }

    public List<TimeWindow> workingWindows() {
        return TimeWindow.subtract(List.of(new TimeWindow(start, end)), breaks);
    }
}
