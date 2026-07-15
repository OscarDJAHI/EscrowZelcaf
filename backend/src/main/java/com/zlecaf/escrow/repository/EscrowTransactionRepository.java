package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.EscrowTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface EscrowTransactionRepository extends JpaRepository<EscrowTransaction, Long> {

    /**
     * Fetch a transaction taking a {@code SELECT ... FOR UPDATE} row lock. This
     * serialises concurrent state-transition attempts on the same transaction,
     * preventing double-spend / lost-update race conditions on the escrow state.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from EscrowTransaction t where t.id = :id")
    Optional<EscrowTransaction> findByIdForUpdate(@Param("id") Long id);

    @Query("select t from EscrowTransaction t where t.buyerId = :userId or t.sellerId = :userId order by t.updatedAt desc")
    List<EscrowTransaction> findAllForParticipant(@Param("userId") Long userId);
}
