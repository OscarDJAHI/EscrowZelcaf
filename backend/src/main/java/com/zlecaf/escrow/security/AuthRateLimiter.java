package com.zlecaf.escrow.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Anti-bruteforce des endpoints d'authentification (Story 1.3, NFR-P2).
 *
 * <p><b>Deux dimensions</b> (défense en profondeur, cf. OWASP) : chaque échec
 * incrémente une clé <i>origine</i> (adresse IP, IPv6 agrégée en /64) et, pour
 * le login, une clé <i>compte</i> (email visé). L'accès est bloqué dès qu'une
 * des deux clés est verrouillée — un bruteforce distribué sur un même compte est
 * freiné par la clé compte, un balayage large par la clé origine.
 *
 * <p><b>Le succès ne lève jamais un verrou d'origine.</b> Un attaquant détenant
 * une crédential valide ne peut donc pas réarmer son quota en intercalant des
 * connexions réussies : seule la clé <i>compte</i> qu'il vient de prouver est
 * remise à zéro. Les échecs d'une origine « honnête » disparaissent d'eux-mêmes
 * via la <b>fenêtre de comptage</b> (un échec trop ancien ne compte plus).
 *
 * <p>Verrou à <b>backoff progressif</b> : au seuil, chaque nouvelle salve double
 * la durée (base 30 s → plafond 15 min). Un verrou expiré rouvre l'accès sans
 * intervention (AC2).
 *
 * <p>Mémoire bornée : éviction planifiée des entrées inactives <i>quel que soit
 * leur compteur</i> + plafond dur sur le nombre de clés (au-delà, purge des plus
 * anciennes) — un flood d'origines uniques (rotation IPv6) ne peut pas épuiser
 * le tas. État en mémoire process : suffisant en mono-instance (MVP) ; la
 * limitation distribuée relève de la stack 11.3.
 */
@Component
public class AuthRateLimiter {

    private static final class State {
        int failures;
        Instant firstFailureAt;   // début de la salve courante (fenêtre de comptage)
        int lockCount;            // salves verrouillées cumulées (pilote le backoff)
        Instant lockedUntil;      // échéance du verrou actif, ou null
        Instant lastSeen;         // dernière activité (éviction)
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final AtomicInteger size = new AtomicInteger();
    private final Clock clock;
    private final int maxAttempts;
    private final long baseLockSeconds;
    private final long maxLockSeconds;
    private final long countingWindowSeconds;
    private final long retentionSeconds;
    private final int maxEntries;

    public AuthRateLimiter(Clock clock,
                           @Value("${escrow.auth.ratelimit.max-attempts:5}") int maxAttempts,
                           @Value("${escrow.auth.ratelimit.base-lock-seconds:30}") long baseLockSeconds,
                           @Value("${escrow.auth.ratelimit.max-lock-seconds:900}") long maxLockSeconds,
                           @Value("${escrow.auth.ratelimit.counting-window-seconds:900}") long countingWindowSeconds,
                           @Value("${escrow.auth.ratelimit.retention-seconds:3600}") long retentionSeconds,
                           @Value("${escrow.auth.ratelimit.max-entries:100000}") int maxEntries) {
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.baseLockSeconds = baseLockSeconds;
        this.maxLockSeconds = maxLockSeconds;
        this.countingWindowSeconds = countingWindowSeconds;
        this.retentionSeconds = retentionSeconds;
        this.maxEntries = maxEntries;
    }

