package com.slotwise.catalog.web.dto;

import com.slotwise.catalog.service.ServiceOfferingService.ServiceDetails;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record ServiceRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 2000) String description,
        @Schema(example = "30") @NotNull @Min(5) @Max(720) Integer durationMinutes,
        @Schema(description = "Blocked time after each appointment", example = "10")
        @NotNull @Min(0) @Max(240) Integer bufferMinutes,
        @Schema(example = "49.99")
        @NotNull @DecimalMin("0.00") @DecimalMax("99999999.99") @Digits(integer = 8, fraction = 2)
        BigDecimal price,
        @Schema(description = "Defaults to true on create; unchanged on update when omitted")
        Boolean active) {

    public ServiceDetails toDetails() {
        return new ServiceDetails(name, description, durationMinutes, bufferMinutes, price, active);
    }
}
