package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.LegalConsent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LegalConsentRepository extends JpaRepository<LegalConsent, Long> {

    List<LegalConsent> findByUserIdOrderByConsentedAtDesc(Long userId);
}
