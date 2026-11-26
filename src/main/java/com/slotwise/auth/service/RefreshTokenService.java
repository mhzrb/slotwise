package com.slotwise.auth.service;

import com.slotwise.auth.config.JwtProperties;
import com.slotwise.auth.domain.RefreshToken;
import com.slotwise.auth.domain.RefreshTokenRepository;
import com.slotwise.common.exception.UnauthorizedException;
import com.slotwise.user.domain.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Opaque refresh tokens with rotation and reuse detection.
 * <ul>
 *   <li>Every refresh revokes the presented token and issues a new one in the same family.</li>
 *   <li>Presenting an already-revoked token means it was stolen or replayed: the entire
 *       family is revoked, forcing a fresh login.</li>
 *   <li>Only SHA-256 hashes are persisted; a database leak does not leak usable tokens.</li>
 * </ul>
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final JwtProperties properties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository repository, JwtProperties properties, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new token family (i.e. a new login session). */
    @Transactional
    public String issueNewFamily(User user) {
        return issue(user, UUID.randomUUID());
    }

    /**
     * Validates and rotates a refresh token. {@code noRollbackFor} matters: when
     * reuse is detected, the family revocation must be committed even though the
     * call fails.
     */
    @Transactional(noRollbackFor = UnauthorizedException.class)
    public Rotation rotate(String rawToken) {
        Instant now = clock.instant();
        RefreshToken token = repository.findByTokenHashForUpdate(hash(rawToken))
                .orElseThrow(RefreshTokenService::invalid);
        if (token.isRevoked()) {
            int revoked = repository.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in family",
                    token.getUser().getId(), revoked);
            throw new UnauthorizedException("REFRESH_TOKEN_REUSED",
                    "Refresh token was already used; please log in again");
        }
        if (token.isExpired(now)) {
            throw new UnauthorizedException("REFRESH_TOKEN_EXPIRED", "Refresh token has expired");
        }
        User user = token.getUser();
        if (!user.isEnabled()) {
            throw new UnauthorizedException("ACCOUNT_DISABLED", "Account is disabled");
        }
        token.revoke(now);
        return new Rotation(user, issue(user, token.getFamilyId()));
    }

    /** Logout: revokes the token's family, if the token belongs to the user. */
    @Transactional
    public void revokeFamilyOf(String rawToken, UUID userId) {
        repository.findByTokenHashForUpdate(hash(rawToken))
                .filter(t -> t.getUser().getId().equals(userId))
                .ifPresent(t -> repository.revokeFamily(t.getFamilyId(), clock.instant()));
    }

    @Transactional
    public void revokeAllForUser(UUID userId) {
        repository.revokeAllForUser(userId, clock.instant());
    }

    private String issue(User user, UUID familyId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        repository.save(new RefreshToken(user, hash(raw), familyId,
                clock.instant().plus(properties.refreshTokenTtl())));
        return raw;
    }

    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static UnauthorizedException invalid() {
        return new UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid");
    }

    public record Rotation(User user, String refreshToken) {
    }
}
