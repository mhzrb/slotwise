package com.slotwise.notification;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentEvent;
import com.slotwise.catalog.domain.ServiceOffering;
import com.slotwise.notification.domain.NotificationType;
import com.slotwise.notification.service.NotificationComposer;
import com.slotwise.notification.service.NotificationComposer.Draft;
import com.slotwise.provider.domain.ProviderProfile;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class NotificationComposerTest {

    private final NotificationComposer composer = new NotificationComposer();
    private User customer;
    private User providerUser;
    private Appointment appointment;

    @BeforeEach
    void setUp() {
        customer = new User("cust@example.com", "h", "Carla", null, Role.CUSTOMER);
        providerUser = new User("prov@example.com", "h", "Pete", null, Role.PROVIDER);
        ProviderProfile provider = new ProviderProfile(providerUser, "Pete's Physio", "America/Toronto");
        ServiceOffering service = new ServiceOffering(provider, "Assessment", null, 45, 0, BigDecimal.TEN);
        appointment = new Appointment(provider, customer, service, Instant.parse("2030-01-07T15:00:00Z"), null);
        ReflectionTestUtils.setField(appointment, "id", UUID.randomUUID());
    }

    @Test
    void creationNotifiesCustomerAndProvider() {
        List<Draft> drafts = composer.compose(event(AppointmentEvent.Type.CREATED), appointment);

        assertThat(drafts).extracting(Draft::recipient, Draft::type).containsExactly(
                tuple(customer, NotificationType.APPOINTMENT_CREATED),
                tuple(providerUser, NotificationType.APPOINTMENT_REQUEST_RECEIVED));
        // rendered in the provider's zone: 15:00Z = 10:00 EST
        assertThat(drafts.getFirst().body()).contains("Mon 7 Jan 2030 at 10:00 (America/Toronto)", "Pete's Physio");
        assertThat(drafts.get(1).body()).contains("Carla requested Assessment");
    }

    @Test
    void confirmationRejectionAndCompletionNotifyOnlyTheCustomer() {
        for (AppointmentEvent.Type type : List.of(AppointmentEvent.Type.CONFIRMED, AppointmentEvent.Type.REJECTED,
                AppointmentEvent.Type.COMPLETED)) {
            assertThat(composer.compose(event(type), appointment)).extracting(Draft::recipient)
                    .containsExactly(customer);
        }
    }

    @Test
    void cancellationNotifiesTheOtherParty() {
        appointment.cancel(Role.CUSTOMER, "Flu");
        assertThat(composer.compose(event(AppointmentEvent.Type.CANCELLED), appointment))
                .extracting(Draft::recipient).containsExactly(providerUser);

        Appointment byProvider = freshAppointment();
        byProvider.cancel(Role.PROVIDER, null);
        assertThat(composer.compose(event(AppointmentEvent.Type.CANCELLED), byProvider))
                .extracting(Draft::recipient).containsExactly(customer);

        Appointment byAdmin = freshAppointment();
        byAdmin.cancel(Role.ADMIN, null);
        assertThat(composer.compose(event(AppointmentEvent.Type.CANCELLED), byAdmin))
                .extracting(Draft::recipient).containsExactly(customer, providerUser);
    }

    @Test
    void cancellationIncludesReason() {
        appointment.cancel(Role.CUSTOMER, "Flu");
        assertThat(composer.compose(event(AppointmentEvent.Type.CANCELLED), appointment).getFirst().body())
                .contains("cancelled by the customer", "Reason: Flu");
    }

    @Test
    void rescheduleNotifiesBothWithOldAndNewTime() {
        Instant previous = appointment.getStartAt();
        appointment.reschedule(Instant.parse("2030-01-08T16:00:00Z"));

        List<Draft> drafts = composer.compose(
                new AppointmentEvent(AppointmentEvent.Type.RESCHEDULED, appointment.getId(), previous), appointment);

        assertThat(drafts).extracting(Draft::recipient).containsExactly(customer, providerUser);
        assertThat(drafts).allSatisfy(d -> assertThat(d.body())
                .contains("from Mon 7 Jan 2030 at 10:00", "to Tue 8 Jan 2030 at 11:00"));
    }

    private Appointment freshAppointment() {
        Appointment copy = new Appointment(appointment.getProvider(), customer, appointment.getService(),
                appointment.getStartAt(), null);
        ReflectionTestUtils.setField(copy, "id", UUID.randomUUID());
        return copy;
    }

    private AppointmentEvent event(AppointmentEvent.Type type) {
        return new AppointmentEvent(type, appointment.getId(), null);
    }
}
