package com.slotwise.appointment.service;

import com.slotwise.appointment.domain.AppointmentRepository;
import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.availability.engine.Interval;
import com.slotwise.availability.service.BusyTimeProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Adapts active appointments to the availability module's {@link BusyTimeProvider} port. */
@Component
class AppointmentBusyTimeProvider implements BusyTimeProvider {

    private final AppointmentRepository repository;

    AppointmentBusyTimeProvider(AppointmentRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Interval> findBusyIntervals(UUID providerId, Instant from, Instant to, UUID excludeAppointmentId) {
        UUID exclude = excludeAppointmentId != null ? excludeAppointmentId : AppointmentRepository.NO_EXCLUSION;
        return repository.findOverlapping(providerId, from, to, AppointmentStatus.ACTIVE, exclude).stream()
                .map(a -> new Interval(a.getStartAt(), a.getBlockedUntil()))
                .toList();
    }
}
