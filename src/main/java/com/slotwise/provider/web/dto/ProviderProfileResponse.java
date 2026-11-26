package com.slotwise.provider.web.dto;

import com.slotwise.provider.domain.ProviderProfile;

import java.time.Instant;
import java.util.UUID;

/** The provider's own (private) view of their profile. */
public record ProviderProfileResponse(
        UUID id,
        UUID userId,
        String businessName,
        String description,
        String timeZone,
        int slotIntervalMinutes,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static ProviderProfileResponse from(ProviderProfile p) {
        return new ProviderProfileResponse(p.getId(), p.getUser().getId(), p.getBusinessName(), p.getDescription(),
                p.getTimeZone(), p.getSlotIntervalMinutes(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt());
    }
}
