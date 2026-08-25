package com.zlecaf.escrow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Story 1.2 (AC1) : sans secret critique, le démarrage échoue explicitement en
 * nommant la ou les variables manquantes — toutes à la fois, pas une par une.
 */
class RequiredSecretsEnvironmentPostProcessorTest {

    private final RequiredSecretsEnvironmentPostProcessor processor =
            new RequiredSecretsEnvironmentPostProcessor();

    private MockEnvironment envWithAllSecrets() {
        return new MockEnvironment()
                .withProperty("spring.datasource.password", "x")
                .withProperty("spring.rabbitmq.password", "x")
                .withProperty("escrow.jwt.secret", "x".repeat(32))
                .withProperty("escrow.storage.secret-key", "x")
                // Story 1.7 : trousseau RÉELLEMENT valide (32 octets décodés) — depuis la
                // revue, le fail-fast invoque la validation fine de SecretCipher pour
                // agréger ses causes au message unique, une valeur factice échouerait.
                .withProperty("escrow.crypto.keys", VALID_KEYRING);
    }

    /** « escrow-test-only-key-32-bytes!!! » en base64 : exactement 32 octets décodés. */
    private static final String VALID_KEYRING = "v1:ZXNjcm93LXRlc3Qtb25seS1rZXktMzItYnl0ZXMhISE=";

    @Test
    @DisplayName("tous les secrets présents -> démarrage autorisé")
    void allPresent_passes() {
        assertThatCode(() -> processor.postProcessEnvironment(envWithAllSecrets(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("un secret manquant -> erreur nommant sa variable d'environnement")
    void oneMissing_namesTheEnvVar() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "x")
                .withProperty("spring.rabbitmq.password", "x")
                .withProperty("escrow.storage.secret-key", "x");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESCROW_JWT_SECRET")
                .hasMessageContaining("escrow.jwt.secret");
    }

    @Test
    @DisplayName("tous manquants -> les cinq variables sont nommées dans UNE erreur")
    void allMissing_listsThemAll() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD")
                .hasMessageContaining("SPRING_RABBITMQ_PASSWORD")
                .hasMessageContaining("ESCROW_JWT_SECRET")
                .hasMessageContaining("ESCROW_STORAGE_SECRET_KEY")
                .hasMessageContaining("ESCROW_CRYPTO_KEYS");
    }

    @Test
    @DisplayName("trousseau de chiffrement absent -> démarrage refusé en nommant ESCROW_CRYPTO_KEYS (Story 1.7)")
    void missingCryptoKeyring_isRejected() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.crypto.keys", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESCROW_CRYPTO_KEYS")
                .hasMessageContaining("escrow.crypto.keys");
    }

    @Test
    @DisplayName("trousseau laissé à la sentinelle de .env.example -> refusé (le cp sans édition ne passe pas)")
    void sentinelCryptoKeyring_isRejected() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.crypto.keys", "remplacez-moi");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .hasMessageContaining("ESCROW_CRYPTO_KEYS")
                .hasMessageContaining("exemple");
    }

    @Test
    @DisplayName("clé de chiffrement mal dimensionnée -> cause AGRÉGÉE au message unique (Story 1.7)")
    void invalidCryptoKeySize_isAggregatedIntoTheSingleMessage() {
        MockEnvironment env = envWithAllSecrets();
        // 31 octets décodés au lieu de 32 : la faute la plus probable d'un opérateur
        // qui colle une clé tronquée. Sans agrégation, elle ne surgissait qu'au
        // redémarrage SUIVANT, à l'initialisation du bean SecretCipher.
        env.setProperty("escrow.crypto.keys", "v1:" + java.util.Base64.getEncoder()
                .encodeToString(new byte[31]));
        env.setProperty("spring.datasource.password", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                // les DEUX problèmes dans la même erreur : c'est tout l'objet du fail-fast
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD")
                .hasMessageContaining("ESCROW_CRYPTO_KEYS")
                .hasMessageContaining("31 octets");
    }

    @Test
    @DisplayName("identifiant de clé actif hors trousseau -> refusé dès le fail-fast, pas au démarrage du bean")
    void activeKeyIdOutsideKeyring_isRejectedEarly() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.crypto.active-key-id", "v2");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESCROW_CRYPTO_ACTIVE_KEY_ID")
                .hasMessageContaining("v2");
    }

    @Test
    @DisplayName("trousseau absent -> une SEULE plainte, pas un doublon présence + syntaxe")
    void missingKeyring_isReportedOnlyOnce() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.crypto.keys", "");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .satisfies(error -> assertThat(error.getMessage().split("ESCROW_CRYPTO_KEYS", -1))
                        .as("la variable ne doit être nommée qu'une fois")
                        .hasSize(2));
    }

    @Test
    @DisplayName("valeur blanche = manquante (un espace ne fait pas un secret)")
    void blankValue_isMissing() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.jwt.secret", "   ");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .hasMessageContaining("ESCROW_JWT_SECRET");
    }

    @Test
    @DisplayName("placeholder non résoluble (cas réel application.yml sans env var) = manquant")
    void unresolvablePlaceholder_isMissing() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.jwt.secret", "${ESCROW_JWT_SECRET}");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .hasMessageContaining("ESCROW_JWT_SECRET");
    }

    @Test
    @DisplayName("valeur sentinelle de .env.example (remplacez-moi…) -> refusée explicitement")
    void sentinelValue_isRejected() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("spring.datasource.password", "remplacez-moi");

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD")
                .hasMessageContaining("exemple");
    }

    @Test
    @DisplayName("secret JWT < 32 octets -> refusé au fail-fast (pas de WeakKeyException tardive)")
    void shortJwtSecret_isRejected() {
        MockEnvironment env = envWithAllSecrets();
        env.setProperty("escrow.jwt.secret", "x".repeat(31));

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .hasMessageContaining("ESCROW_JWT_SECRET")
                .hasMessageContaining("32");
    }

    @Test
    @DisplayName("garde anti-dérive : chaque secret requis est bien un placeholder SANS défaut dans application.yml")
    void applicationYml_hasNoDefaultForRequiredSecrets() throws Exception {
        String yml = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/application.yml"));
        for (var entry : RequiredSecretsEnvironmentPostProcessor.REQUIRED_SECRETS.entrySet()) {
            String envVar = entry.getValue();
            assertThat(yml)
                    .as("le placeholder ${%s} doit exister dans application.yml", envVar)
                    .contains("${" + envVar + "}");
            assertThat(yml)
                    .as("${%s:...} ne doit avoir AUCUNE valeur par défaut", envVar)
                    .doesNotContain("${" + envVar + ":");
        }
    }

    @Test
    @DisplayName("le post-processor est réellement enregistré dans META-INF/spring.factories")
    void springFactories_registersTheProcessor() throws Exception {
        String factories = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/resources/META-INF/spring.factories"));
        assertThat(factories.replace("\\\n", "").replaceAll("\\s", ""))
                .contains("org.springframework.boot.env.EnvironmentPostProcessor="
                        + RequiredSecretsEnvironmentPostProcessor.class.getName());
        // et l'ordre le place bien APRÈS le chargement des fichiers de config
        assertThat(processor.getOrder())
                .isGreaterThan(org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER);
    }
}
