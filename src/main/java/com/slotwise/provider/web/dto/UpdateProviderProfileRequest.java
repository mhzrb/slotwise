package com.slotwise.provider.web.dto;

import com.slotwise.common.validation.ValidTimeZone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProviderProfileRequest(
        @NotBlank @Size(max = 120) String businessName,
        @Size(max = 2000) String description,
        @NotBlank @ValidTimeZone String timeZone,
        @Schema(description = "Spacing between offered slot start times, in minutes")
        @Min(5) @Max(240) Integer slotIntervalMinutes,
        @Schema(description = "Inactive providers are hidden from the public catalogue")
        Boolean active) {
}
