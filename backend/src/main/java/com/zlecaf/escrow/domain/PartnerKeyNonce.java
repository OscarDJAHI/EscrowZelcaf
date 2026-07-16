package com.zlecaf.escrow.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One nonce already seen for a given inbound {@code keyId} — the anti-replay
 * record for partner deposits (Epic 3). Uniqueness is scoped per key-id by the
 * {@code uq_partner_key_nonces_key_nonce} constraint, so the same nonce may
 * recur under two distinct key-ids but never twice under one. {@code keyId}
 * references {@link PartnerHmacKey#getKeyId()} (the textual key, not its id).
 */
@Entity
@Table(name = "partner_key_nonces")
public class PartnerKeyNonce {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_id", nullable = false, length = 100)
    private String keyId;

    @Column(name = "nonce", nullable = false, length = 255)
    private String nonce;

    @Column(name = "seen_at", nullable = false, updatable = false)
    private Instant seenAt;

    /** JPA requires a no-arg constructor. */
    public PartnerKeyNonce() {}

    /**
     * Records a freshly consumed nonce for {@code keyId}. {@code seenAt} is stamped
     * by {@link #onCreate()} at persist time, so it is never set by the caller.
     */
    public PartnerKeyNonce(String keyId, String nonce) {
        this.keyId = keyId;
        this.nonce = nonce;
    }

    @PrePersist
    void onCreate() {
        if (seenAt == null) seenAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    public Instant getSeenAt() { return seenAt; }
    public void setSeenAt(Instant seenAt) { this.seenAt = seenAt; }
}
