package com.slotwise.appointment.domain;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

public final class AppointmentSpecifications {

    private AppointmentSpecifications() {
    }

    public static Specification<Appointment> customer(UUID customerId) {
        return (root, query, cb) -> customerId == null ? null : cb.equal(root.get("customer").get("id"), customerId);
    }

    public static Specification<Appointment> provider(UUID providerId) {
        return (root, query, cb) -> providerId == null ? null : cb.equal(root.get("provider").get("id"), providerId);
    }

    public static Specification<Appointment> status(AppointmentStatus status) {
        return (root, query, cb) -> status == null ? null : cb.equal(root.get("status"), status);
    }

    public static Specification<Appointment> startsAtOrAfter(Instant from) {
        return (root, query, cb) -> from == null ? null : cb.greaterThanOrEqualTo(root.get("startAt"), from);
    }

    public static Specification<Appointment> startsBefore(Instant to) {
        return (root, query, cb) -> to == null ? null : cb.lessThan(root.get("startAt"), to);
    }
}
