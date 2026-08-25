package com.zlecaf.escrow.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Envoi de notification PERSISTÉ avant livraison (AD-22).
 *
 * <p>Décision T0 de la Story 2.4 : le transport e-mail n'existe pas et appartient au spike
 * 8.1, seul juge du fournisseur et de la délivrabilité par corridor ZLECAf. L'envoi est
 * donc consigné ici, comme le backlog le prévoit déjà ailleurs.
 *
 * <p><b>Une ligne ici n'est PAS un e-mail reçu</b> tant qu'aucun adaptateur de production
 * n'est branché. {@code deliveredAt} reste alors nul : c'est l'état réel du système, pas un
 * défaut à masquer.
 *
 * <p><b>Le corps ne contient jamais le code.</b> L'outbox est lisible par tout ce qui lit
 * la base ; le code ne doit exister en clair que le temps d'un appel au port. Le rendu du
 * message appartient à l'adaptateur, qui reçoit le code hors de cette ligne.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false, length = 150)
    private String recipient;

    @Column(name = "template_key", nullable = false, length = 100)
    private String templateKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() { return id; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getTemplateKey() { return templateKey; }
    public void setTemplateKey(String templateKey) { this.templateKey = templateKey; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
