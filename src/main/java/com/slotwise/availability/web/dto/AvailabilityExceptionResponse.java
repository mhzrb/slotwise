package com.slotwise.availability.web.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slotwise.availability.domain.AvailabilityException;
import com.slotwise.availability.domain.ExceptionType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record AvailabilityExceptionResponse(
        UUID id,
        LocalDate date,
        ExceptionType type,
        @JsonFormat(pattern = "HH:mm") LocalTime startTime,
        @JsonFormat(pattern = "HH:mm") LocalTime endTime,
        String reason) {

    /** Owner view, including the private reason. */
    public static AvailabilityExceptionResponse forOwner(AvailabilityException e) {
        return new AvailabilityExceptionResponse(e.getId(), e.getDate(), e.getType(), e.getStartTime(),
                e.getEndTime(), e.getReason());
    }

    /** Public view: customers see that the provider is unavailable, not why. */
    public static AvailabilityExceptionResponse forPublic(AvailabilityException e) {
        return new AvailabilityExceptionResponse(e.getId(), e.getDate(), e.getType(), e.getStartTime(),
                e.getEndTime(), null);
    }
}
