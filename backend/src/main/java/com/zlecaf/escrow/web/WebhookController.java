package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.WebhookSubscription;
import com.zlecaf.escrow.repository.WebhookSubscriptionRepository;
import com.zlecaf.escrow.web.dto.WebhookDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Partner self-service for outbound webhook subscriptions. Partners register a
 * callback URL and a shared secret; every matching state change is then
 * delivered to them signed with HMAC-SHA256 (see {@code WebhookService}).
 */
@RestController
@RequestMapping("/api/v1/webhooks/subscriptions")
public class WebhookController {

    private final WebhookSubscriptionRepository subscriptions;

    public WebhookController(WebhookSubscriptionRepository subscriptions) {
        this.subscriptions = subscriptions;
    }

    @PostMapping
    public ResponseEntity<SubscriptionDto> create(@Valid @RequestBody SubscriptionRequest request) {
        WebhookSubscription sub = new WebhookSubscription();
        sub.setCompanyId(request.companyId());
        sub.setTargetUrl(request.targetUrl());
        sub.setSecretKey(request.secretKey());
        sub.setEventType(request.eventType().toUpperCase());
        sub.setActive(true);
        sub = subscriptions.save(sub);
        return ResponseEntity.status(HttpStatus.CREATED).body(SubscriptionDto.from(sub));
    }

    @GetMapping
    public List<SubscriptionDto> list() {
        return subscriptions.findAll().stream().map(SubscriptionDto::from).toList();
    }
}
