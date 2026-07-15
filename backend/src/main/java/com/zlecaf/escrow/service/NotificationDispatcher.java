package com.zlecaf.escrow.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges committed state changes to the asynchronous notification fabric. Fires
 * only <em>after</em> the escrow transaction commits, so partners and the
 * orchestrator are never told about a transition that was subsequently rolled
 * back — a correctness requirement for financial notifications.
 */
@Component
public class NotificationDispatcher {

    private final EventPublisher eventPublisher;
    private final WebhookService webhookService;

    public NotificationDispatcher(EventPublisher eventPublisher, WebhookService webhookService) {
        this.eventPublisher = eventPublisher;
        this.webhookService = webhookService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStateChanged(StateChangedEvent event) {
        // Decoupled fan-out: RabbitMQ for the orchestrator, HTTP for partners.
        eventPublisher.publish(event);
        webhookService.dispatch(event);
    }
}
