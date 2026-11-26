package com.slotwise.notification.domain;

public enum NotificationType {
    /** To the customer: their booking request was received. */
    APPOINTMENT_CREATED,
    /** To the provider: a customer requested a booking. */
    APPOINTMENT_REQUEST_RECEIVED,
    APPOINTMENT_CONFIRMED,
    APPOINTMENT_REJECTED,
    APPOINTMENT_CANCELLED,
    APPOINTMENT_RESCHEDULED,
    APPOINTMENT_COMPLETED
}
