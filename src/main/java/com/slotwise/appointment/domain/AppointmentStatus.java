package com.slotwise.appointment.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Appointment lifecycle.
 *
 * <pre>
 *            confirm             complete
 *  PENDING ----------> CONFIRMED ---------> COMPLETED
 *     |  \                 |
 *     |   \ reject         | cancel
 *     |    `--> REJECTED   v
 *     `---- cancel ---> CANCELLED
 * </pre>
 *
 * CANCELLED, COMPLETED and REJECTED are terminal. Rescheduling keeps the
 * appointment active and moves it back to PENDING.
 */
public enum AppointmentStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    COMPLETED,
    REJECTED;

    /** Statuses that occupy the provider's calendar (mirrors the DB exclusion constraint predicate). */
    public static final Set<AppointmentStatus> ACTIVE = EnumSet.of(PENDING, CONFIRMED);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    public boolean canTransitionTo(AppointmentStatus target) {
        return allowedTransitions().contains(target);
    }

    public Set<AppointmentStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, REJECTED, CANCELLED);
            case CONFIRMED -> EnumSet.of(CANCELLED, COMPLETED);
            case CANCELLED, COMPLETED, REJECTED -> EnumSet.noneOf(AppointmentStatus.class);
        };
    }
}
