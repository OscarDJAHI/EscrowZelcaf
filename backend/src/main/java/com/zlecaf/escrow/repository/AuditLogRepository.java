package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.AuditLog;
import org.springframework.data.domain.Pageable;
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

    /**
     * Bounded, <em>reverse</em>-chronological overload: returns the {@code LIMIT}
     * MOST RECENT trail rows first. Ordering derives from the method name, so the
     * {@link Pageable} must be UNSORTED and contributes only the {@code LIMIT}
     * (e.g. {@code PageRequest.of(0, MAX_LIST_RESULTS)}). A plain {@code ...Asc}
     * limit would keep the OLDEST rows and silently drop the recent tail; the
     * caller queries this DESC overload then re-reverses in memory to restore the
     * causal ascending order while keeping the newest N.
     */
    List<AuditLog> findByTransactionIdOrderByTimestampDescIdDesc(Long transactionId, Pageable page);

    /**
     * True iff this transaction was ever disputed — the durable discriminator
     * between "the dispute you are replaying was already arbitrated"
     * (DISPUTE_ALREADY_RESOLVED) and "this transaction simply ended"
     * (TRANSACTION_TERMINAL). No {@code Dispute} entity exists: a dispute is only
     * {@code EscrowState.DISPUTED}, and the state alone cannot answer this, since
     * RELEASED is reachable both by arbitration and by a plain delivery
     * confirmation. The audit trail can, because it is append-only and never purged.
     *
     * <p><strong>Why {@code next_state} alone is sound.</strong> The dispute
     * <em>outcome</em> lives in the JSONB payload, which no derived query can
     * reach — but it does not need to. Every writer sets {@code next_state} to
     * either the state actually reached (a successful transition) or the state the
     * transaction was already in (a rejection or an evidence action, where
     * {@code previous == next == current}). So a row with {@code next_state =
     * 'DISPUTED'} means the transaction either became DISPUTED or already was: in
     * both cases a dispute existed. No row can carry it otherwise.
     *
     * <p>Indexed by {@code idx_audit_transaction} on {@code audit_logs(transaction_id)}.
     */
    boolean existsByTransactionIdAndNextState(Long transactionId, String nextState);
}
