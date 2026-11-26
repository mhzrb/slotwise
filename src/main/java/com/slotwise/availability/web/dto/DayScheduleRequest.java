package com.slotwise.availability.web.dto;

import com.slotwise.availability.engine.TimeWindow;
import com.slotwise.availability.service.AvailabilityService.DaySchedule;
import com.slotwise.common.validation.TimeRange;
import com.slotwise.common.validation.ValidTimeRange;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

@ValidTimeRange
public record DayScheduleRequest(
        @NotNull DayOfWeek dayOfWeek,
        @Schema(type = "string", example = "09:00") @NotNull LocalTime startTime,
        @Schema(type = "string", example = "17:00") @NotNull LocalTime endTime,
        @Size(max = 10) List<@Valid @NotNull BreakRequest> breaks) implements TimeRange {

    public DaySchedule toDaySchedule() {
        List<TimeWindow> windows = breaks == null
                ? List.of()
                : breaks.stream().map(b -> new TimeWindow(b.startTime(), b.endTime())).toList();
        return new DaySchedule(dayOfWeek, startTime, endTime, windows);
    }
}
