package com.zlecaf.escrow.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    static final Map<String, String> REQUIRED_SECRETS = new LinkedHashMap<>();
    static {
        REQUIRED_SECRETS.put("spring.datasource.password", "SPRING_DATASOURCE_PASSWORD");
        REQUIRED_SECRETS.put("spring.rabbitmq.password", "SPRING_RABBITMQ_PASSWORD");
        REQUIRED_SECRETS.put("escrow.jwt.secret", "ESCROW_JWT_SECRET");
        REQUIRED_SECRETS.put("escrow.storage.secret-key", "ESCROW_STORAGE_SECRET_KEY");
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : REQUIRED_SECRETS.entrySet()) {
            if (!isResolvable(environment, entry.getKey())) {
                missing.add(entry.getValue() + " (" + entry.getKey() + ")");
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Démarrage refusé : secret(s) obligatoire(s) manquant(s) — "
                            + String.join(", ", missing)
                            + ". Fournir ces variables d'environnement (NFR-P1) ; aucune valeur par défaut n'existe.");
        }
    }

    private boolean isResolvable(ConfigurableEnvironment environment, String property) {
        try {
            String value = environment.getProperty(property);
            return value != null && !value.isBlank();
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            return false;
        }
    }

    @Override
    public int getOrder() {
        // Après le chargement d'application.yml/properties et des profils,
        // pour voir la configuration telle que l'application la verra.
        return ConfigDataEnvironmentPostProcessor.ORDER + 10;
    }
}
