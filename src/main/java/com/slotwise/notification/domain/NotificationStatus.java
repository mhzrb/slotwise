package com.slotwise.notification.domain;

public enum NotificationStatus {
    PENDING,
    SENT,
    /** Gave up after the maximum number of delivery attempts. */
    FAILED
}
