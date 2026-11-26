package com.slotwise.appointment.domain;

import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.common.domain.BaseEntity;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Aggregate root for a booking. All state changes go through methods that
 * enforce the {@link AppointmentStatus} state machine; there are no setters.
 * {@code @Version} turns concurrent state changes (e.g. confirm vs. cancel)
 * into an optimistic-locking failure instead of a lost update.
 */
@Entity
@Table(name = "appointments")
public class Appointment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    private ProviderProfile provider;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private User customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceOffering service;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /** endAt + buffer: the provider is unavailable until this instant. */
    @Column(name = "blocked_until", nullable = false)
    private Instant blockedUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AppointmentStatus status;

    /** Price at booking time; later price changes of the service do not affect it. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(name = "customer_notes", length = 1000)
    private String customerNotes;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancelled_by", length = 20)
    private Role cancelledBy;

    @Column(name = "reschedule_count", nullable = false)
    private int rescheduleCount;

    @Version
    private long version;

    protected Appointment() {
    }

    public Appointment(ProviderProfile provider, User customer, ServiceOffering service, Instant startAt,
            String customerNotes) {
        this.provider = provider;
        this.customer = customer;
        this.service = service;
        this.price = service.getPrice();
        this.customerNotes = customerNotes;
        this.status = AppointmentStatus.PENDING;
        setTimes(startAt, service.duration(), service.buffer());
    }

    public void confirm() {
        transitionTo(AppointmentStatus.CONFIRMED);
    }

    public void reject(String reason) {
        transitionTo(AppointmentStatus.REJECTED);
        this.cancellationReason = reason;
    }

    public void cancel(Role cancelledBy, String reason) {
        transitionTo(AppointmentStatus.CANCELLED);
        this.cancelledBy = cancelledBy;
        this.cancellationReason = reason;
    }

    public void complete() {
        transitionTo(AppointmentStatus.COMPLETED);
    }

    /**
     * Moves the appointment, keeping its original duration and buffer. A
     * confirmed appointment returns to PENDING so the provider re-confirms
     * the new time.
     */
    public void reschedule(Instant newStart) {
        if (!status.isActive()) {
            throw new InvalidStatusTransitionException(status, AppointmentStatus.PENDING);
        }
        setTimes(newStart, duration(), buffer());
        this.status = AppointmentStatus.PENDING;
        this.rescheduleCount++;
    }

    public Duration duration() {
        return Duration.between(startAt, endAt);
    }

    public Duration buffer() {
        return Duration.between(endAt, blockedUntil);
    }

    public boolean hasStarted(Instant now) {
        return !now.isBefore(startAt);
    }

    private void transitionTo(AppointmentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        this.status = target;
    }

    private void setTimes(Instant start, Duration duration, Duration buffer) {
        this.startAt = start;
        this.endAt = start.plus(duration);
        this.blockedUntil = endAt.plus(buffer);
    }

    public ProviderProfile getProvider() {
        return provider;
    }

    public User getCustomer() {
        return customer;
    }

    public ServiceOffering getService() {
        return service;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }

    public Instant getBlockedUntil() {
        return blockedUntil;
    }

    public AppointmentStatus getStatus() {
        return status;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public String getCustomerNotes() {
        return customerNotes;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Role getCancelledBy() {
        return cancelledBy;
    }

    public int getRescheduleCount() {
        return rescheduleCount;
    }
}
