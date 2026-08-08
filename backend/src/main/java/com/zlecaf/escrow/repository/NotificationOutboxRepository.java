package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.NotificationOutboxEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntry, Long> {

    List<NotificationOutboxEntry> findByRecipientOrderByCreatedAtDesc(String recipient);
}
