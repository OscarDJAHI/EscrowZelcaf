package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EscrowEvent;
import com.zlecaf.escrow.domain.EscrowState;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal Spring application event emitted when an escrow transaction changes
 * state. Consumed after commit to fan out to RabbitMQ (for the n8n orchestrator)
 * and to signed partner webhooks. This is the canonical notification payload.
 */
public record StateChangedEvent(
        Long transactionId,
        EscrowState previousState,
        EscrowState newState,
        EscrowEvent event,
        Long actorUserId,
        BigDecimal amount,
        String currency,
        Instant occurredAt) {
}
