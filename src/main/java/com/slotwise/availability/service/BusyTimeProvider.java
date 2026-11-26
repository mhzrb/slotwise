package com.slotwise.availability.service;

import com.slotwise.availability.engine.Interval;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port through which the availability module learns which time is already
 * taken, without depending on the appointment module (that would be a
 * dependency cycle, since appointments depend on availability). Implemented
 * by the appointment module; further sources such as external calendar sync
 * could be added as additional implementations.
 */
public interface BusyTimeProvider {

    /**
     * @param excludeAppointmentId appointment to ignore (the one being rescheduled), may be null
     * @return occupied intervals {@code [start, blockedUntil)} overlapping {@code [from, to)}
     */
    List<Interval> findBusyIntervals(UUID providerId, Instant from, Instant to, UUID excludeAppointmentId);
}
