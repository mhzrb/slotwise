package com.slotwise.appointment.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published inside the appointment transaction. Consumers (the
 * notification module) must not be able to break the booking: see
 * {@code NotificationEventListener}.
 */
public record AppointmentEvent(Type type, UUID appointmentId, Instant previousStartAt) {

    public enum Type {
        CREATED,
        CONFIRMED,
        REJECTED,
        CANCELLED,
        RESCHEDULED,
        COMPLETED
    }

    public static AppointmentEvent of(Type type, Appointment appointment) {
        return new AppointmentEvent(type, appointment.getId(), null);
    }
}
