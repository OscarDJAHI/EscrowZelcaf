package com.zlecaf.escrow.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import jakarta.persistence.*;

import java.nio.charset.StandardCharsets;
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

    /**
     * Plancher de robustesse du secret entrant, en OCTETS UTF-8. Valeur de
     * référence de la plateforme : le CHECK {@code ck_partner_hmac_keys_secret_len}
     * (V5, reformulé en V7 puis resserré en V8) la double côté base pour les
     * écritures en SQL direct, et {@code SecretsEncryptionBootstrap} la relit ici
     * plutôt que de la recopier. Compter en octets et non en caractères est
     * délibéré (régression prouvée en Story 1.6 : 32 caractères accentués ne font
     * pas 32 octets).
     */
    public static final int MIN_SECRET_BYTES = 32;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque public identifier the partner presents; UNIQUE across all keys. */
    @Column(name = "key_id", nullable = false, unique = true, length = 100)
    private String keyId;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    /**
     * Shared secret used to verify the inbound HMAC-SHA256 signature. Chiffré au
     * repos depuis la Story 1.7 : la colonne ne contient qu'une enveloppe
     * {@code esc:1:…}, jamais le clair — mais l'attribut, lui, reste le clair (il
     * doit signer/vérifier, un hachage ne conviendrait pas).
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "secret_key", nullable = false)
    private String secretKey;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        requireStrongSecret();
    }

    @PreUpdate
    void onUpdate() {
        // Sans ce second point de contrôle, une rotation de secret pourrait
        // réintroduire par UPDATE ce que l'admission refuse à l'insertion.
        requireStrongSecret();
    }

    private void requireStrongSecret() {
        int bytes = secretKey == null ? 0 : secretKey.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            // Le secret lui-même n'apparaît jamais dans le message (il finirait en log).
            throw new IllegalArgumentException("partner_hmac_keys.secret_key : secret trop court ("
                    + bytes + " octets UTF-8, minimum " + MIN_SECRET_BYTES + ")");
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    // WRITE_ONLY: the secret is never serialized out (JSON/log/API), but stays bindable
    // on input so a future provisioning path (Story 3.2 / admin) can still set it.
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
