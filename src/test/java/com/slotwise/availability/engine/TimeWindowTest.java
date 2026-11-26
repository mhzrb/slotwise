package com.slotwise.availability.engine;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowTest {

    @Test
    void rejectsEmptyOrInvertedRanges() {
        assertThatThrownBy(() -> w("10:00", "10:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> w("11:00", "10:00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void halfOpenRangesDoNotOverlapWhenAdjacent() {
        assertThat(w("09:00", "10:00").overlaps(w("10:00", "11:00"))).isFalse();
        assertThat(w("09:00", "10:01").overlaps(w("10:00", "11:00"))).isTrue();
    }

    @Test
    void mergeCombinesOverlappingAndTouchingWindows() {
        assertThat(TimeWindow.merge(List.of(w("13:00", "14:00"), w("09:00", "10:00"), w("09:30", "11:00"),
                w("11:00", "12:00"))))
                .containsExactly(w("09:00", "12:00"), w("13:00", "14:00"));
    }

    @Test
    void subtractSplitsAndTrims() {
        List<TimeWindow> result = TimeWindow.subtract(List.of(w("09:00", "17:00")),
                List.of(w("12:00", "13:00"), w("08:00", "09:30"), w("16:30", "18:00")));

        assertThat(result).containsExactly(w("09:30", "12:00"), w("13:00", "16:30"));
    }

    @Test
    void subtractingEverythingLeavesNothing() {
        assertThat(TimeWindow.subtract(List.of(w("09:00", "10:00")), List.of(w("08:00", "11:00")))).isEmpty();
    }

    private static TimeWindow w(String start, String end) {
        return new TimeWindow(LocalTime.parse(start), LocalTime.parse(end));
    }
}
