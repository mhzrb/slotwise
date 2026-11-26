package com.slotwise.availability.service;

import com.slotwise.availability.engine.TimeWindow;
import com.slotwise.availability.service.AvailabilityService.DaySchedule;
import com.slotwise.common.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleValidationTest {

    @Test
    void acceptsValidSchedule() {
        assertThatCode(() -> AvailabilityService.validateSchedule(List.of(
                day(DayOfWeek.MONDAY, "09:00", "17:00", w("12:00", "13:00"), w("15:00", "15:15")),
                day(DayOfWeek.TUESDAY, "10:00", "14:00"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateWeekday() {
        assertThatThrownBy(() -> AvailabilityService.validateSchedule(List.of(
                day(DayOfWeek.MONDAY, "09:00", "12:00"),
                day(DayOfWeek.MONDAY, "13:00", "17:00"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void rejectsBreakOutsideWorkingHours() {
        assertThatThrownBy(() -> AvailabilityService.validateSchedule(List.of(
                day(DayOfWeek.MONDAY, "09:00", "12:00", w("11:30", "12:30")))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("outside working hours");
    }

    @Test
    void rejectsOverlappingBreaks() {
        assertThatThrownBy(() -> AvailabilityService.validateSchedule(List.of(
                day(DayOfWeek.MONDAY, "09:00", "17:00", w("12:00", "13:00"), w("12:30", "13:30")))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("overlap");
    }

    private static DaySchedule day(DayOfWeek dow, String start, String end, TimeWindow... breaks) {
        return new DaySchedule(dow, LocalTime.parse(start), LocalTime.parse(end), List.of(breaks));
    }

    private static TimeWindow w(String start, String end) {
        return new TimeWindow(LocalTime.parse(start), LocalTime.parse(end));
    }
}
