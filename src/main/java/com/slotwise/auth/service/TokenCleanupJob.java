package com.slotwise.auth.service;

import com.slotwise.auth.domain.RefreshTokenRepository;
import com.slotwise.auth.domain.RevokedAccessTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/** Purges token rows that can no longer be used, keeping both tables small. */
@Component
public class TokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupJob.class);

    private final RefreshTokenRepository refreshTokens;
    private final RevokedAccessTokenRepository revokedAccessTokens;
    private final Clock clock;

    public TokenCleanupJob(RefreshTokenRepository refreshTokens, RevokedAccessTokenRepository revokedAccessTokens,
            Clock clock) {
        this.refreshTokens = refreshTokens;
        this.revokedAccessTokens = revokedAccessTokens;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.security.token-cleanup-cron:0 17 * * * *}")
    @Transactional
    public void purgeExpired() {
        Instant now = clock.instant();
        int refresh = refreshTokens.deleteExpired(now);
        int access = revokedAccessTokens.deleteExpired(now);
        if (refresh + access > 0) {
            log.info("Purged {} expired refresh token(s) and {} revoked access token(s)", refresh, access);
        }
    }
}
