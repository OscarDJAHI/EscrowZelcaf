package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EvidenceFileRepository extends JpaRepository<EvidenceFile, Long> {

    /**
     * All evidence rows for a transaction in server-time chronological order.
     * No status filter: WITHDRAWN rows stay visible (AD-4). Backed by the
     * {@code (transaction_id, created_at)} index from migration V2.
     * {@code id ASC} is a deterministic tie-breaker for rows sharing the same
     * {@code created_at} (e.g. a multi-file batch deposit stamped within the same
     * instant), so the list order is stable and monotonic with insertion.
     */
    List<EvidenceFile> findByTransactionIdOrderByCreatedAtAscIdAsc(Long transactionId);

    /**
     * Sealed lookup keyed on <em>both</em> the evidence id and its owning
     * transaction: the anti-IDOR guard for download. A piece that does not exist,
     * or that belongs to a different transaction, yields an empty result — the
     * caller maps that to a 404 without ever revealing the piece's existence
     * elsewhere. Membership is never inferred from an unsealed {@code findById}.
     */
    Optional<EvidenceFile> findByIdAndTransactionId(Long id, Long transactionId);

    /**
     * Counts the evidence rows of a transaction in a given status. Read behind the
     * transaction's pessimistic row lock, it backs the dispute evidence floor
     * (FR-6): a withdrawal that would drop the {@code ACTIVE} count below the
     * minimum is refused, and because both contending withdrawals serialise on
     * the same locked transaction row, the loser recounts the committed state.
     */
    long countByTransactionIdAndStatus(Long transactionId, EvidenceStatus status);
}
