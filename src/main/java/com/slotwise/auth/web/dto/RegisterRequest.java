package com.slotwise.auth.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.slotwise.common.validation.ValidTimeZone;
import com.slotwise.user.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank
        @Size(min = PasswordPolicy.MIN_LENGTH, max = PasswordPolicy.MAX_LENGTH)
        @Pattern(regexp = PasswordPolicy.REGEX, message = PasswordPolicy.MESSAGE)
        String password,
        @NotBlank @Size(max = 120) String fullName,
        @Size(max = 32) @Pattern(regexp = "^\\+?[0-9 ()-]{5,32}$", message = "must be a valid phone number")
        String phone,
        @Schema(description = "CUSTOMER (default) or PROVIDER. ADMIN accounts cannot self-register.")
        Role role,
        @Schema(description = "Required for PROVIDER registrations")
        @Size(max = 120) String businessName,
        @Schema(description = "IANA time zone; required for PROVIDER registrations", example = "America/Toronto")
        @ValidTimeZone String timeZone) {

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "role must be CUSTOMER or PROVIDER")
    public boolean isRoleAllowed() {
        return role == null || role == Role.CUSTOMER || role == Role.PROVIDER;
    }

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "businessName and timeZone are required for providers")
    public boolean isProviderDetailsPresent() {
        return role != Role.PROVIDER
                || (businessName != null && !businessName.isBlank() && timeZone != null && !timeZone.isBlank());
    }
}
