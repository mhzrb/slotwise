package com.slotwise.notification;

import com.slotwise.notification.domain.Notification;
import com.slotwise.notification.domain.NotificationStatus;
import com.slotwise.notification.domain.NotificationType;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationTest {

    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void failedAttemptsBackOffExponentiallyThenGiveUp() {
        Notification n = new Notification(new User("a@example.com", "h", "A", null, Role.CUSTOMER), null,
                NotificationType.APPOINTMENT_CREATED, "s", "b", NOW);
        Duration backoff = Duration.ofMinutes(1);

        n.markAttemptFailed("smtp down", NOW, 4, backoff);
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        n.markAttemptFailed("smtp down", NOW, 4, backoff);
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
        n.markAttemptFailed("smtp down", NOW, 4, backoff);
        assertThat(n.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(4)));
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);

        n.markAttemptFailed("x".repeat(600), NOW, 4, backoff);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getAttempts()).isEqualTo(4);
        assertThat(n.getLastError()).hasSize(500);
    }

    @Test
    void successRecordsSentTimeAndClearsError() {
        Notification n = new Notification(new User("a@example.com", "h", "A", null, Role.CUSTOMER), null,
                NotificationType.APPOINTMENT_CREATED, "s", "b", NOW);
        n.markAttemptFailed("temporary", NOW, 5, Duration.ofMinutes(1));

        n.markSent(NOW.plusSeconds(90));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(NOW.plusSeconds(90));
        assertThat(n.getLastError()).isNull();
        assertThat(n.getAttempts()).isEqualTo(2);
    }
}
