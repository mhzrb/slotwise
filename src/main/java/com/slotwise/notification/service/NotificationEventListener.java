package com.slotwise.notification.service;

import com.slotwise.appointment.domain.Appointment;
import com.slotwise.appointment.domain.AppointmentEvent;
import com.slotwise.appointment.domain.AppointmentRepository;
import com.slotwise.notification.config.NotificationProperties;
import com.slotwise.notification.domain.Notification;
import com.slotwise.notification.domain.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Clock;

/**
 * Bridges appointment events to the outbox.
 *
 * <ul>
 *   <li><b>BEFORE_COMMIT</b> - notification rows are written inside the
 *       appointment's transaction: they exist if and only if the appointment
 *       change committed (no lost or phantom notifications). This step is a
 *       local insert only; nothing here talks to the outside world.</li>
 *   <li><b>AFTER_COMMIT, async</b> - wakes the dispatcher so delivery happens
 *       within moments, on a separate thread and transaction. Delivery
 *       failures can therefore never roll back or delay an appointment.</li>
 * </ul>
 */
@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final AppointmentRepository appointmentRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationComposer composer;
    private final NotificationDispatcher dispatcher;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationEventListener(AppointmentRepository appointmentRepository,
            NotificationRepository notificationRepository, NotificationComposer composer,
            NotificationDispatcher dispatcher, NotificationProperties properties, Clock clock) {
        this.appointmentRepository = appointmentRepository;
        this.notificationRepository = notificationRepository;
        this.composer = composer;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void writeOutbox(AppointmentEvent event) {
        Appointment appointment = appointmentRepository.findDetailedById(event.appointmentId()).orElseThrow();
        composer.compose(event, appointment).forEach(draft -> notificationRepository.save(new Notification(
                draft.recipient(), appointment.getId(), draft.type(), draft.subject(), draft.body(),
                clock.instant())));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void wakeDispatcher(AppointmentEvent event) {
        if (!properties.enabled()) {
            return;
        }
        try {
            dispatcher.dispatchDue();
        } catch (RuntimeException ex) {
            // the scheduled run will pick the notifications up again
            log.warn("Immediate notification dispatch failed: {}", ex.getMessage());
        }
    }
}
