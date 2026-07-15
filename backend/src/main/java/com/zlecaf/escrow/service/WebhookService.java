package com.zlecaf.escrow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.WebhookSubscription;
import com.zlecaf.escrow.repository.WebhookSubscriptionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Dispatches signed state-change notifications to registered partner webhooks.
 * Every payload is signed with the subscription's shared secret (HMAC-SHA256)
 * and delivered in the {@code X-Escrow-Signature} header. Dispatch is async and
 * per-subscription failures are isolated so one bad endpoint cannot block others.
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    public static final String SIGNATURE_HEADER = "X-Escrow-Signature";

    private final WebhookSubscriptionRepository subscriptions;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WebhookService(WebhookSubscriptionRepository subscriptions, ObjectMapper objectMapper) {
        this.subscriptions = subscriptions;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    /**
     * Fan out an event to all active subscriptions whose filter matches. Runs on
     * a background thread; the caller (an after-commit listener) does not block.
     */
    @Async("webhookExecutor")
    public void dispatch(StateChangedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("Unable to serialise webhook payload for tx={}", event.transactionId(), e);
            return;
        }

        List<WebhookSubscription> targets = subscriptions.findByActiveTrue().stream()
                .filter(s -> matches(s.getEventType(), event.newState().name()))
                .toList();

        for (WebhookSubscription sub : targets) {
            deliver(sub, payload, event);
        }
    }

    private boolean matches(String subscribedEventType, String stateName) {
        return "ALL".equalsIgnoreCase(subscribedEventType) || subscribedEventType.equalsIgnoreCase(stateName);
    }

    private void deliver(WebhookSubscription sub, String payload, StateChangedEvent event) {
        String signature = HmacSigner.sign(payload, sub.getSecretKey());
        try {
            restClient.post()
                    .uri(sub.getTargetUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, signature)
                    .header(HttpHeaders.USER_AGENT, "Escrow-Webhook/1.0")
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Webhook delivered tx={} state={} -> {}",
                    event.transactionId(), event.newState(), sub.getTargetUrl());
        } catch (Exception ex) {
            // Isolate failures: a partner outage must not affect other deliveries
            // nor the committed transaction. Production would enqueue for retry.
            log.warn("Webhook delivery failed tx={} -> {}: {}",
                    event.transactionId(), sub.getTargetUrl(), ex.getMessage());
        }
    }
}
