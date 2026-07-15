package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.EvidenceFile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvidenceFileRepository extends JpaRepository<EvidenceFile, Long> {
}
