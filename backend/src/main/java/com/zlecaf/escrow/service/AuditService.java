package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.EscrowEvent;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the immutable compliance audit trail. Successful transitions are logged
 * within the caller's transaction (atomic with the state change); rejected
 * attempts are logged in a <em>separate</em> transaction so the record survives
 * the rollback that the rejection triggers.
 */
@Service
public class AuditService {

    private final AuditLogRepository auditLogs;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogs, ObjectMapper objectMapper) {
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    /** Log a committed transition; joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(Long transactionId, Long actorId, ParticipantRole actorRole,
                              EscrowEvent event, EscrowState previous, EscrowState next) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("outcome", "SUCCESS");
        // A null event denotes the genesis transition (contract creation).
        payload.put("event", event == null ? "CREATE" : event.name());
        payload.put("actorRole", actorRole.name());
        save(transactionId, actorId, previous, next, payload);
    }

    /**
     * Log a rejected transition in its own transaction so it is durably recorded
     * even though the caller is about to throw and roll back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long transactionId, Long actorId, ParticipantRole actorRole,
                              EscrowEvent event, EscrowState current, String reason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("outcome", "REJECTED");
        payload.put("event", event.name());
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("reason", reason);
        // No state change on rejection: previous == next == current.
        save(transactionId, actorId, current, current, payload);
    }

    /**
     * Log an evidence deposit within the caller's transaction (atomic with the
     * {@code evidence_files} row). A deposit is not a state change, so
     * {@code previous == next == currentState}. The JSONB payload is schemaless:
     * no schema change is needed to carry the extra evidence context.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidenceAdded(Long transactionId, Long actorId, ParticipantRole actorRole,
                                    EscrowState currentState, Long evidenceId, String sha256,
                                    String clientCapturedAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "EVIDENCE_ADDED");
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("evidenceId", evidenceId);
        payload.put("sha256", sha256);
        if (clientCapturedAt != null && !clientCapturedAt.isBlank()) {
            payload.put("clientCapturedAt", clientCapturedAt);
        }
        save(transactionId, actorId, currentState, currentState, payload);
    }

    /**
     * Log an evidence withdrawal within the caller's transaction (atomic with the
     * {@code ACTIVE -> WITHDRAWN} flip on the {@code evidence_files} row). Like a
     * deposit, a withdrawal is not a state change, so
     * {@code previous == next == currentState}. The payload carries {@code evidenceId}
     * (which correlates with the {@code EVIDENCE_ADDED} entry that holds the
     * {@code sha256}) but no hash: withdrawal is a metadata-only operation and must
     * not read the storage binary to recompute one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidenceWithdrawn(Long transactionId, Long actorId, ParticipantRole actorRole,
                                        EscrowState currentState, Long evidenceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "EVIDENCE_WITHDRAWN");
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("evidenceId", evidenceId);
        save(transactionId, actorId, currentState, currentState, payload);
    }

    private void save(Long transactionId, Long actorId, EscrowState previous, EscrowState next, ObjectNode payload) {
        AuditLog logEntry = new AuditLog();
        logEntry.setTransactionId(transactionId);
        logEntry.setActionBy(actorId);
        logEntry.setPreviousState(previous == null ? null : previous.name());
        logEntry.setNextState(next == null ? null : next.name());
        logEntry.setPayload(payload);
        auditLogs.save(logEntry);
    }
}
