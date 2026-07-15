package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.WebhookSubscription;
import jakarta.validation.constraints.NotBlank;

/** Webhook subscription payloads. */
public final class WebhookDtos {

    private WebhookDtos() {}

    public record SubscriptionRequest(
            Long companyId,
            @NotBlank String targetUrl,
            @NotBlank String secretKey,
            @NotBlank String eventType) {}

    public record SubscriptionDto(
            Long id,
            Long companyId,
            String targetUrl,
            String eventType,
            boolean active) {
        public static SubscriptionDto from(WebhookSubscription s) {
            return new SubscriptionDto(s.getId(), s.getCompanyId(), s.getTargetUrl(),
                    s.getEventType(), s.isActive());
        }
    }
}
