package com.slotwise.notification.domain;

import com.slotwise.common.domain.BaseEntity;
import com.slotwise.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Outbox row: one message to one recipient over one channel, plus its delivery state. */
@Entity
@Table(name = "notifications")
public class Notification extends BaseEntity {

    private static final int MAX_ERROR_LENGTH = 500;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** Plain id (no association) to keep the notification module decoupled from appointment internals. */
    @Column(name = "appointment_id")
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationChannel channel;

    @Column(name = "recipient_address", nullable = false, length = 254)
    private String recipientAddress;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected Notification() {
    }

    public Notification(User recipient, UUID appointmentId, NotificationType type, String subject, String body,
            Instant now) {
        this.recipient = recipient;
        this.appointmentId = appointmentId;
        this.type = type;
        this.channel = NotificationChannel.EMAIL;
        this.recipientAddress = recipient.getEmail();
        this.subject = subject;
        this.body = body;
        this.nextAttemptAt = now;
    }

    public void markSent(Instant now) {
        attempts++;
        status = NotificationStatus.SENT;
        sentAt = now;
        lastError = null;
    }

    /** Exponential backoff: backoff, 2x, 4x, ... until maxAttempts, then FAILED. */
    public void markAttemptFailed(String error, Instant now, int maxAttempts, Duration backoff) {
        attempts++;
        lastError = error == null ? "unknown error"
                : error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        if (attempts >= maxAttempts) {
            status = NotificationStatus.FAILED;
        } else {
            nextAttemptAt = now.plus(backoff.multipliedBy(1L << (attempts - 1)));
        }
    }

    public User getRecipient() {
        return recipient;
    }

    public UUID getAppointmentId() {
        return appointmentId;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getRecipientAddress() {
        return recipientAddress;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
