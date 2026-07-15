package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByTransactionIdOrderByTimestampAsc(Long transactionId);
}
