package com.slotwise.appointment.web.dto;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record AppointmentResponse(
        UUID id,
        AppointmentStatus status,
        @Schema(description = "UTC") Instant startAt,
        @Schema(description = "UTC") Instant endAt,
        @Schema(description = "Start in the provider's time zone") OffsetDateTime localStartAt,
        OffsetDateTime localEndAt,
        String timeZone,
        ServiceSummary service,
        ProviderSummary provider,
        CustomerSummary customer,
        BigDecimal price,
        String notes,
        String cancellationReason,
        Role cancelledBy,
        int rescheduleCount,
        Instant createdAt,
        Instant updatedAt) {

    public record ServiceSummary(UUID id, String name, int durationMinutes) {
    }

    public record ProviderSummary(UUID id, String businessName) {
    }

    /** Contact details of the customer; only ever shown to the customer, their provider and admins. */
    public record CustomerSummary(UUID id, String fullName, String email) {
    }

    public static AppointmentResponse from(Appointment a) {
        ZoneId zone = a.getProvider().zoneId();
        return new AppointmentResponse(
                a.getId(), a.getStatus(), a.getStartAt(), a.getEndAt(),
                a.getStartAt().atZone(zone).toOffsetDateTime(), a.getEndAt().atZone(zone).toOffsetDateTime(),
                zone.getId(),
                new ServiceSummary(a.getService().getId(), a.getService().getName(), (int) a.duration().toMinutes()),
                new ProviderSummary(a.getProvider().getId(), a.getProvider().getBusinessName()),
                new CustomerSummary(a.getCustomer().getId(), a.getCustomer().getFullName(), a.getCustomer().getEmail()),
                a.getPrice(), a.getCustomerNotes(), a.getCancellationReason(), a.getCancelledBy(),
                a.getRescheduleCount(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
