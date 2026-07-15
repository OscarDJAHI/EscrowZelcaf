package com.zlecaf.escrow.service;

import com.zlecaf.escrow.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes state-change events onto RabbitMQ for asynchronous consumption by
 * the n8n orchestrator. Broker unavailability is logged but never propagated —
 * the financial transition has already been committed and must not be rolled
 * back by a downstream messaging outage (at-least-once is best-effort here).
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    public EventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(StateChangedEvent event) {
        String routingKey = RabbitConfig.ROUTING_PREFIX + event.newState().name();
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.EXCHANGE, routingKey, event);
            log.info("Published state-change event tx={} {} -> {} to '{}'",
                    event.transactionId(), event.previousState(), event.newState(), routingKey);
        } catch (AmqpException ex) {
            log.warn("Failed to publish event for tx={} to RabbitMQ (broker down?): {}",
                    event.transactionId(), ex.getMessage());
        }
    }
}
