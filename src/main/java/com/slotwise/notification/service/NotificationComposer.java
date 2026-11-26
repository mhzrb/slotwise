package com.slotwise.notification.service;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentEvent;
import com.slotwise.notification.domain.NotificationType;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides who is notified about what, and renders the message. Pure logic
 * (no I/O), so recipient rules are unit-tested in isolation. Times are shown
 * in the provider's time zone, where the appointment takes place.
 */
@Component
public class NotificationComposer {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy 'at' HH:mm", Locale.ENGLISH);

    public record Draft(User recipient, NotificationType type, String subject, String body) {
    }

    public List<Draft> compose(AppointmentEvent event, Appointment a) {
        User customer = a.getCustomer();
        User providerUser = a.getProvider().getUser();
        String when = format(a.getStartAt(), a.getProvider().zoneId());
        String service = a.getService().getName();
        String business = a.getProvider().getBusinessName();
        List<Draft> drafts = new ArrayList<>();

        switch (event.type()) {
            case CREATED -> {
                drafts.add(new Draft(customer, NotificationType.APPOINTMENT_CREATED,
                        "Booking request received: " + service,
                        greet(customer) + "your request for " + service + " with " + business + " on " + when
                                + " has been received and is awaiting confirmation."));
                drafts.add(new Draft(providerUser, NotificationType.APPOINTMENT_REQUEST_RECEIVED,
                        "New booking request: " + service,
                        greet(providerUser) + customer.getFullName() + " requested " + service + " on " + when
                                + ". Please confirm or reject the request."));
            }
            case CONFIRMED -> drafts.add(new Draft(customer, NotificationType.APPOINTMENT_CONFIRMED,
                    "Appointment confirmed: " + service,
                    greet(customer) + "your appointment for " + service + " with " + business + " on " + when
                            + " is confirmed."));
            case REJECTED -> drafts.add(new Draft(customer, NotificationType.APPOINTMENT_REJECTED,
                    "Booking request declined: " + service,
                    greet(customer) + business + " could not accept your request for " + service + " on " + when
                            + "." + reason(a)));
            case CANCELLED -> {
                String body = "The appointment for " + service + " on " + when + " has been cancelled"
                        + (a.getCancelledBy() != null ? " by the " + a.getCancelledBy().name().toLowerCase(Locale.ROOT)
                        : "") + "." + reason(a);
                if (a.getCancelledBy() != Role.CUSTOMER) {
                    drafts.add(new Draft(customer, NotificationType.APPOINTMENT_CANCELLED,
                            "Appointment cancelled: " + service, greet(customer) + body));
                }
                if (a.getCancelledBy() != Role.PROVIDER) {
                    drafts.add(new Draft(providerUser, NotificationType.APPOINTMENT_CANCELLED,
                            "Appointment cancelled: " + service, greet(providerUser) + body));
                }
            }
            case RESCHEDULED -> {
                String previous = event.previousStartAt() == null ? "its previous time"
                        : format(event.previousStartAt(), a.getProvider().zoneId());
                String body = "the appointment for " + service + " has been moved from " + previous + " to " + when
                        + ".";
                drafts.add(new Draft(customer, NotificationType.APPOINTMENT_RESCHEDULED,
                        "Appointment rescheduled: " + service,
                        greet(customer) + body + " It is awaiting confirmation by " + business + "."));
                drafts.add(new Draft(providerUser, NotificationType.APPOINTMENT_RESCHEDULED,
                        "Appointment rescheduled: " + service,
                        greet(providerUser) + body + " Please confirm the new time."));
            }
            case COMPLETED -> drafts.add(new Draft(customer, NotificationType.APPOINTMENT_COMPLETED,
                    "Thanks for visiting " + business,
                    greet(customer) + "your appointment for " + service + " on " + when + " is complete."));
        }
        return drafts;
    }

    private static String format(Instant instant, ZoneId zone) {
        return FORMAT.format(instant.atZone(zone)) + " (" + zone.getId() + ")";
    }

    private static String greet(User user) {
        return "Hi " + user.getFullName() + ", ";
    }

    private static String reason(Appointment a) {
        return a.getCancellationReason() == null ? "" : " Reason: " + a.getCancellationReason();
    }
}
