package com.slotwise.appointment;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentStatus;
import com.slotwise.appointment.domain.InvalidStatusTransitionException;
import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentTest {

    private static final Instant START = Instant.parse("2030-01-07T15:00:00Z");

    @Test
    void newAppointmentIsPendingWithBufferedOccupiedRangeAndPriceSnapshot() {
        Appointment appointment = newAppointment();

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(appointment.getEndAt()).isEqualTo(START.plus(Duration.ofMinutes(45)));
        assertThat(appointment.getBlockedUntil()).isEqualTo(START.plus(Duration.ofMinutes(55)));
        assertThat(appointment.getPrice()).isEqualByComparingTo("60.00");
    }

    @Test
    void rescheduleKeepsDurationAndBufferAndRequiresReconfirmation() {
        Appointment appointment = newAppointment();
        appointment.confirm();
        Instant newStart = START.plus(Duration.ofDays(1));

        appointment.reschedule(newStart);

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.PENDING);
        assertThat(appointment.getStartAt()).isEqualTo(newStart);
        assertThat(appointment.duration()).isEqualTo(Duration.ofMinutes(45));
        assertThat(appointment.buffer()).isEqualTo(Duration.ofMinutes(10));
        assertThat(appointment.getRescheduleCount()).isEqualTo(1);
    }

    @Test
    void cancelRecordsWhoAndWhy() {
        Appointment appointment = newAppointment();
        appointment.cancel(Role.CUSTOMER, "Sick");

        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(appointment.getCancelledBy()).isEqualTo(Role.CUSTOMER);
        assertThat(appointment.getCancellationReason()).isEqualTo("Sick");
    }

    @Test
    void invalidTransitionsAreRejected() {
        Appointment appointment = newAppointment();
        assertThatThrownBy(appointment::complete).isInstanceOf(InvalidStatusTransitionException.class);

        appointment.reject("Fully booked");
        assertThatThrownBy(appointment::confirm).isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> appointment.cancel(Role.CUSTOMER, null))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> appointment.reschedule(START.plusSeconds(3600)))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThat(appointment.getStatus()).isEqualTo(AppointmentStatus.REJECTED);
    }

    @Test
    void hasStartedIsInclusiveOfStartInstant() {
        Appointment appointment = newAppointment();
        assertThat(appointment.hasStarted(START.minusMillis(1))).isFalse();
        assertThat(appointment.hasStarted(START)).isTrue();
    }

    static Appointment newAppointment() {
        User providerUser = new User("p@example.com", "h", "P", null, Role.PROVIDER);
        ProviderProfile provider = new ProviderProfile(providerUser, "Biz", "America/Toronto");
        ServiceOffering service = new ServiceOffering(provider, "Facial", null, 45, 10, new BigDecimal("60.00"));
        User customer = new User("c@example.com", "h", "C", null, Role.CUSTOMER);
        return new Appointment(provider, customer, service, START, null);
    }
}
