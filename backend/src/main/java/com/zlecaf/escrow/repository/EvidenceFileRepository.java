package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.EvidenceFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}
