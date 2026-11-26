package com.slotwise.appointment.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BookAppointmentRequest(
        @NotNull UUID serviceId,
        @Schema(description = "Start time as returned by the slots endpoint (ISO-8601 with offset)",
                example = "2030-01-07T15:00:00Z")
        @NotNull OffsetDateTime startAt,
        @Size(max = 1000) String notes) {
}
