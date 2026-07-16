package com.zlecaf.escrow.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Inbound HMAC-SHA256 credential for a machine-to-machine partner (Epic 3).
 * Resolved by its opaque {@code keyId} to exactly one {@link Company}; the
 * {@code secretKey} signs incoming partner deposits. Deliberately distinct from
 * {@link WebhookSubscription#getSecretKey()} — the outbound webhook secret is
 * never reused for inbound authentication.
 */
@Entity
@Table(name = "partner_hmac_keys")
public class PartnerHmacKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque public identifier the partner presents; UNIQUE across all keys. */
    @Column(name = "key_id", nullable = false, unique = true, length = 100)
    private String keyId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /** Shared secret used to verify the inbound HMAC-SHA256 signature. */
    @Column(name = "secret_key", nullable = false, length = 255)
    private String secretKey;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
