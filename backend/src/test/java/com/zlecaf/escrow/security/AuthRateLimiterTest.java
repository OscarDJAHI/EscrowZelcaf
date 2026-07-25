package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 1.3 (NFR-P2) : seuil, backoff progressif doublé, plafond, expiration
 * automatique (AC2) et reset sur succès — sur horloge contrôlée.
 */
class AuthRateLimiterTest {

    /** Horloge mutable : le temps n'avance que quand le test le décide. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-25T00:00:00Z");
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public Instant instant() { return now; }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    private static final String KEY = "/api/v1/auth/login|10.0.0.1";

    private final MutableClock clock = new MutableClock();
    // seuil 5, verrou de base 30 s, plafond 900 s — les défauts de production
    private final AuthRateLimiter limiter = new AuthRateLimiter(clock, 5, 30, 900);

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(KEY);
        }
    }

    @Test
    @DisplayName("sous le seuil : l'accès reste ouvert")
    void underThreshold_staysOpen() {
        fail(4);
        assertThat(limiter.retryAfterSeconds(KEY)).isZero();
    }

    @Test
    @DisplayName("au seuil : verrou de 30 s posé")
    void atThreshold_locks30s() {
        fail(5);
        assertThat(limiter.retryAfterSeconds(KEY)).isBetween(1L, 30L);
    }

    @Test
    @DisplayName("AC2 : le verrou expire seul — l'accès se rétablit sans intervention")
    void lockExpires_accessRestored() {
        fail(5);
        clock.advanceSeconds(31);
        assertThat(limiter.retryAfterSeconds(KEY)).isZero();
    }

    @Test
    @DisplayName("backoff progressif : chaque nouvelle salve double le verrou (30, 60, 120…)")
    void progressiveBackoff_doubles() {
        fail(5); // verrou 30 s
        clock.advanceSeconds(31);
        fail(5); // verrou 60 s
        assertThat(limiter.retryAfterSeconds(KEY)).isBetween(31L, 60L);
        clock.advanceSeconds(61);
        fail(5); // verrou 120 s
        assertThat(limiter.retryAfterSeconds(KEY)).isBetween(61L, 120L);
    }

    @Test
    @DisplayName("le backoff plafonne à max-lock-seconds (900 s)")
    void backoff_isCapped() {
        for (int round = 0; round < 12; round++) {
            fail(5);
            clock.advanceSeconds(901);
        }
        fail(5);
        assertThat(limiter.retryAfterSeconds(KEY)).isBetween(1L, 900L);
    }

    @Test
    @DisplayName("un succès remet tout à zéro : compteur, verrou et niveau de backoff")
    void success_resetsEverything() {
        fail(5);
        clock.advanceSeconds(31);
        limiter.recordSuccess(KEY);
        fail(4);
        assertThat(limiter.retryAfterSeconds(KEY)).isZero();
        fail(1); // 5e échec d'une salve neuve → verrou de BASE (30 s), pas doublé
        assertThat(limiter.retryAfterSeconds(KEY)).isBetween(1L, 30L);
    }

    @Test
    @DisplayName("les origines sont indépendantes")
    void keys_areIndependent() {
        fail(5);
        assertThat(limiter.retryAfterSeconds("/api/v1/auth/login|10.0.0.2")).isZero();
    }

    @Test
    @DisplayName("l'éviction ne purge pas un verrou actif")
    void eviction_keepsActiveLocks() {
        fail(5);
        limiter.evictStale();
        assertThat(limiter.retryAfterSeconds(KEY)).isPositive();
    }

    @Test
    @DisplayName("l'éviction purge les verrous expirés depuis plus d'une heure")
    void eviction_dropsLongExpired() {
        fail(5);
        clock.advanceSeconds(Duration.ofHours(2).getSeconds());
        limiter.evictStale();
        // nouvelle salve : repart du verrou de base (l'historique a été purgé)
        fail(5);
        assertThat(limiter.retryAfterSeconds(KEY)).isBetween(1L, 30L);
    }
}
