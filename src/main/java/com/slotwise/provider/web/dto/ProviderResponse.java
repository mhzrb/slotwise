package com.slotwise.provider.web.dto;

import com.slotwise.provider.domain.ProviderProfile;

import java.util.UUID;

/** Public provider information. Owner contact details are deliberately excluded. */
public record ProviderResponse(
        UUID id,
        String businessName,
        String description,
        String timeZone,
        int slotIntervalMinutes) {

    public static ProviderResponse from(ProviderProfile p) {
        return new ProviderResponse(p.getId(), p.getBusinessName(), p.getDescription(), p.getTimeZone(),
                p.getSlotIntervalMinutes());
    }
}
