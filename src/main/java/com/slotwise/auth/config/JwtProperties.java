package com.slotwise.auth.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * JWT settings. The application refuses to start without a sufficiently
 * long HMAC secret, so a misconfigured deployment fails fast instead of
 * running with a weak key.
 */
@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
        @NotBlank(message = "JWT_SECRET must be configured") String secret,
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotNull Duration refreshTokenTtl) {

    /** HS256 requires a key of at least 256 bits. */
    @AssertTrue(message = "JWT secret must be at least 32 bytes long")
    public boolean isSecretLongEnough() {
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }
}
