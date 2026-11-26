package com.slotwise.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Platform-wide booking policy, configurable per environment. */
@Validated
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(
        @NotNull Duration minBookingNotice,
        @NotNull Duration maxBookingHorizon,
        @NotNull Duration customerCancellationCutoff,
        @NotNull Duration customerRescheduleCutoff,
        @Min(0) int maxReschedules,
        @Min(1) int maxSlotQueryDays) {
}
