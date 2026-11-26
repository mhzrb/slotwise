package com.slotwise.availability.engine;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** A half-open local-time range {@code [start, end)} within a single calendar day. */
public record TimeWindow(LocalTime start, LocalTime end) {

    public TimeWindow {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        if (!start.isBefore(end)) {
            throw new IllegalArgumentException("start must be before end: " + start + " - " + end);
        }
    }

    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean contains(TimeWindow other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    /** Sorts and merges overlapping or touching windows. */
    public static List<TimeWindow> merge(List<TimeWindow> windows) {
        List<TimeWindow> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparing(TimeWindow::start));
        List<TimeWindow> merged = new ArrayList<>();
        for (TimeWindow w : sorted) {
            if (!merged.isEmpty() && !w.start.isAfter(merged.getLast().end)) {
                TimeWindow last = merged.removeLast();
                merged.add(new TimeWindow(last.start, last.end.isAfter(w.end) ? last.end : w.end));
            } else {
                merged.add(w);
            }
        }
        return merged;
    }

    /** Removes every {@code cut} from every window, splitting windows where necessary. */
    public static List<TimeWindow> subtract(List<TimeWindow> windows, List<TimeWindow> cuts) {
        List<TimeWindow> result = new ArrayList<>(windows);
        for (TimeWindow cut : cuts) {
            List<TimeWindow> next = new ArrayList<>();
            for (TimeWindow w : result) {
                if (!w.overlaps(cut)) {
                    next.add(w);
                    continue;
                }
                if (w.start.isBefore(cut.start)) {
                    next.add(new TimeWindow(w.start, cut.start));
                }
                if (cut.end.isBefore(w.end)) {
                    next.add(new TimeWindow(cut.end, w.end));
                }
            }
            result = next;
        }
        return result;
    }
}
