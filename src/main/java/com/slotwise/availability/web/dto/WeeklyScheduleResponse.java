package com.slotwise.availability.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slotwise.availability.domain.WeeklyScheduleDay;
import com.slotwise.provider.domain.ProviderProfile;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public record WeeklyScheduleResponse(
        UUID providerId,
        String timeZone,
        int slotIntervalMinutes,
        List<Day> days) {

    public record Day(DayOfWeek dayOfWeek, @JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm") LocalTime endTime, List<Break> breaks) {
    }

    public record Break(@JsonFormat(pattern = "HH:mm") LocalTime startTime,
            @JsonFormat(pattern = "HH:mm") LocalTime endTime) {
    }

    public static WeeklyScheduleResponse from(ProviderProfile provider, List<WeeklyScheduleDay> days) {
        return new WeeklyScheduleResponse(provider.getId(), provider.getTimeZone(), provider.getSlotIntervalMinutes(),
                days.stream().map(d -> new Day(d.getDayOfWeek(), d.getStartTime(), d.getEndTime(),
                        d.getBreaks().stream().map(b -> new Break(b.getStartTime(), b.getEndTime())).toList()))
                        .toList());
    }
}
