package com.slotwise.availability.web.dto;

import com.slotwise.availability.service.AvailabilityService.SlotSearchResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

public record SlotsResponse(
        UUID providerId,
        UUID serviceId,
        String timeZone,
        LocalDate from,
        LocalDate to,
        int durationMinutes,
        List<SlotResponse> slots) {

    public record SlotResponse(
            @Schema(description = "Slot start in UTC; send this value when booking") Instant startAt,
            Instant endAt,
            @Schema(description = "Slot start in the provider's time zone") OffsetDateTime localStart,
            OffsetDateTime localEnd) {
    }

    public static SlotsResponse from(SlotSearchResult result) {
        ZoneId zone = result.provider().zoneId();
        return new SlotsResponse(result.provider().getId(), result.service().getId(), zone.getId(), result.from(),
                result.to(), result.service().getDurationMinutes(),
                result.slots().stream().map(s -> new SlotResponse(s.start(), s.end(),
                        s.start().atZone(zone).toOffsetDateTime(), s.end().atZone(zone).toOffsetDateTime()))
                        .toList());
    }
}
