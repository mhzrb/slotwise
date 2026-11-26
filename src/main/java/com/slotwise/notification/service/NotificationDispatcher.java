package com.slotwise.notification.service;

import com.slotwise.notification.config.NotificationProperties;
import com.slotwise.notification.domain.Notification;
import com.slotwise.notification.domain.NotificationRepository;
import com.slotwise.notification.domain.NotificationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Delivers pending outbox rows: at-least-once, with exponential backoff and a
 * maximum number of attempts. Safe to run concurrently on several threads or
 * instances thanks to {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final NotificationRepository repository;
    private final EmailSender emailSender;
    private final NotificationProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public NotificationDispatcher(NotificationRepository repository, EmailSender emailSender,
            NotificationProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.repository = repository;
        this.emailSender = emailSender;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatcher.interval}",
            initialDelayString = "${app.notifications.dispatcher.interval}")
    public void scheduledDispatch() {
        if (properties.enabled()) {
            dispatchDue();
        }
    }

    /** Processes one batch of due notifications; returns how many were attempted. */
    public int dispatchDue() {
        Integer processed = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<Notification> batch = repository.lockDueBatch(now, properties.batchSize());
            for (Notification notification : batch) {
                deliver(notification, now);
            }
            return batch.size();
        });
        return processed == null ? 0 : processed;
    }

    private void deliver(Notification notification, Instant now) {
        try {
            emailSender.send(new EmailMessage(notification.getRecipientAddress(), notification.getSubject(),
                    notification.getBody()));
            notification.markSent(now);
        } catch (RuntimeException ex) {
            notification.markAttemptFailed(ex.getMessage(), now, properties.maxAttempts(), properties.retryBackoff());
            if (notification.getStatus() == NotificationStatus.FAILED) {
                log.error("Notification {} failed permanently after {} attempts: {}", notification.getId(),
                        notification.getAttempts(), ex.getMessage());
            } else {
                log.warn("Notification {} delivery failed (attempt {}), retrying at {}: {}", notification.getId(),
                        notification.getAttempts(), notification.getNextAttemptAt(), ex.getMessage());
            }
        }
    }
}
