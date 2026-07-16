package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.PartnerKeyNonce;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface PartnerKeyNonceRepository extends JpaRepository<PartnerKeyNonce, Long> {

    /**
     * Fast replay probe: has this {@code nonce} already been seen under this
     * {@code keyId}? Scoped per key-id, mirroring the
     * {@code uq_partner_key_nonces_key_nonce} constraint that is the ultimate
     * arbiter — a concurrent insert still fails on that constraint even if the
     * probe raced and returned {@code false}.
     */
    boolean existsByKeyIdAndNonce(String keyId, String nonce);

    /**
     * Bounded retention purge, issued as a single bulk {@code DELETE} (not the
     * derived select-then-delete-each): removes every nonce whose {@code seenAt}
     * is strictly before {@code cutoff}, keeping the recent tail. The caller owns
     * the cutoff and must keep it at least the validity window old
     * ({@code now - retention}, retention ≥ 5 min) so an in-window nonce is never
     * dropped. Returns the number of rows removed. {@code @Transactional} so the
     * bulk delete runs in its own unit of work when invoked outside an ambient
     * transaction; no {@code @Scheduled} orchestration lives here (that is
     * Story 3.2). Returns {@code int} per Spring Data's {@code @Modifying}
     * contract (row counts fit an int; a purge never removes 2^31 rows at once).
     */
    @Modifying
    @Transactional
    @Query("delete from PartnerKeyNonce n where n.seenAt < :cutoff")
    int deleteBySeenAtBefore(@Param("cutoff") Instant cutoff);
}
