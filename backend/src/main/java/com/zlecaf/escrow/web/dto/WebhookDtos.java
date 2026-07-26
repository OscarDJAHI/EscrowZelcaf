package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.WebhookSubscription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Webhook subscription payloads. */
public final class WebhookDtos {

    private WebhookDtos() {}

    public record SubscriptionRequest(
            Long companyId,
            @NotBlank String targetUrl,
            // Plafond explicite : la colonne est passée en TEXT (Story 1.7, l'enveloppe
            // chiffrée dépasse 255) et ne borne plus rien. Sans cette limite, un compte
            // authentifié pourrait stocker des mégaoctets par abonnement — régression
            // introduite par le changement de type, pas un durcissement nouveau.
            @NotBlank @Size(max = 255) String secretKey,
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
