package com.slotwise.notification.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "app.notifications.dispatcher")
public record NotificationProperties(
        boolean enabled,
        @NotNull Duration interval,
        @Min(1) int batchSize,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff) {
}
