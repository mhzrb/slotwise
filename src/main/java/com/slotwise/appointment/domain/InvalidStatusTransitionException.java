package com.slotwise.appointment.domain;

import com.slotwise.common.exception.ConflictException;

public class InvalidStatusTransitionException extends ConflictException {

    public InvalidStatusTransitionException(AppointmentStatus from, AppointmentStatus to) {
        super("INVALID_STATUS_TRANSITION", "Cannot change appointment status from " + from + " to " + to);
    }
}
