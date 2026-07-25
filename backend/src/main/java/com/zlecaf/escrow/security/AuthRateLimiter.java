package com.zlecaf.escrow.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Anti-bruteforce des endpoints d'authentification (Story 1.3, NFR-P2).
 *
 * <p>Compteur d'échecs par origine, verrou à backoff progressif : au-delà du
 * seuil, chaque nouveau verrou double la durée du précédent (base 30 s,
 * plafond 15 min par défaut). Un verrou expiré rouvre l'accès sans aucune
 * intervention (AC2) ; un succès remet le compteur à zéro.
 *
 * <p>État en mémoire process : suffisant en mono-instance (MVP). Une
 * limitation distribuée (multi-réplicas) relèvera de la stack 11.3.
 */
@Component
public class AuthRateLimiter {

    /** État d'une origine : échecs consécutifs, verrous consécutifs, échéance du verrou. */
    private static final class State {
        int failures;
        int lockCount;
        Instant lockedUntil;
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxAttempts;
    private final long baseLockSeconds;
    private final long maxLockSeconds;

    public AuthRateLimiter(Clock clock,
                           @Value("${escrow.auth.ratelimit.max-attempts:5}") int maxAttempts,
                           @Value("${escrow.auth.ratelimit.base-lock-seconds:30}") long baseLockSeconds,
                           @Value("${escrow.auth.ratelimit.max-lock-seconds:900}") long maxLockSeconds) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.baseLockSeconds = baseLockSeconds;
        this.maxLockSeconds = maxLockSeconds;
    }

    /**
     * Secondes restantes de verrou pour cette origine, ou 0 si l'accès est ouvert.
     * Ne modifie pas l'état : une tentative pendant le verrou ne le prolonge pas
     * (le backoff ne progresse qu'à chaque NOUVELLE salve d'échecs).
     */
    public long retryAfterSeconds(String key) {
        State s = states.get(key);
        if (s == null) {
            return 0;
        }
        synchronized (s) {
            if (s.lockedUntil == null) {
                return 0;
            }
            long remaining = Duration.between(clock.instant(), s.lockedUntil).getSeconds();
            return Math.max(remaining, 0);
        }
    }

    /** Enregistre un échec ; pose (ou repose, doublée) la fenêtre de verrou au franchissement du seuil. */
    public void recordFailure(String key) {
        State s = states.computeIfAbsent(key, k -> new State());
        synchronized (s) {
            // Un verrou expiré se purge à la première activité suivante.
            if (s.lockedUntil != null && !clock.instant().isBefore(s.lockedUntil)) {
                s.lockedUntil = null;
                s.failures = 0;
            }
            s.failures++;
            if (s.failures >= maxAttempts) {
                s.lockCount++;
                long lockSeconds = Math.min(
                        baseLockSeconds * (1L << Math.min(s.lockCount - 1, 30)),
                        maxLockSeconds);
                s.lockedUntil = clock.instant().plusSeconds(lockSeconds);
                s.failures = 0;
            }
        }
    }

    /** Succès d'authentification : l'origine repart de zéro (verrou et backoff inclus). */
    public void recordSuccess(String key) {
        states.remove(key);
    }

    /** Éviction périodique des origines inactives (verrou expiré depuis > 1 h, ou sans échec ni verrou). */
    @Scheduled(fixedDelayString = "${escrow.auth.ratelimit.eviction-interval-ms:300000}")
    public void evictStale() {
        Instant cutoff = clock.instant().minus(Duration.ofHours(1));
        states.entrySet().removeIf(e -> {
            State s = e.getValue();
            synchronized (s) {
                boolean idle = s.failures == 0 && s.lockedUntil == null;
                boolean lockLongExpired = s.lockedUntil != null && s.lockedUntil.isBefore(cutoff);
                return idle || lockLongExpired;
            }
        });
    }
}
