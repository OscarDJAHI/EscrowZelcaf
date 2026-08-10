package com.zlecaf.escrow.service.notification;

import com.zlecaf.escrow.domain.NotificationOutboxEntry;
import com.zlecaf.escrow.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'adaptateur SMTP du port d'envoi (Story 2.4, décision T0 : « adaptateur de développement »).
 *
 * <p>Ce que cette classe garde : le code atteint bien le TRANSPORT (sinon l'inscription
 * reste infinissable dans l'IHM, ce que l'adaptateur existe pour corriger), il n'atteint
 * JAMAIS la base, et {@code deliveredAt} ne ment dans aucun des deux sens.
 */
class SmtpEmailVerificationSenderTest {

    private final JavaMailSender mailSender = mock(JavaMailSender.class);
    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final SmtpEmailVerificationSender sender =
            new SmtpEmailVerificationSender(mailSender, outbox, "no-reply@escrow.local");

    @Test
    @DisplayName("le code part par SMTP — c'est la marche qui manquait au parcours")
    void deliversTheCodeToTheTransport() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        sender.sendVerificationCode("pro@escrow.test", "123456");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        SimpleMailMessage sent = captor.getValue();
        assertThat(sent.getTo()).containsExactly("pro@escrow.test");
        assertThat(sent.getFrom()).isEqualTo("no-reply@escrow.local");
        assertThat(sent.getText())
                .as("sans le code dans le corps, le collecteur local ne sert à rien")
                .contains("123456");
    }

    @Test
    @DisplayName("le CODE ne part toujours pas dans l'outbox, transport branché ou non")
    void neverPersistsTheCode() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        sender.sendVerificationCode("pro@escrow.test", "987654");

        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
        verify(outbox, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        NotificationOutboxEntry entry = captor.getValue();
        // Même invariant que l'adaptateur d'attente : l'outbox est lisible par tout ce qui
        // lit la base, une credential qui y figurerait n'en serait plus une. Brancher un
        // transport ne relâche pas cette contrainte.
        String serialised = entry.getChannel() + "|" + entry.getRecipient() + "|" + entry.getTemplateKey();
        assertThat(serialised).doesNotContain("987654");
    }

    @Test
    @DisplayName("deliveredAt n'est daté QUE si le serveur SMTP a accepté le message")
    void marksDeliveredOnlyAfterTheTransportAccepted() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        sender.sendVerificationCode("pro@escrow.test", "123456");

        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
        verify(outbox, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(1).getDeliveredAt())
                .as("le message est parti : laisser le champ nul sous-déclarerait l'état du système")
                .isNotNull();
    }

    @Test
    @DisplayName("un transport muet laisse deliveredAt NUL — l'état réel, pas un défaut masqué")
    void leavesDeliveredAtNullWhenTheTransportFails() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new MailSendException("serveur injoignable")).when(mailSender).send(any(SimpleMailMessage.class));

        sender.sendVerificationCode("pro@escrow.test", "123456");

        // UNE seule écriture : celle de la demande. Le second save (le marquage) ne doit pas
        // avoir lieu — c'est exactement ce qui distingue « demandé » de « livré », et c'est
        // la propriété que la migration V10 documente sur ce champ.
        verify(outbox, org.mockito.Mockito.times(1)).save(any(NotificationOutboxEntry.class));
    }

    @Test
    @DisplayName("un échec de transport n'interrompt JAMAIS le flux appelant")
    void transportFailureNeverBreaksTheCaller() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new MailSendException("serveur injoignable")).when(mailSender).send(any(SimpleMailMessage.class));

        // Contrat du port : le compte est DÉJÀ persisté quand on arrive ici. Remonter
        // l'exception laisserait un compte créé dont l'utilisateur n'apprendrait rien.
        assertThatCode(() -> sender.sendVerificationCode("pro@escrow.test", "123456"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("une outbox en panne n'empêche pas la livraison")
    void stillDeliversWhenTheOutboxIsDown() {
        when(outbox.save(any(NotificationOutboxEntry.class)))
                .thenThrow(new IllegalStateException("base indisponible"));

        assertThatCode(() -> sender.sendVerificationCode("pro@escrow.test", "123456"))
                .doesNotThrowAnyException();

        // Consigner est souhaitable, livrer est l'objectif : perdre la trace ne doit pas
        // priver l'utilisateur de son code.
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("aucun envoi n'est tenté sans destinataire assemblé — garde de non-régression")
    void alwaysAddressesTheRecipientItWasGiven() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        sender.sendVerificationCode("autre@escrow.test", "111222");

        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        assertThat(captor.getValue().getTo()).containsExactly("autre@escrow.test");
        verify(mailSender, never()).send(new SimpleMailMessage[0]);
    }
}
