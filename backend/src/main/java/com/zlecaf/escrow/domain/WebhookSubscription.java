package com.zlecaf.escrow.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Third-party partner registration for automatic state-change callbacks.
 */
@Entity
@Table(name = "webhook_subscriptions")
public class WebhookSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id")
    private Long companyId;

    @Column(name = "target_url", nullable = false, length = 255)
    private String targetUrl;

    /** Shared secret used to sign the HMAC-SHA256 payload signature. */
    @Column(name = "secret_key", nullable = false, length = 255)
    private String secretKey;

    /** Event filter: a specific {@link EscrowState} name, or "ALL". */
    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }

    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
