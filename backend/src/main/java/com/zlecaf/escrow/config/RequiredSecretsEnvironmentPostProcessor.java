package com.zlecaf.escrow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.zlecaf.escrow.security.crypto.SecretCipher;
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
 * d'initialisation des beans. Ici on les vérifie tous d'un coup, après le
 * chargement des fichiers de configuration, et on nomme TOUTES les variables
 * manquantes dans une seule erreur actionnable.
 */
public class RequiredSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** property Spring -> variable d'environnement attendue par l'opérateur. */
    static final Map<String, String> REQUIRED_SECRETS = Map.of(
            "spring.datasource.password", "SPRING_DATASOURCE_PASSWORD",
            "spring.rabbitmq.password", "SPRING_RABBITMQ_PASSWORD",
            "escrow.jwt.secret", "ESCROW_JWT_SECRET",
            "escrow.storage.secret-key", "ESCROW_STORAGE_SECRET_KEY",
            // Trousseau de chiffrement au repos (Story 1.7, AD-29). Sa validation
            // FINE reste dans SecretCipher — qui seul sait ce qu'est une clé
            // valable — mais elle est INVOQUÉE ici (cf. cryptoKeyringProblems)
            // pour que ses causes rejoignent le message unique plutôt que de
            // surgir un redémarrage plus tard, à l'initialisation du bean.
            "escrow.crypto.keys", "ESCROW_CRYPTO_KEYS");

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
        problems.addAll(cryptoKeyringProblems(environment));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Démarrage refusé : secret(s) obligatoire(s) invalide(s) — "
                            + String.join(" ; ", problems)
                            + ". Fournir ces variables d'environnement (NFR-P1) ; aucune valeur par défaut n'existe.");
        }
    }

    /**
     * Validité SYNTAXIQUE du trousseau de chiffrement (Story 1.7) : format
     * {@code id:base64}, 32 octets décodés, identifiants uniques, et identifiant
     * actif présent au trousseau.
     *
     * <p>Silencieux quand le trousseau est absent ou resté à la sentinelle : la
     * boucle principale l'a déjà signalé, un second message sur la même variable
     * n'ajouterait que du bruit. C'est {@code SecretCipher} qui décide, ici on ne
     * fait que lui poser la question au bon moment.
     */
    private List<String> cryptoKeyringProblems(ConfigurableEnvironment environment) {
        String rawKeyring = resolveOrNull(environment, "escrow.crypto.keys");
        if (rawKeyring == null || rawKeyring.isBlank() || rawKeyring.startsWith(SENTINEL_PREFIX)) {
            return List.of();
        }
        // Les messages nomment déjà leur variable (ESCROW_CRYPTO_KEYS ou
        // ESCROW_CRYPTO_ACTIVE_KEY_ID) : les préfixer une seconde fois les rendrait
        // illisibles dans l'énumération.
        return SecretCipher.keyringProblems(
                rawKeyring, resolveOrNull(environment, "escrow.crypto.active-key-id"));
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
