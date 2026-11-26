package com.slotwise.notification.web.dto;

import com.slotwise.notification.domain.Notification;
import com.slotwise.notification.domain.NotificationChannel;
import com.slotwise.notification.domain.NotificationStatus;
import com.slotwise.notification.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID appointmentId,
        NotificationType type,
        NotificationChannel channel,
        String subject,
        String body,
        NotificationStatus status,
        Instant createdAt,
        Instant sentAt) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getAppointmentId(), n.getType(), n.getChannel(), n.getSubject(),
                n.getBody(), n.getStatus(), n.getCreatedAt(), n.getSentAt());
    }
}
