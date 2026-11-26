package com.slotwise.availability.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record WeeklyScheduleRequest(
        @Schema(description = "Working days. Weekdays that are not listed are days off.")
        @NotNull @Size(max = 7) List<@Valid @NotNull DayScheduleRequest> days) {
}
