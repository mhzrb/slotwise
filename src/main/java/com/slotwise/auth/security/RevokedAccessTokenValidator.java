package com.slotwise.auth.security;

import com.slotwise.auth.domain.RevokedAccessTokenRepository;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects access tokens whose {@code jti} was revoked by logout. This costs a
 * single primary-key lookup per authenticated request, which is the price of
 * real logout semantics with otherwise stateless JWTs.
 */
public class RevokedAccessTokenValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED = new OAuth2Error("invalid_token", "Token has been revoked", null);

    private final RevokedAccessTokenRepository repository;

    public RevokedAccessTokenValidator(RevokedAccessTokenRepository repository) {
        this.repository = repository;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String jti = jwt.getId();
        if (jti == null) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Missing jti", null));
        }
        return repository.existsById(jti)
                ? OAuth2TokenValidatorResult.failure(REVOKED)
                : OAuth2TokenValidatorResult.success();
    }
}
