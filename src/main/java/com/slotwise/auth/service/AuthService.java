package com.slotwise.auth.service;

import com.slotwise.auth.domain.RevokedAccessToken;
import com.slotwise.auth.domain.RevokedAccessTokenRepository;
import com.slotwise.auth.web.dto.AuthResponse;
import com.slotwise.auth.web.dto.RegisterRequest;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.UnauthorizedException;
import com.slotwise.provider.service.ProviderService;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import com.slotwise.user.domain.UserRepository;
import com.slotwise.user.web.dto.UserResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final RevokedAccessTokenRepository revokedAccessTokenRepository;
    private final ProviderService providerService;
    /** Used to spend the same time on unknown emails as on wrong passwords (no user enumeration by timing). */
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            AccessTokenService accessTokenService, RefreshTokenService refreshTokenService,
            RevokedAccessTokenRepository revokedAccessTokenRepository, ProviderService providerService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenService = accessTokenService;
        this.refreshTokenService = refreshTokenService;
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
        this.providerService = providerService;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        Role role = request.role() == null ? Role.CUSTOMER : request.role();
        User user = userRepository.save(new User(email, passwordEncoder.encode(request.password()),
                request.fullName().trim(), request.phone(), role));
        if (role == Role.PROVIDER) {
            providerService.createProfile(user, request.businessName(), request.timeZone());
        }
        log.info("Registered new {} account {}", role, user.getId());
        return tokensFor(user, refreshTokenService.issueNewFamily(user));
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        User user = userRepository.findByEmail(User.normalizeEmail(email)).orElse(null);
        if (user == null) {
            passwordEncoder.matches(password, dummyPasswordHash);
            throw new UnauthorizedException("INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            log.info("Failed login attempt for user {}", user.getId());
            throw new UnauthorizedException("INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }
        if (!user.isEnabled()) {
            throw new UnauthorizedException("ACCOUNT_DISABLED", "Account is disabled");
        }
        return tokensFor(user, refreshTokenService.issueNewFamily(user));
    }

    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(refreshToken);
        return tokensFor(rotation.user(), rotation.refreshToken());
    }

    /**
     * Revokes the presented access token (until its natural expiry) and, if
     * supplied, the refresh token family of this session.
     */
    @Transactional
    public void logout(Jwt accessToken, String refreshToken) {
        UUID userId = UUID.fromString(accessToken.getSubject());
        Instant expiresAt = accessToken.getExpiresAt() != null ? accessToken.getExpiresAt() : Instant.now();
        if (!revokedAccessTokenRepository.existsById(accessToken.getId())) {
            revokedAccessTokenRepository.save(new RevokedAccessToken(accessToken.getId(), userId, expiresAt));
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.revokeFamilyOf(refreshToken, userId);
        }
        log.info("User {} logged out", userId);
    }

    private AuthResponse tokensFor(User user, String refreshToken) {
        AccessTokenService.IssuedAccessToken access = accessTokenService.issue(user);
        return new AuthResponse(access.value(), refreshToken, "Bearer", access.expiresInSeconds(),
                UserResponse.from(user));
    }
}