    /**
     * Secondes de verrou restantes pour cette clé (arrondi au supérieur pour ne
     * jamais rouvrir avant l'échéance), ou 0 si l'accès est ouvert. Lecture pure.
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
            Duration remaining = Duration.between(clock.instant(), s.lockedUntil);
            if (remaining.isZero() || remaining.isNegative()) {
                return 0;
            }
            long secs = remaining.getSeconds();
            return remaining.getNano() > 0 ? secs + 1 : secs;
        }
    }

    /** {@code true} si l'une des clés fournies est actuellement verrouillée. */
    public boolean isBlocked(String... keys) {
        for (String key : keys) {
            if (retryAfterSeconds(key) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Plus grand {@code retryAfterSeconds} parmi les clés (pour l'en-tête Retry-After). */
    public long maxRetryAfter(String... keys) {
        long max = 0;
        for (String key : keys) {
            max = Math.max(max, retryAfterSeconds(key));
        }
        return max;
    }

    /**
     * Enregistre un échec sur cette clé. Retourne {@code true} si CE franchissement
     * vient d'armer un verrou (le filtre journalise alors l'événement — une fois
     * par pose, jamais à chaque requête refusée).
     */
    public boolean recordFailure(String key) {
        State s = states.computeIfAbsent(key, k -> { size.incrementAndGet(); return new State(); });
        synchronized (s) {
            Instant now = clock.instant();
            s.lastSeen = now;
            // Verrou expiré : on le purge, mais lockCount survit dans la fenêtre de
            // rétention pour que le backoff reste progressif (une salve juste après
            // expiration repart plus haut, pas de la base).
            if (s.lockedUntil != null && !now.isBefore(s.lockedUntil)) {
                s.lockedUntil = null;
                s.failures = 0;
                s.firstFailureAt = null;
            }
            // Fenêtre de comptage : un échec dont la salve a commencé il y a trop
            // longtemps ne compte plus (un utilisateur honnête lent n'est pas puni).
            if (s.firstFailureAt != null
                    && Duration.between(s.firstFailureAt, now).getSeconds() > countingWindowSeconds) {
                s.failures = 0;
                s.firstFailureAt = null;
            }
            if (s.failures == 0) {
                s.firstFailureAt = now;
            }
            s.failures++;
            if (s.failures >= maxAttempts) {
                s.lockCount++;
                s.lockedUntil = now.plusSeconds(nextLockSeconds(s.lockCount));
                s.failures = 0;
                s.firstFailureAt = null;
                return true;
            }
            return false;
        }
    }

    /**
     * Succès d'authentification prouvé pour cette clé COMPTE : la salve d'échecs
     * en cours est effacée. N'agit JAMAIS sur un verrou d'origine (le paramètre
     * doit être la clé compte, pas la clé IP) — c'est ce qui interdit le
     * réarmement du quota par des connexions réussies contrôlées par l'attaquant.
     */
    public void recordAccountSuccess(String accountKey) {
        State s = states.get(accountKey);
        if (s == null) {
            return;
        }
        synchronized (s) {
            s.lastSeen = clock.instant();
            // Un compte déjà verrouillé ne peut de toute façon pas atteindre ce
            // point (bloqué avant le contrôleur) ; on ne touche donc pas au verrou.
            if (s.lockedUntil == null) {
                s.failures = 0;
                s.firstFailureAt = null;
            }
        }
    }

    private long nextLockSeconds(int lockCount) {
        int shift = Math.min(lockCount - 1, 30);
        long factor = 1L << shift;
        // Multiplication saturante : une base mal configurée ne doit pas déborder
        // en négatif (verrou dans le passé = jamais armé).
        long lock = (baseLockSeconds > maxLockSeconds / factor)
                ? maxLockSeconds
                : baseLockSeconds * factor;
        return Math.min(lock, maxLockSeconds);
    }

    /**
     * Éviction planifiée : retire toute entrée inactive depuis plus que la
     * rétention (compteur non nul inclus — c'est ce qui borne la mémoire face à
     * un flood d'origines uniques), et rabote au plafond si nécessaire.
     */
    @Scheduled(fixedDelayString = "${escrow.auth.ratelimit.eviction-interval-ms:300000}")
    public void evictStale() {
        Instant cutoff = clock.instant().minusSeconds(retentionSeconds);
        states.entrySet().removeIf(e -> {
            State s = e.getValue();
            synchronized (s) {
                boolean stale = s.lastSeen == null || s.lastSeen.isBefore(cutoff);
                if (stale) {
                    size.decrementAndGet();
                }
                return stale;
            }
        });
        // Garde-fou dur : si le plafond est franchi malgré l'éviction temporelle
        // (pic soudain d'origines), on retire les entrées les moins récemment vues.
        int overflow = size.get() - maxEntries;
        if (overflow > 0) {
            states.entrySet().stream()
                    .sorted((a, b) -> {
                        Instant la = lastSeenOf(a.getValue());
                        Instant lb = lastSeenOf(b.getValue());
                        return la.compareTo(lb);
                    })
                    .limit(overflow)
                    .map(Map.Entry::getKey)
                    .forEach(k -> { if (states.remove(k) != null) size.decrementAndGet(); });
        }
    }

    private Instant lastSeenOf(State s) {
        synchronized (s) {
            return s.lastSeen == null ? Instant.EPOCH : s.lastSeen;
        }
    }

    /** Réinitialisation totale — réservé aux tests (l'état est un singleton). */
    void reset() {
        states.clear();
        size.set(0);
    }
}
