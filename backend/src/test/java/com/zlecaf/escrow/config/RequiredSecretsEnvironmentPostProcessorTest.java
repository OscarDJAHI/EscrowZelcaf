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
                // Story 1.7 : ici on ne teste que présence/sentinelle ; la validité du
                // trousseau (format, 32 octets, id actif) est prouvée par SecretCipherTest.
                .withProperty("escrow.crypto.keys", "v1:x");
    }

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
