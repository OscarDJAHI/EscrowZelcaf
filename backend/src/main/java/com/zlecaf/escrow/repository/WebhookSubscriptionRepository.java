package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {

    List<WebhookSubscription> findByActiveTrue();

    /**
     * Scopes a listing to one company. Added by the Epic 1 retrospective (2026-07-27):
     * {@code WebhookController.list()} called {@code findAll()}, so any authenticated
     * user read every company's callback URLs. The guard belongs here, in a derived
     * query the caller cannot widen, rather than in a filter the next endpoint would
     * forget to reapply.
     */
    List<WebhookSubscription> findByCompanyId(Long companyId);
}
