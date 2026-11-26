package com.slotwise.user.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank @Size(max = 120) String fullName,
        @Size(max = 32) @Pattern(regexp = "^\\+?[0-9 ()-]{5,32}$", message = "must be a valid phone number")
        String phone) {
}
