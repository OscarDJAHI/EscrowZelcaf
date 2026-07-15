package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, Long> {

    List<WebhookSubscription> findByActiveTrue();
}
