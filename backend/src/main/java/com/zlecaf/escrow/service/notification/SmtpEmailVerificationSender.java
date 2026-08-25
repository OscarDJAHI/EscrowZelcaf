package com.zlecaf.escrow.service.notification;

import com.zlecaf.escrow.domain.NotificationOutboxEntry;
import com.zlecaf.escrow.repository.NotificationOutboxRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Adaptateur SMTP du port d'envoi — <b>l'« adaptateur de développement » que la décision T0
 * de la Story 2.4 avait retenu et qui n'avait pas été livré</b>.
 *
 * <p><b>Ce qu'il débloque.</b> Avec le seul {@link OutboxEmailVerificationSender}, le code
 * ne quitte jamais le processus : l'inscription est complète côté serveur mais
 * <i>infinissable dans l'IHM</i>, faute de pouvoir lire l'OTP. Cet adaptateur pointé sur un
 * collecteur SMTP local (Mailpit, {@code infra/docker-compose.yml}) rend le parcours
 * démontrable de bout en bout sans rien concéder sur la sécurité.
 *
 * <p><b>Il ne préempte pas le spike 8.1.</b> SMTP est un protocole, pas un fournisseur. Le
 * choix du prestataire de production — délivrabilité par corridor ZLECAf, coûts, conformité
 * — reste entier et appartient à la Story 8.1, qui branchera son propre adaptateur ou
 * réutilisera celui-ci en changeant l'hôte.
 *
 * <p><b>Il respecte l'interdiction ferme de T0.</b> Le code sort de l'application par le
 * port, comme exigé, et se lit dans l'UI du collecteur — un processus séparé. Aucun endpoint
 * de lecture d'OTP n'est ajouté, ni ici ni ailleurs ; {@code DevApiSurfaceIntegrationTest} et
 * {@code ProductionApiSurfaceIntegrationTest} continuent d'interdire cette surface.
 *
 * <p><b>Pourquoi il écrit AUSSI dans l'outbox.</b> AD-22 veut l'envoi persisté ; le laisser
 * de côté ferait de l'activation du transport une perte de traçabilité. La ligne est donc
 * écrite comme avant, et {@code deliveredAt} n'est renseigné <i>que</i> si le serveur SMTP a
 * accepté le message. C'est ce qui redonne son sens au champ : nul = demandé mais non livré,
 * daté = remis à un transport. La migration V10 le disait déjà.
 *
 * <p><b>Le corps ne traverse pas la base.</b> Le code voyage dans le message SMTP et nulle
 * part ailleurs : ni colonne d'outbox, ni journal en niveau {@code info}. Une credential
 * lisible par tout ce qui lit la base n'en est plus une.
 */
@Component
@ConditionalOnProperty(name = "escrow.mail.transport", havingValue = "smtp")
public class SmtpEmailVerificationSender implements EmailVerificationSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailVerificationSender.class);

    static final String TEMPLATE_KEY = "auth.email-verification";
    static final String CHANNEL = "EMAIL";

    private final JavaMailSender mailSender;
    private final NotificationOutboxRepository outbox;
    private final String from;

    public SmtpEmailVerificationSender(
            JavaMailSender mailSender,
            NotificationOutboxRepository outbox,
            @org.springframework.beans.factory.annotation.Value("${escrow.mail.from:no-reply@escrow.local}") String from) {
        this.mailSender = mailSender;
        this.outbox = outbox;
        this.from = from;
    }

    /**
     * {@inheritDoc}
     *
     * <p>N'échoue jamais, conformément au contrat du port : l'inscription est déjà persistée
     * quand on arrive ici, et remonter une exception laisserait un compte créé dont
     * l'utilisateur n'apprendrait rien. Un transport muet se lit dans l'outbox
     * ({@code deliveredAt} nul), pas dans une 500 rendue à l'inscrivant.
     */
    @Override
    public void sendVerificationCode(String email, String code) {
        NotificationOutboxEntry entry = new NotificationOutboxEntry();
        entry.setChannel(CHANNEL);
        entry.setRecipient(email);
        entry.setTemplateKey(TEMPLATE_KEY);

        try {
            entry = outbox.save(entry);
        } catch (RuntimeException persistenceFailure) {
            // Consigner est souhaitable, livrer est l'objectif : on poursuit sans la trace.
            log.warn("Consignation de l'envoi de vérification impossible", persistenceFailure);
            entry = null;
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(email);
            message.setSubject("Escrow — code de vérification");
            message.setText("Votre code de vérification est : " + code
                    + "\n\nIl expire sous peu. Si vous n'êtes pas à l'origine de cette demande,"
                    + " ignorez ce message.");
            mailSender.send(message);
        } catch (RuntimeException transportFailure) {
            // Volontairement large : MailException en couvre l'essentiel, mais un
            // MailSender mal configuré lève aussi des IllegalStateException / IllegalArgument
            // au moment de l'envoi. Le contrat du port est de ne jamais casser l'appelant —
            // le restreindre à MailException rouvrirait ce trou par le côté.
            log.warn("Remise SMTP du code de vérification impossible pour {}", email, transportFailure);
            return; // deliveredAt reste nul : l'état réel du système, pas un défaut masqué.
        }

        if (entry != null) {
            try {
                entry.setDeliveredAt(Instant.now());
                outbox.save(entry);
            } catch (RuntimeException persistenceFailure) {
                // Le message EST parti ; ne pas le renvoyer parce que la trace a échoué.
                log.warn("Marquage de livraison impossible pour {}", email, persistenceFailure);
            }
        }
    }
}
