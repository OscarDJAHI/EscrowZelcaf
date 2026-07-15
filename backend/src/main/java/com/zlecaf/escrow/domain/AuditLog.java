package com.zlecaf.escrow.domain;

import com.fasterxml.jackson.databind.JsonNode;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * Immutable compliance audit trail. One row is appended for every attempted
 * state transition (successful or rejected). Rows are never updated or deleted.
 */
@Entity
@Table(name = "audit_logs")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "action_by")
    private Long actionBy;

    @Column(name = "previous_state", length = 50)
    private String previousState;

    @Column(name = "next_state", length = 50)
    private String nextState;

    /** Full technical request history / context for this transition. */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    @PrePersist
    void onCreate() {
        if (timestamp == null) timestamp = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public Long getActionBy() { return actionBy; }
    public void setActionBy(Long actionBy) { this.actionBy = actionBy; }

    public String getPreviousState() { return previousState; }
    public void setPreviousState(String previousState) { this.previousState = previousState; }

    public String getNextState() { return nextState; }
    public void setNextState(String nextState) { this.nextState = nextState; }

    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode payload) { this.payload = payload; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
