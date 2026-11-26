package com.slotwise.appointment;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentEvent;
import com.slotwise.appointment.domain.AppointmentRepository;
import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.appointment.service.AppointmentService;
import com.slotwise.auth.security.AuthenticatedUser;
import com.slotwise.availability.service.AvailabilityService;
import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.catalog.service.ServiceOfferingService;
import com.slotwise.common.config.BookingProperties;
import com.slotwise.common.exception.BusinessRuleException;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.ForbiddenException;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.provider.service.ProviderService;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import com.slotwise.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentServiceTest {

    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final Instant START = Instant.parse("2030-01-10T15:00:00Z");

    @Mock
    AppointmentRepository repository;
    @Mock
    ProviderService providerService;
    @Mock
    ServiceOfferingService serviceOfferingService;
    @Mock
    AvailabilityService availabilityService;
    @Mock
    UserService userService;
    @Mock
    ApplicationEventPublisher events;

    AppointmentService service;
    User providerUser;
    User customer;
    ProviderProfile provider;
    ServiceOffering offering;

    @BeforeEach
    void setUp() {
        BookingProperties policy = new BookingProperties(Duration.ofHours(1), Duration.ofDays(90),
                Duration.ofHours(24), Duration.ofHours(24), 2, 31);
        service = new AppointmentService(repository, providerService, serviceOfferingService, availabilityService,
                userService, policy, events, Clock.fixed(NOW, ZoneOffset.UTC));
        providerUser = withId(new User("p@example.com", "h", "P", null, Role.PROVIDER));
        customer = withId(new User("c@example.com", "h", "C", null, Role.CUSTOMER));
        provider = withId(new ProviderProfile(providerUser, "Biz", "UTC"));
        offering = withId(new ServiceOffering(provider, "Cut", null, 30, 5, BigDecimal.TEN));
    }

    @Test
    void bookingLocksProviderBeforeCheckingAvailabilityAndInserting() {
        when(serviceOfferingService.getPublic(offering.getId())).thenReturn(offering);
        when(providerService.lockForScheduling(provider.getId())).thenReturn(provider);
        when(userService.getById(customer.getId())).thenReturn(customer);
        when(repository.saveAndFlush(any(Appointment.class))).thenAnswer(inv -> withId(inv.getArgument(0)));

        Appointment booked = service.book(customer.getId(), offering.getId(), START, "  ");

        InOrder order = inOrder(providerService, availabilityService, repository);
        order.verify(providerService).lockForScheduling(provider.getId());
        order.verify(availabilityService).assertSlotBookable(provider, Duration.ofMinutes(30), Duration.ofMinutes(5),
                START, null);
        order.verify(repository).saveAndFlush(any(Appointment.class));
        assertThat(booked.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(booked.getCustomerNotes()).isNull();

        ArgumentCaptor<AppointmentEvent> event = ArgumentCaptor.forClass(AppointmentEvent.class);
        verify(events).publishEvent(event.capture());
        assertThat(event.getValue().type()).isEqualTo(AppointmentEvent.Type.CREATED);
    }

    @Test
    void bookingAbortsWithoutInsertWhenSlotIsTaken() {
        when(serviceOfferingService.getPublic(offering.getId())).thenReturn(offering);
        when(providerService.lockForScheduling(provider.getId())).thenReturn(provider);
        when(userService.getById(customer.getId())).thenReturn(customer);
        doThrow(new ConflictException("SLOT_UNAVAILABLE", "taken")).when(availabilityService)
                .assertSlotBookable(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.book(customer.getId(), offering.getId(), START, null))
                .isInstanceOf(ConflictException.class);
        verify(repository, never()).saveAndFlush(any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void customerCannotCancelInsideCutoff() {
        Appointment appointment = appointmentStartingAt(NOW.plus(Duration.ofHours(23)));

        assertThatThrownBy(() -> service.cancel(customerActor(), appointment.getId(), null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("CANCELLATION_WINDOW_CLOSED");
    }

    @Test
    void providerMayCancelInsideCustomerCutoff() {
        Appointment appointment = appointmentStartingAt(NOW.plus(Duration.ofHours(2)));

        service.cancel(new AuthenticatedUser(providerUser.getId(), Role.PROVIDER), appointment.getId(), "Ill");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointment.getCancelledBy()).isEqualTo(Role.PROVIDER);
    }

    @Test
    void startedAppointmentsCannotBeCancelled() {
        Appointment appointment = appointmentStartingAt(NOW.minus(Duration.ofMinutes(5)));

        assertThatThrownBy(() -> service.cancel(new AuthenticatedUser(UUID.randomUUID(), Role.ADMIN),
                appointment.getId(), null))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("APPOINTMENT_ALREADY_STARTED");
    }

    @Test
    void otherCustomersAndProvidersAreForbidden() {
        Appointment appointment = appointmentStartingAt(START);

        assertThatThrownBy(() -> service.cancel(new AuthenticatedUser(UUID.randomUUID(), Role.CUSTOMER),
                appointment.getId(), null)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.confirm(UUID.randomUUID(), appointment.getId()))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.getVisibleTo(new AuthenticatedUser(UUID.randomUUID(), Role.PROVIDER),
                appointment.getId())).isInstanceOf(ForbiddenException.class);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING);
    }

    @Test
    void rescheduleLimitIsEnforced() {
        Appointment appointment = appointmentStartingAt(START);
        ReflectionTestUtils.setField(appointment, "rescheduleCount", 2);

        assertThatThrownBy(() -> service.reschedule(customer.getId(), appointment.getId(), START.plusSeconds(3600)))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("RESCHEDULE_LIMIT_REACHED");
        verify(providerService, never()).lockForScheduling(any());
    }

    @Test
    void rescheduleExcludesItselfFromConflictCheck() {
        Appointment appointment = appointmentStartingAt(START);
        when(providerService.lockForScheduling(provider.getId())).thenReturn(provider);
        when(repository.saveAndFlush(appointment)).thenReturn(appointment);
        Instant newStart = START.plus(Duration.ofMinutes(15));

        service.reschedule(customer.getId(), appointment.getId(), newStart);

        verify(availabilityService).assertSlotBookable(eq(provider), eq(Duration.ofMinutes(30)),
                eq(Duration.ofMinutes(5)), eq(newStart), eq(appointment.getId()));
        assertThat(appointment.getStartAt()).isEqualTo(newStart);
    }

    @Test
    void completeRequiresAppointmentToHaveStarted() {
        Appointment appointment = appointmentStartingAt(START);
        appointment.confirm();

        assertThatThrownBy(() -> service.complete(providerUser.getId(), appointment.getId()))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("APPOINTMENT_NOT_STARTED");
    }

    private AuthenticatedUser customerActor() {
        return new AuthenticatedUser(customer.getId(), Role.CUSTOMER);
    }

    private Appointment appointmentStartingAt(Instant start) {
        Appointment appointment = withId(new Appointment(provider, customer, offering, start, null));
        when(repository.findDetailedById(appointment.getId())).thenReturn(Optional.of(appointment));
        return appointment;
    }

    private static <T> T withId(T entity) {
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }
}
