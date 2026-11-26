package com.slotwise.auth;

import com.slotwise.auth.domain.RevokedAccessToken;
import com.slotwise.auth.domain.RevokedAccessTokenRepository;
import com.slotwise.auth.service.AccessTokenService;
import com.slotwise.auth.service.AuthService;
import com.slotwise.auth.service.RefreshTokenService;
import com.slotwise.auth.web.dto.AuthResponse;
import com.slotwise.auth.web.dto.RegisterRequest;
import com.slotwise.common.exception.ConflictException;
import com.slotwise.common.exception.UnauthorizedException;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import com.slotwise.user.domain.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    AccessTokenService accessTokenService;
    @Mock
    RefreshTokenService refreshTokenService;
    @Mock
    RevokedAccessTokenRepository revokedAccessTokenRepository;

    AuthService authService;

    @BeforeEach
    void setUp() {
        when(passwordEncoder.encode(anyString())).thenReturn("{bcrypt}dummy");
        authService = new AuthService(userRepository, passwordEncoder, accessTokenService, refreshTokenService,
                revokedAccessTokenRepository);
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request(" Taken@Example.com ")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already exists");
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerNormalisesEmailHashesPasswordAndDefaultsToCustomer() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Secret123")).thenReturn("{bcrypt}hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> withId(inv.getArgument(0)));
        when(refreshTokenService.issueNewFamily(any())).thenReturn("refresh");
        when(accessTokenService.issue(any())).thenReturn(
                new AccessTokenService.IssuedAccessToken("access", Instant.now(), 900));

        AuthResponse response = authService.register(request("New@Example.com"));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("new@example.com");
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("{bcrypt}hash");
        assertThat(saved.getValue().getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
        assertThat(response.expiresIn()).isEqualTo(900);
    }

    @Test
    void loginWithUnknownEmailStillPerformsPasswordCheck() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login("ghost@example.com", "whatever1"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIALS");
        // constant-time-ish: a hash comparison happens even when the user does not exist
        verify(passwordEncoder).matches(eq("whatever1"), anyString());
    }

    @Test
    void loginWithWrongPasswordFails() {
        User user = withId(new User("a@example.com", "{bcrypt}hash", "A", null, Role.CUSTOMER));
        when(userRepository.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("bad", "{bcrypt}hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login("a@example.com", "bad"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("INVALID_CREDENTIALS");
        verify(refreshTokenService, never()).issueNewFamily(any());
    }

    @Test
    void loginOfDisabledAccountFailsEvenWithCorrectPassword() {
        User user = withId(new User("a@example.com", "{bcrypt}hash", "A", null, Role.CUSTOMER));
        user.setEnabled(false);
        when(userRepository.findByEmail("a@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("good", "{bcrypt}hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.login("a@example.com", "good"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("ACCOUNT_DISABLED");
    }

    @Test
    void logoutDenylistsAccessTokenAndRevokesRefreshFamily() {
        UUID userId = UUID.randomUUID();
        Instant exp = Instant.now().plusSeconds(600);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "HS256").subject(userId.toString())
                .jti("jti-1").expiresAt(exp).issuedAt(exp.minusSeconds(900)).build();

        authService.logout(jwt, "refresh-raw");

        ArgumentCaptor<RevokedAccessToken> revoked = ArgumentCaptor.forClass(RevokedAccessToken.class);
        verify(revokedAccessTokenRepository).save(revoked.capture());
        assertThat(revoked.getValue().getJti()).isEqualTo("jti-1");
        verify(refreshTokenService).revokeFamilyOf("refresh-raw", userId);
    }

    private static RegisterRequest request(String email) {
        return new RegisterRequest(email, "Secret123", "New User", null, null, null, null);
    }

    private static User withId(User user) {
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
