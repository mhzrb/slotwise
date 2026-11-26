package com.slotwise.availability.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slotwise.availability.domain.ExceptionType;
import com.slotwise.common.validation.TimeRange;
import com.slotwise.common.validation.ValidTimeRange;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;

@ValidTimeRange
public record AvailabilityExceptionRequest(
        @Schema(description = "Provider-local date", example = "2030-12-25") @NotNull LocalDate date,
        @NotNull ExceptionType type,
        @Schema(type = "string", example = "10:00", description = "Omit for whole-day HOLIDAY/BLOCKED")
        LocalTime startTime,
        @Schema(type = "string", example = "12:00") LocalTime endTime,
        @Size(max = 255) String reason) implements TimeRange {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "HOLIDAY must not have times; EXTRA_HOURS requires both times; "
            + "otherwise give both times or neither")
    public boolean isTimesConsistentWithType() {
        boolean none = startTime == null && endTime == null;
        boolean both = startTime != null && endTime != null;
        if (type == ExceptionType.HOLIDAY) {
            return none;
        }
        if (type == ExceptionType.EXTRA_HOURS) {
            return both;
        }
        return none || both;
    }
}
