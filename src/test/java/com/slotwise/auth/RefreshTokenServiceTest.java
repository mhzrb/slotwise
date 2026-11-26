package com.slotwise.auth;

import com.slotwise.auth.config.JwtProperties;
import com.slotwise.auth.domain.RefreshToken;
import com.slotwise.auth.domain.RefreshTokenRepository;
import com.slotwise.auth.service.RefreshTokenService;
import com.slotwise.common.exception.UnauthorizedException;
import com.slotwise.user.domain.Role;
import com.slotwise.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2030-03-01T12:00:00Z");

    @Mock
    RefreshTokenRepository repository;

    RefreshTokenService service;
    User user;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties("x".repeat(32), "slotwise", Duration.ofMinutes(15), Duration.ofDays(14));
        service = new RefreshTokenService(repository, props, Clock.fixed(NOW, ZoneOffset.UTC));
        user = new User("u@example.com", "h", "U", null, Role.CUSTOMER);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
    }

    @Test
    void issuedTokenIsStoredOnlyAsHash() {
        String raw = service.issueNewFamily(user);

        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        String storedHash = (String) ReflectionTestUtils.getField(saved.getValue(), "tokenHash");
        assertThat(storedHash).hasSize(64).isNotEqualTo(raw);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    void rotationRevokesOldTokenAndKeepsFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken existing = new RefreshToken(user, "hash", family, NOW.plusSeconds(60));
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        RefreshTokenService.Rotation rotation = service.rotate("raw");

        assertThat(existing.isRevoked()).isTrue();
        assertThat(rotation.user()).isSameAs(user);
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFamilyId()).isEqualTo(family);
    }

    @Test
    void reuseOfRevokedTokenRevokesFamily() {
        UUID family = UUID.randomUUID();
        RefreshToken existing = new RefreshToken(user, "hash", family, NOW.plusSeconds(60));
        existing.revoke(NOW.minusSeconds(5));
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("REFRESH_TOKEN_REUSED");
        verify(repository).revokeFamily(family, NOW);
        verify(repository, never()).save(any());
    }

    @Test
    void expiredTokenIsRejected() {
        RefreshToken existing = new RefreshToken(user, "hash", UUID.randomUUID(), NOW);
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("REFRESH_TOKEN_EXPIRED");
    }

    @Test
    void tokenOfDisabledUserIsRejected() {
        user.setEnabled(false);
        RefreshToken existing = new RefreshToken(user, "hash", UUID.randomUUID(), NOW.plusSeconds(60));
        when(repository.findByTokenHashForUpdate(anyString())).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.rotate("raw"))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("code").isEqualTo("ACCOUNT_DISABLED");
    }
}
