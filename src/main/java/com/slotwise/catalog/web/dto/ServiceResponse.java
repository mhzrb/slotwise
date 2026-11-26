package com.slotwise.catalog.web.dto;

import com.slotwise.catalog.domain.ServiceOffering;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ServiceResponse(
        UUID id,
        UUID providerId,
        String providerName,
        String name,
        String description,
        int durationMinutes,
        int bufferMinutes,
        BigDecimal price,
        boolean active,
        Instant createdAt,
        Instant updatedAt) {

    public static ServiceResponse from(ServiceOffering s) {
        return new ServiceResponse(s.getId(), s.getProvider().getId(), s.getProvider().getBusinessName(), s.getName(),
                s.getDescription(), s.getDurationMinutes(), s.getBufferMinutes(), s.getPrice(), s.isActive(),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
