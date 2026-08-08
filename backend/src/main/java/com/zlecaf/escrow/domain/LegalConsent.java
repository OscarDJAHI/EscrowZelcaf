package com.zlecaf.escrow.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Consentement aux documents légaux, horodaté par le SERVEUR (Story 2.4, FR-P27).
 *
 * <p>Historique et non état : une ligne par version acceptée. Écraser le consentement
 * précédent détruirait la preuve de ce qui a été accepté et quand — or c'est exactement ce
 * que cette table existe pour produire.
 *
 * <p>{@code consentedAt} vient de l'horloge serveur (AD-11). Une date fournie par le client
 * serait une date choisie par le signataire lui-même.
 */
@Entity
@Table(name = "legal_consents")
public class LegalConsent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "document_version", nullable = false, length = 50)
    private String documentVersion;

    @Column(name = "consented_at", nullable = false, updatable = false)
    private Instant consentedAt;

    public Long getId() { return id; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public String getDocumentVersion() { return documentVersion; }
    public void setDocumentVersion(String documentVersion) { this.documentVersion = documentVersion; }

    public Instant getConsentedAt() { return consentedAt; }
    public void setConsentedAt(Instant consentedAt) { this.consentedAt = consentedAt; }
}
