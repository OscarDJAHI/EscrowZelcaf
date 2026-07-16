package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByTransactionIdOrderByTimestampAsc(Long transactionId);

    /**
     * Compliance trail in causal order. Several audit rows written inside one
     * transaction (e.g. an OPEN_DISPUTE transition plus its EVIDENCE_ADDED rows)
     * can share the same {@code timestamp}; the monotonic identity is the
     * tiebreaker so the transition always precedes the evidence it caused.
     */
    List<AuditLog> findByTransactionIdOrderByTimestampAscIdAsc(Long transactionId);
}
