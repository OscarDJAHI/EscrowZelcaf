package com.zlecaf.escrow.service.notification;

import com.zlecaf.escrow.domain.NotificationOutboxEntry;
import com.zlecaf.escrow.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptateur d'attente du port d'envoi : consigne dans l'outbox (AD-22) et journalise.
 *
 * <p>Il tient la place que la Story 8.1 occupera avec un vrai fournisseur. Il est
 * volontairement le SEUL adaptateur enregistré aujourd'hui, et il ne prétend rien : une
 * ligne d'outbox avec {@code deliveredAt} nul décrit exactement l'état du système —
 * l'envoi est demandé, il n'est pas livré.
 *
 * <p><b>Le code ne va pas dans l'outbox</b>, seulement dans le journal de niveau debug, et
 * jamais en information : les journaux d'application partent en agrégation là où l'outbox
 * reste en base, et une credential ne doit traverser ni l'une ni l'autre sans nécessité.
 *
 * <p><b>Il n'échoue jamais</b> : l'inscription est déjà persistée quand on l'appelle, et
 * la faire remonter une exception laisserait un compte créé dont l'utilisateur n'apprendrait
 * rien — le cas que l'AC de 8.1 nomme explicitement.
 */
@Component
public class OutboxEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(OutboxEmailVerificationSender.class);

    /** Clé de gabarit ; le rendu appartiendra à l'adaptateur de production (Story 8.1). */
    static final String TEMPLATE_KEY = "auth.email-verification";
    static final String CHANNEL = "EMAIL";

    private final NotificationOutboxRepository outbox;

    public OutboxEmailVerificationSender(NotificationOutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Override
    public void sendVerificationCode(String email, String code) {
        try {
            NotificationOutboxEntry entry = new NotificationOutboxEntry();
            entry.setChannel(CHANNEL);
            entry.setRecipient(email);
            entry.setTemplateKey(TEMPLATE_KEY);
            outbox.save(entry);
        } catch (RuntimeException transportFailure) {
            // Journalisé et absorbé — voir le contrat du port.
            log.warn("Consignation de l'envoi de vérification impossible", transportFailure);
        }
        log.debug("Code de vérification émis pour {} (non livré : aucun transport branché avant la Story 8.1)", email);
    }
}
