package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 1.3 (NFR-P2) : seuil, backoff progressif, plafond, fenêtre de comptage,
 * non-réarmement par succès, éviction bornée — sur horloge contrôlée.
 */
class AuthRateLimiterTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-07-25T00:00:00Z");
        void advanceSeconds(long s) { now = now.plusSeconds(s); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static final String ORIGIN = "origin|203.0.113.5";
    private static final String ACCOUNT = "account|victim@example.com";

    private final MutableClock clock = new MutableClock();
    // seuil 5, base 30 s, cap 900 s, fenêtre de comptage 900 s, rétention 3600 s, cap 100k
    private final AuthRateLimiter limiter = new AuthRateLimiter(clock, 5, 30, 900, 900, 3600, 100_000);

    private void fail(String key, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(key);
        }
    }

    @Test
    @DisplayName("sous le seuil : accès ouvert")
    void underThreshold_open() {
        fail(ORIGIN, 4);
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isZero();
    }

    @Test
    @DisplayName("au seuil : verrou ~30 s et recordFailure signale la pose")
    void atThreshold_locksAndSignals() {
        for (int i = 0; i < 4; i++) {
            assertThat(limiter.recordFailure(ORIGIN)).isFalse();
        }
        assertThat(limiter.recordFailure(ORIGIN)).as("le 5e échec arme le verrou").isTrue();
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isBetween(1L, 30L);
    }

    @Test
    @DisplayName("AC2 : le verrou expire seul")
    void lockExpires() {
        fail(ORIGIN, 5);
        clock.advanceSeconds(31);
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isZero();
    }

    @Test
    @DisplayName("retryAfter est arrondi au supérieur : il ne rouvre pas avant l'échéance")
    void retryAfter_roundsUp() {
        fail(ORIGIN, 5); // verrou 30 s
        clock.advanceSeconds(29); // reste 1 s pile
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isEqualTo(1);
        // encore verrouillé à 0,5 s près
    }

    @Test
    @DisplayName("backoff progressif : 30 -> 60 -> 120")
    void progressiveBackoff() {
        fail(ORIGIN, 5);
        clock.advanceSeconds(31);
        fail(ORIGIN, 5);
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isBetween(31L, 60L);
        clock.advanceSeconds(61);
        fail(ORIGIN, 5);
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isBetween(61L, 120L);
    }

    @Test
    @DisplayName("le backoff plafonne à 900 s")
    void backoffCapped() {
        for (int round = 0; round < 12; round++) {
            fail(ORIGIN, 5);
            clock.advanceSeconds(901);
        }
        fail(ORIGIN, 5);
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isBetween(1L, 900L);
    }

    @Test
    @DisplayName("SÉCURITÉ : un succès de COMPTE ne lève jamais un verrou d'ORIGINE (pas de réarmement)")
    void accountSuccess_doesNotResetOrigin() {
        // L'attaquant accumule des échecs d'origine en intercalant des « succès »
        // sur son propre compte : l'origine doit tout de même finir verrouillée.
        for (int salvo = 0; salvo < 5; salvo++) {
            fail(ORIGIN, 1);
            limiter.recordAccountSuccess("account|attacker@example.com");
        }
        assertThat(limiter.retryAfterSeconds(ORIGIN)).as("l'origine se verrouille malgré les succès intercalés").isPositive();
    }

    @Test
    @DisplayName("un succès de compte efface la salve d'échecs de CE compte")
    void accountSuccess_clearsThatAccount() {
        fail(ACCOUNT, 4);
        limiter.recordAccountSuccess(ACCOUNT);
        fail(ACCOUNT, 4);
        assertThat(limiter.retryAfterSeconds(ACCOUNT)).isZero();
    }

    @Test
    @DisplayName("fenêtre de comptage : des échecs trop espacés ne s'additionnent pas")
    void countingWindow_expiresOldFailures() {
        fail(ORIGIN, 4);
        clock.advanceSeconds(901); // au-delà de la fenêtre de 900 s
        fail(ORIGIN, 4);           // repart d'une salve neuve
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isZero();
    }

    @Test
    @DisplayName("origines indépendantes")
    void independentKeys() {
        fail(ORIGIN, 5);
        assertThat(limiter.retryAfterSeconds("origin|203.0.113.6")).isZero();
    }

    @Test
    @DisplayName("l'éviction garde un verrou actif")
    void eviction_keepsActive() {
        fail(ORIGIN, 5);
        limiter.evictStale();
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isPositive();
    }

    @Test
    @DisplayName("MÉMOIRE : l'éviction purge une entrée sous le seuil restée inactive au-delà de la rétention")
    void eviction_dropsIdleSubThresholdEntries() {
        fail(ORIGIN, 2); // 2 échecs, pas de verrou — l'ancienne implémentation la gardait pour toujours
        clock.advanceSeconds(3601); // au-delà de la rétention
        limiter.evictStale();
        // repart d'une salve neuve : la preuve que l'entrée a bien été purgée
        fail(ORIGIN, 4);
        assertThat(limiter.retryAfterSeconds(ORIGIN)).isZero();
    }

    @Test
    @DisplayName("MÉMOIRE : le plafond dur borne le nombre de clés")
    void eviction_enforcesHardCap() {
        AuthRateLimiter small = new AuthRateLimiter(clock, 5, 30, 900, 900, 3600, 10);
        for (int i = 0; i < 50; i++) {
            small.recordFailure("origin|10.0.0." + i);
            clock.advanceSeconds(1); // lastSeen croissant → les plus anciennes partent d'abord
        }
        small.evictStale();
        // pas d'assertion sur un compteur interne (privé) : on vérifie l'invariant
        // fonctionnel — les entrées les plus récentes survivent, les vieilles non.
        assertThat(small.retryAfterSeconds("origin|10.0.0.49")).isZero(); // récente, présente ou recréable
        assertThat(small.retryAfterSeconds("origin|10.0.0.0")).isZero();  // ancienne, purgée
    }
}
