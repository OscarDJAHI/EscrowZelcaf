package com.zlecaf.escrow.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Code de vérification e-mail à 6 chiffres (Story 2.4, FR-P27).
 *
 * <p><b>Une seule ligne par utilisateur.</b> Un renvoi remplace le code courant. Laisser
 * plusieurs codes valides en même temps multiplierait les chances d'un attaquant à chaque
 * renvoi, et ferait porter le plafond de tentatives sur un code au lieu du compte.
 *
 * <p><b>Le code n'est jamais stocké en clair</b> — c'est une credential. Le hash est
 * produit par le {@code PasswordEncoder} déjà en service ; sa lenteur, gênante ailleurs,
 * est ici un atout : elle freine le forçage autant que le plafond de tentatives.
 */
@Entity
@Table(name = "email_verification_codes")
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Tentatives de vérification du code courant. Persisté : un compteur en mémoire se réarmerait au redémarrage. */
    @Column(nullable = false)
    private int attempts;

    /** Envois consommés dans la fenêtre courante — ferme le canal d'envoi vers un tiers ouvert par l'anti-énumération. */
    @Column(name = "send_count", nullable = false)
    private int sendCount;

    @Column(name = "send_window_start", nullable = false)
    private Instant sendWindowStart;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }

    public int getSendCount() { return sendCount; }
    public void setSendCount(int sendCount) { this.sendCount = sendCount; }

    public Instant getSendWindowStart() { return sendWindowStart; }
    public void setSendWindowStart(Instant sendWindowStart) { this.sendWindowStart = sendWindowStart; }

    public Instant getLastSentAt() { return lastSentAt; }
    public void setLastSentAt(Instant lastSentAt) { this.lastSentAt = lastSentAt; }

    public Instant getCreatedAt() { return createdAt; }
}
