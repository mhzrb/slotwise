package com.slotwise.appointment;

import com.slotwise.appointment.domain.AppointmentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static com.slotwise.appointment.domain.AppointmentStatus.CANCELLED;
import static com.slotwise.appointment.domain.AppointmentStatus.COMPLETED;
import static com.slotwise.appointment.domain.AppointmentStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

class AppointmentStatusTest {

    /** The complete transition table: every (from, to) pair is listed exactly once. */
    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
            "PENDING,   PENDING,   false",
            "PENDING,   CONFIRMED, true",
            "PENDING,   CANCELLED, true",
            "PENDING,   COMPLETED, false",
            "PENDING,   REJECTED,  true",
            "CONFIRMED, PENDING,   false",
            "CONFIRMED, CONFIRMED, false",
            "CONFIRMED, CANCELLED, true",
            "CONFIRMED, COMPLETED, true",
            "CONFIRMED, REJECTED,  false",
            "CANCELLED, PENDING,   false",
            "CANCELLED, CONFIRMED, false",
            "CANCELLED, CANCELLED, false",
            "CANCELLED, COMPLETED, false",
            "CANCELLED, REJECTED,  false",
            "COMPLETED, PENDING,   false",
            "COMPLETED, CONFIRMED, false",
            "COMPLETED, CANCELLED, false",
            "COMPLETED, COMPLETED, false",
            "COMPLETED, REJECTED,  false",
            "REJECTED,  PENDING,   false",
            "REJECTED,  CONFIRMED, false",
            "REJECTED,  CANCELLED, false",
            "REJECTED,  COMPLETED, false",
            "REJECTED,  REJECTED,  false"
    })
    void transitionTable(AppointmentStatus from, AppointmentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"CANCELLED", "COMPLETED", "REJECTED"})
    void terminalStatusesAreInactive(AppointmentStatus status) {
        assertThat(status.isTerminal()).isTrue();
        assertThat(status.isActive()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = AppointmentStatus.class, names = {"PENDING", "CONFIRMED"})
    void activeStatusesBlockTheCalendar(AppointmentStatus status) {
        assertThat(status.isActive()).isTrue();
        assertThat(AppointmentStatus.ACTIVE).doesNotContain(CANCELLED, COMPLETED, REJECTED);
    }
}
