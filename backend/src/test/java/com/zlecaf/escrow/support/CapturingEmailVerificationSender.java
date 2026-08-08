package com.zlecaf.escrow.support;

import com.zlecaf.escrow.service.notification.EmailVerificationSender;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptateur de test du port d'envoi : garde le dernier code émis par destinataire.
 *
 * <p><b>C'est le seul chemin par lequel un test peut connaître un code</b>, et c'est
 * délibéré. La tentation évidente serait un endpoint « de développement » rendant l'OTP
 * d'une adresse — ce serait une porte dérobée d'authentification, protégée par un profil
 * qu'une erreur de configuration suffit à activer en production.
 * {@code ProductionApiSurfaceIntegrationTest} existe précisément pour interdire ce genre
 * de surface. Passer par le port ne coûte rien de plus et ne peut pas être déployé.
 *
 * <p>Le code ne transite ni par la base ni par l'outbox : il n'existe qu'ici, en mémoire du
 * processus de test, et meurt avec lui.
 */
public class CapturingEmailVerificationSender implements EmailVerificationSender {

    private final Map<String, String> lastCodes = new ConcurrentHashMap<>();

    @Override
    public void sendVerificationCode(String email, String code) {
        lastCodes.put(email.toLowerCase(), code);
    }

    /** Dernier code émis pour cette adresse, ou {@code null} si aucun envoi n'a eu lieu. */
    public String lastCodeFor(String email) {
        return lastCodes.get(email.toLowerCase());
    }

    /** Vrai si un envoi a eu lieu — sert à prouver l'ABSENCE d'envoi autant que sa présence. */
    public boolean hasSentTo(String email) {
        return lastCodes.containsKey(email.toLowerCase());
    }

    public void clear() {
        lastCodes.clear();
    }

    /**
     * À importer par toute classe de test qui a besoin des codes.
     *
     * <p>{@code @Primary} remplace l'adaptateur outbox pour la durée du test sans toucher au
     * câblage de production — le port est justement là pour rendre cette substitution
     * possible sans condition dans le code métier.
     */
    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public CapturingEmailVerificationSender capturingEmailVerificationSender() {
            return new CapturingEmailVerificationSender();
        }
    }
}
