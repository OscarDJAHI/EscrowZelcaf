package com.zlecaf.escrow.scheduler;

import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Bounds the growth of {@code partner_key_nonces} (Story 3.4, report #1): the
 * anti-replay table would otherwise grow forever, since nothing consumed the
 * {@link PartnerKeyNonceRepository#deleteBySeenAtBefore} primitive shipped in
 * Story 3.1. A nonce only has anti-replay value while its timestamp is inside
 * the freshness window; once past it the window guard already rejects the
 * request, so a nonce older than {@code max(retention, timestamp-tolerance)} is
 * safe to drop.
 *
 * <p>The {@code max(...)} floor prevents a misconfigured retention shorter than
 * the tolerance from re-opening the replay window. {@link #purge()} is public and
 * side-effect deterministic so a test can invoke it directly (not through the
 * scheduler) and assert the exact rows removed.
 */
@Component
public class PartnerNoncePurger {

    private static final Logger log = LoggerFactory.getLogger(PartnerNoncePurger.class);

    private final PartnerKeyNonceRepository nonceRepository;
    private final long retentionSeconds;
    private final long toleranceSeconds;

    public PartnerNoncePurger(PartnerKeyNonceRepository nonceRepository,
                              @Value("${escrow.partner.nonce-retention-seconds:900}") long retentionSeconds,
                              @Value("${escrow.partner.timestamp-tolerance-seconds:300}") long toleranceSeconds) {
        this.nonceRepository = nonceRepository;
        this.retentionSeconds = retentionSeconds;
        this.toleranceSeconds = toleranceSeconds;
    }

    /**
     * Deletes every nonce older than {@code now - max(retention, tolerance)} and
     * returns how many rows were removed. Never touches a nonce still inside the
     * freshness window, so it can never re-open the replay window.
     */
    public int purge() {
        Instant cutoff = Instant.now().minusSeconds(Math.max(retentionSeconds, toleranceSeconds));
        int removed = nonceRepository.deleteBySeenAtBefore(cutoff);
        if (removed > 0) {
            log.debug("Purged {} expired partner nonce(s) older than {}", removed, cutoff);
        }
        return removed;
    }

    /**
     * Scheduled trigger kept as a thin wrapper over {@link #purge()} so tests can
     * exercise the purge logic deterministically without the scheduler.
     *
     * <p>{@code @Transactional} on this proxied scheduler entry point makes the
     * bounded-retention delete self-contained: the modifying purge always runs
     * inside a transaction even though the scheduler invokes it with no ambient
     * one, without relying solely on the repository method's own annotation.
     */
    @Scheduled(fixedDelayString = "${escrow.partner.nonce-purge-interval-ms:60000}")
    @Transactional
    public void scheduledPurge() {
        purge();
    }
}
