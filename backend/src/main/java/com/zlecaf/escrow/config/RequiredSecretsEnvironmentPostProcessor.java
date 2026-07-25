package com.zlecaf.escrow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Fail-fast des secrets obligatoires (Story 1.2, NFR-P1).
 *
 * application.yml ne fournit plus de valeur par défaut pour les secrets
 * critiques : sans cette classe, le démarrage échouerait quand même, mais sur
 * le PREMIER placeholder non résoluble rencontré, au hasard de l'ordre
 * d'initialisation des beans. Ici on vérifie les quatre d'un coup, après le
 * chargement des fichiers de configuration, et on nomme TOUTES les variables
 * manquantes dans une seule erreur actionnable.
 */
public class RequiredSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** property Spring -> variable d'environnement attendue par l'opérateur. */
    static final Map<String, String> REQUIRED_SECRETS = Map.of(
            "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD",
            "spring.rabbitmq.password", "SPRING_RABBITMQ_PASSWORD",
            "escrow.jwt.secret", "ESCROW_JWT_SECRET",
            "escrow.storage.secret-key", "ESCROW_STORAGE_SECRET_KEY");

    /**
     * Valeurs sentinelles de infra/.env.example : leur présence signifie que
     * l'opérateur a copié le fichier d'exemple sans le remplir. La syntaxe
     * compose {@code :?} ne teste que la présence — c'est ici qu'on attrape
     * le « cp sans édition ».
     */
    static final String SENTINEL_PREFIX = "remplacez-moi";

    /** HS256 impose >= 32 octets ; échouer ici plutôt qu'en WeakKeyException dans JwtService. */
    private static final String JWT_SECRET_PROPERTY = "escrow.jwt.secret";
    private static final int JWT_SECRET_MIN_BYTES = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> entry : sortedRequiredSecrets().entrySet()) {
            String property = entry.getKey();
            String envVar = entry.getValue();
            String value = resolveOrNull(environment, property);
            if (value == null || value.isBlank()) {
                problems.add(envVar + " (" + property + ") : manquant");
            } else if (value.startsWith(SENTINEL_PREFIX)) {
                problems.add(envVar + " (" + property + ") : valeur d'exemple de infra/.env.example non remplacée");
            } else if (JWT_SECRET_PROPERTY.equals(property)
                    && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < JWT_SECRET_MIN_BYTES) {
                problems.add(envVar + " (" + property + ") : trop court — >= " + JWT_SECRET_MIN_BYTES
                        + " octets requis pour HS256");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Démarrage refusé : secret(s) obligatoire(s) invalide(s) — "
                            + String.join(" ; ", problems)
                            + ". Fournir ces variables d'environnement (NFR-P1) ; aucune valeur par défaut n'existe.");
        }
    }

    /** Ordre stable pour un message d'erreur déterministe (Map.of ne garantit rien). */
    private Map<String, String> sortedRequiredSecrets() {
        return new java.util.TreeMap<>(REQUIRED_SECRETS);
    }

    private String resolveOrNull(ConfigurableEnvironment environment, String property) {
        try {
            return environment.getProperty(property);
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            // Placeholder ${...} non résoluble = variable absente. Une autre
            // IllegalArgumentException serait re-signalée comme « manquant »,
            // ce qui reste actionnable : la variable fautive est nommée.
            return null;
        }
    }

    @Override
    public int getOrder() {
        // Après le chargement d'application.yml/properties et des profils,
        // pour voir la configuration telle que l'application la verra.
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }
}
