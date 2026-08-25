package com.zlecaf.escrow.service.notification;

import com.zlecaf.escrow.domain.NotificationOutboxEntry;
import com.zlecaf.escrow.repository.NotificationOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L'adaptateur d'attente du port d'envoi (Story 2.4, décision T0).
 *
 * <p><b>Pourquoi cette classe existe séparément.</b> Les tests d'intégration de
 * l'inscription substituent au port un adaptateur de CAPTURE, pour lire le code — cet
 * adaptateur-ci n'y tourne donc jamais, et une assertion sur l'outbox posée là-bas ne
 * regarderait rien. C'était le cas d'une première version : elle vérifiait le contenu de
 * l'outbox dans un contexte où aucune ligne n'était écrite, et échouait pour cette raison
 * plutôt que pour un défaut. Le seul endroit d'où l'on peut observer cet adaptateur est
 * celui où il est réellement appelé.
 */
class OutboxEmailVerificationSenderTest {

    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final OutboxEmailVerificationSender sender = new OutboxEmailVerificationSender(outbox);

    @Test
    @DisplayName("consigne l'envoi, non livré — c'est l'état réel avant la Story 8.1")
    void recordsAnUndeliveredEntry() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        sender.sendVerificationCode("pro@escrow.test", "123456");

        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
        verify(outbox).save(captor.capture());
        NotificationOutboxEntry entry = captor.getValue();
        assertThat(entry.getRecipient()).isEqualTo("pro@escrow.test");
        assertThat(entry.getChannel()).isEqualTo("EMAIL");
        assertThat(entry.getDeliveredAt())
                .as("aucun transport n'est branché : prétendre le contraire serait le seul défaut ici")
                .isNull();
    }

    @Test
    @DisplayName("le CODE ne part jamais dans l'outbox")
    void neverPersistsTheCode() {
        when(outbox.save(any(NotificationOutboxEntry.class))).thenAnswer(inv -> inv.getArgument(0));

        sender.sendVerificationCode("pro@escrow.test", "987654");

        ArgumentCaptor<NotificationOutboxEntry> captor = ArgumentCaptor.forClass(NotificationOutboxEntry.class);
        verify(outbox).save(captor.capture());
        NotificationOutboxEntry entry = captor.getValue();
        // L'outbox est lisible par tout ce qui lit la base. Un code qui y figurerait serait
        // une credential en clair, à la portée d'une sauvegarde égarée ou d'un accès en
        // lecture accordé pour tout autre motif.
        String serialised = entry.getChannel() + "|" + entry.getRecipient() + "|" + entry.getTemplateKey();
        assertThat(serialised).doesNotContain("987654");
    }

    @Test
    @DisplayName("un échec de consignation n'interrompt JAMAIS le flux appelant")
    void transportFailureNeverBreaksTheCaller() {
        when(outbox.save(any(NotificationOutboxEntry.class)))
                .thenThrow(new IllegalStateException("base indisponible"));

        // Le compte est DÉJÀ persisté quand le port est appelé. Remonter l'exception le
        // laisserait créé sans que l'utilisateur n'apprenne rien — le cas que l'AC de la
        // Story 8.1 nomme explicitement.
        assertThatCode(() -> sender.sendVerificationCode("pro@escrow.test", "123456"))
                .doesNotThrowAnyException();
    }
}
