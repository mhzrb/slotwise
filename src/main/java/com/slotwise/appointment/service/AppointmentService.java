package com.slotwise.appointment.service;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentEvent;
import com.slotwise.appointment.domain.AppointmentRepository;
import com.slotwise.appointment.domain.AppointmentSpecifications;
import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.availability.service.AvailabilityService;
import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.catalog.service.ServiceOfferingService;
import com.slotwise.common.config.BookingProperties;
import com.slotwise.common.exception.BusinessRuleException;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.ForbiddenException;
import com.slotwise.common.exception.NotFoundException;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.service.ProviderService;
import com.slotwise.user.domain.User;
import com.slotwise.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Appointment use cases.
 *
 * <h2>Concurrency</h2>
 * Every operation that places an appointment on the calendar (book,
 * reschedule) runs in one READ COMMITTED transaction that:
 * <ol>
 *   <li>locks the provider row ({@code SELECT ... FOR UPDATE}) - concurrent
 *       bookings for the same provider queue up here, bookings for different
 *       providers proceed in parallel;</li>
 *   <li>re-validates the slot against the current committed state (each
 *       READ COMMITTED statement sees rows committed before it started, so
 *       the second of two racing requests sees the first one's appointment);</li>
 *   <li>inserts/updates and flushes, so that the PostgreSQL exclusion
 *       constraint {@code ex_appointments_no_overlap} is checked inside the
 *       transaction as a last line of defence.</li>
 * </ol>
 * Status changes (confirm, cancel, ...) do not move time ranges and are
 * protected by optimistic locking ({@code @Version}) instead.
 */
@Service
@Transactional(readOnly = true)
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final String PG_EXCLUSION_VIOLATION = "23P01";
    static final EnumSet<AppointmentStatus> SCHEDULE_STATUSES =
            EnumSet.of(AppointmentStatus.PENDING, AppointmentStatus.CONFIRMED, AppointmentStatus.COMPLETED);

    private final AppointmentRepository repository;
    private final ProviderService providerService;
    private final ServiceOfferingService serviceOfferingService;
    private final AvailabilityService availabilityService;
    private final UserService userService;
    private final BookingProperties policy;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public AppointmentService(AppointmentRepository repository, ProviderService providerService,
            ServiceOfferingService serviceOfferingService, AvailabilityService availabilityService,
            UserService userService, BookingProperties policy, ApplicationEventPublisher events, Clock clock) {
        this.repository = repository;
        this.providerService = providerService;
        this.serviceOfferingService = serviceOfferingService;
        this.availabilityService = availabilityService;
        this.userService = userService;
        this.policy = policy;
        this.events = events;
        this.clock = clock;
    }

    // ================================================================== booking

    @Transactional
    public Appointment book(UUID customerId, UUID serviceId, Instant startAt, String notes) {
        ServiceOffering service = serviceOfferingService.getPublic(serviceId);
        // (1) serialisation point for this provider's calendar
        ProviderProfile provider = providerService.lockForScheduling(service.getProvider().getId());
        if (!provider.isActive()) {
            throw new BusinessRuleException("PROVIDER_UNAVAILABLE", "This provider is not accepting bookings");
        }
        User customer = userService.getById(customerId);

        // (2) authoritative availability check, under the lock
        availabilityService.assertSlotBookable(provider, service.duration(), service.buffer(), startAt, null);

        // (3) insert + flush so the exclusion constraint is evaluated now
        Appointment appointment = saveAndFlushGuarded(new Appointment(provider, customer, service, startAt,
                blankToNull(notes)));
        events.publishEvent(AppointmentEvent.of(AppointmentEvent.Type.CREATED, appointment));
        log.info("Appointment {} booked: provider={} service={} start={}", appointment.getId(), provider.getId(),
                service.getId(), startAt);
        return appointment;
    }

    @Transactional
    public Appointment reschedule(UUID customerId, UUID appointmentId, Instant newStart) {
        Appointment appointment = getDetailed(appointmentId);
        requireCustomer(appointment, customerId);
        if (!appointment.getStatus().isActive()) {
            throw new BusinessRuleException("APPOINTMENT_NOT_ACTIVE", "Only pending or confirmed appointments can "
                    + "be rescheduled");
        }
        Instant now = clock.instant();
        if (now.isAfter(appointment.getStartAt().minus(policy.customerRescheduleCutoff()))) {
            throw new BusinessRuleException("RESCHEDULE_WINDOW_CLOSED", "Appointments can be rescheduled up to "
                    + policy.customerRescheduleCutoff().toHours() + " hours before the start time");
        }
        if (appointment.getRescheduleCount() >= policy.maxReschedules()) {
            throw new BusinessRuleException("RESCHEDULE_LIMIT_REACHED", "This appointment has already been "
                    + "rescheduled the maximum number of times (" + policy.maxReschedules() + ")");
        }
        if (appointment.getStartAt().equals(newStart)) {
            throw new BusinessRuleException("SAME_START_TIME", "The new start time equals the current one");
        }

        ProviderProfile provider = providerService.lockForScheduling(appointment.getProvider().getId());
        // the appointment itself is excluded so it may move into a range overlapping its old one
        availabilityService.assertSlotBookable(provider, appointment.duration(), appointment.buffer(), newStart,
                appointment.getId());

        Instant previousStart = appointment.getStartAt();
        appointment.reschedule(newStart);
        saveAndFlushGuarded(appointment);
        events.publishEvent(new AppointmentEvent(AppointmentEvent.Type.RESCHEDULED, appointment.getId(),
                previousStart));
        log.info("Appointment {} rescheduled from {} to {}", appointmentId, previousStart, newStart);
        return appointment;
    }

    // ================================================================== lifecycle

    @Transactional
    public Appointment cancel(AuthenticatedUser actor, UUID appointmentId, String reason) {
        Appointment appointment = getDetailed(appointmentId);
        Instant now = clock.instant();
        switch (actor.role()) {
            case CUSTOMER -> {
                requireCustomer(appointment, actor.id());
                if (now.isAfter(appointment.getStartAt().minus(policy.customerCancellationCutoff()))) {
                    throw new BusinessRuleException("CANCELLATION_WINDOW_CLOSED", "Appointments can be cancelled up "
                            + "to " + policy.customerCancellationCutoff().toHours() + " hours before the start time");
                }
            }
            case PROVIDER -> requireProvider(appointment, actor.id());
            case ADMIN -> {
                // administrators may cancel any appointment
            }
        }
        if (appointment.hasStarted(now) && appointment.getStatus().isActive()) {
            throw new BusinessRuleException("APPOINTMENT_ALREADY_STARTED", "Appointments that have already started "
                    + "cannot be cancelled");
        }
        appointment.cancel(actor.role(), blankToNull(reason));
        events.publishEvent(AppointmentEvent.of(AppointmentEvent.Type.CANCELLED, appointment));
        log.info("Appointment {} cancelled by {}", appointmentId, actor.role());
        return appointment;
    }

    @Transactional
    public Appointment confirm(UUID providerUserId, UUID appointmentId) {
        Appointment appointment = getDetailed(appointmentId);
        requireProvider(appointment, providerUserId);
        if (appointment.hasStarted(clock.instant()) && appointment.getStatus() == AppointmentStatus.PENDING) {
            throw new BusinessRuleException("APPOINTMENT_ALREADY_STARTED", "Past appointments cannot be confirmed");
        }
        appointment.confirm();
        events.publishEvent(AppointmentEvent.of(AppointmentEvent.Type.CONFIRMED, appointment));
        return appointment;
    }

    @Transactional
    public Appointment reject(UUID providerUserId, UUID appointmentId, String reason) {
        Appointment appointment = getDetailed(appointmentId);
        requireProvider(appointment, providerUserId);
        appointment.reject(blankToNull(reason));
        events.publishEvent(AppointmentEvent.of(AppointmentEvent.Type.REJECTED, appointment));
        return appointment;
    }

    @Transactional
    public Appointment complete(UUID providerUserId, UUID appointmentId) {
        Appointment appointment = getDetailed(appointmentId);
        requireProvider(appointment, providerUserId);
        if (!appointment.hasStarted(clock.instant()) && appointment.getStatus() == AppointmentStatus.CONFIRMED) {
            throw new BusinessRuleException("APPOINTMENT_NOT_STARTED",
                    "Appointments can only be completed once they have started");
        }
        appointment.complete();
        events.publishEvent(AppointmentEvent.of(AppointmentEvent.Type.COMPLETED, appointment));
        return appointment;
    }

    // ================================================================== queries

    /** Visible to the customer who booked it, the provider it is booked with, and admins. */
    public Appointment getVisibleTo(AuthenticatedUser user, UUID appointmentId) {
        Appointment appointment = getDetailed(appointmentId);
        boolean allowed = switch (user.role()) {
            case ADMIN -> true;
            case CUSTOMER -> appointment.getCustomer().getId().equals(user.id());
            case PROVIDER -> appointment.getProvider().getUser().getId().equals(user.id());
        };
        if (!allowed) {
            throw new ForbiddenException("You do not have access to this appointment");
        }
        return appointment;
    }

    public Page<Appointment> listForCustomer(UUID customerId, AppointmentStatus status, Instant from, Instant to,
            Pageable pageable) {
        return repository.findAll(Specification.allOf(
                AppointmentSpecifications.customer(customerId),
                AppointmentSpecifications.status(status),
                AppointmentSpecifications.startsAtOrAfter(from),
                AppointmentSpecifications.startsBefore(to)), pageable);
    }

    public Page<Appointment> listForProvider(UUID providerUserId, AppointmentStatus status, Instant from, Instant to,
            Pageable pageable) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        return repository.findAll(Specification.allOf(
                AppointmentSpecifications.provider(provider.getId()),
                AppointmentSpecifications.status(status),
                AppointmentSpecifications.startsAtOrAfter(from),
                AppointmentSpecifications.startsBefore(to)), pageable);
    }

    public Page<Appointment> listAll(UUID providerId, UUID customerId, AppointmentStatus status, Instant from,
            Instant to, Pageable pageable) {
        return repository.findAll(Specification.allOf(
                AppointmentSpecifications.provider(providerId),
                AppointmentSpecifications.customer(customerId),
                AppointmentSpecifications.status(status),
                AppointmentSpecifications.startsAtOrAfter(from),
                AppointmentSpecifications.startsBefore(to)), pageable);
    }

    public enum ScheduleView { DAY, WEEK }

    public record Schedule(ProviderProfile provider, ScheduleView view, LocalDate from, LocalDate to,
            List<Appointment> appointments) {
    }

    /**
     * Daily or weekly (Monday-Sunday) agenda in the provider's time zone,
     * excluding cancelled and rejected appointments.
     */
    public Schedule providerSchedule(UUID providerUserId, LocalDate date, ScheduleView view) {
        ProviderProfile provider = providerService.getByUserId(providerUserId);
        ZoneId zone = provider.zoneId();
        LocalDate anchor = date != null ? date : LocalDate.now(clock.withZone(zone));
        LocalDate from = view == ScheduleView.WEEK
                ? anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                : anchor;
        LocalDate to = view == ScheduleView.WEEK ? from.plusDays(6) : from;
        List<Appointment> appointments = repository
                .findByProviderIdAndStartAtGreaterThanEqualAndStartAtLessThanAndStatusInOrderByStartAtAsc(
                        provider.getId(), from.atStartOfDay(zone).toInstant(),
                        to.plusDays(1).atStartOfDay(zone).toInstant(), SCHEDULE_STATUSES);
        return new Schedule(provider, view, from, to, appointments);
    }

    // ================================================================== helpers

    private Appointment getDetailed(UUID appointmentId) {
        return repository.findDetailedById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment", appointmentId));
    }

    private static void requireCustomer(Appointment appointment, UUID customerId) {
        if (!appointment.getCustomer().getId().equals(customerId)) {
            throw new ForbiddenException("You can only manage your own appointments");
        }
    }

    private static void requireProvider(Appointment appointment, UUID providerUserId) {
        if (!appointment.getProvider().getUser().getId().equals(providerUserId)) {
            throw new ForbiddenException("You can only manage appointments booked with you");
        }
    }

    /**
     * Flushes immediately and translates an exclusion-constraint violation
     * into a 409. With the provider lock in place this should never trigger;
     * if it does, the database has prevented a double booking that the
     * application layer missed.
     */
    private Appointment saveAndFlushGuarded(Appointment appointment) {
        try {
            return repository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException ex) {
            if (PG_EXCLUSION_VIOLATION.equals(sqlState(ex))) {
                log.warn("Exclusion constraint prevented an overlapping appointment for provider {}",
                        appointment.getProvider().getId());
                throw new ConflictException("SLOT_UNAVAILABLE", "The requested time slot is no longer available");
            }
            throw ex;
        }
    }

    private static String sqlState(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
