package com.slotwise.appointment.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public record RescheduleAppointmentRequest(
        @Schema(example = "2030-01-08T15:00:00Z") @NotNull OffsetDateTime startAt) {
}
