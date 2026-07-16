package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.PartnerHmacKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PartnerHmacKeyRepository extends JpaRepository<PartnerHmacKey, Long> {

    /**
     * Resolves an inbound partner credential by its public key-id, admitting only
     * ACTIVE keys: a disabled key ({@code active=false}) yields an empty result,
     * so revocation is immediate. The returned key carries the {@code companyId}
     * that attributes the partner deposit. Never consults the outbound webhook
     * secret. Backed by the {@code key_id} UNIQUE constraint (at most one match).
     */
    Optional<PartnerHmacKey> findByKeyIdAndActiveTrue(String keyId);
}
