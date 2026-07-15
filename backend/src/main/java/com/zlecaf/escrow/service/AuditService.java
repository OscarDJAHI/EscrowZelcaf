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
