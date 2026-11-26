package com.slotwise.availability.web.dto;

import com.slotwise.common.validation.TimeRange;
import com.slotwise.common.validation.ValidTimeRange;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

@ValidTimeRange
public record BreakRequest(
        @Schema(type = "string", example = "12:00") @NotNull LocalTime startTime,
        @Schema(type = "string", example = "13:00") @NotNull LocalTime endTime) implements TimeRange {
}
