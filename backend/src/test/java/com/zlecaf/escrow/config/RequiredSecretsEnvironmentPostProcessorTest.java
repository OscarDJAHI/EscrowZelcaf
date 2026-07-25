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
                .withProperty("escrow.storage.secret-key", "x");
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
    @DisplayName("tous manquants -> les quatre variables sont nommées dans UNE erreur")
    void allMissing_listsAllFour() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATASOURCE_PASSWORD")
                .hasMessageContaining("SPRING_RABBITMQ_PASSWORD")
                .hasMessageContaining("ESCROW_JWT_SECRET")
                .hasMessageContaining("ESCROW_STORAGE_SECRET_KEY");
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
    @DisplayName("le registre couvre exactement les 4 secrets critiques de la story")
    void registryCoversTheFourCriticalSecrets() {
        assertThat(RequiredSecretsEnvironmentPostProcessor.REQUIRED_SECRETS).containsOnlyKeys(
                "spring.datasource.password",
                "spring.rabbitmq.password",
                "escrow.jwt.secret",
                "escrow.storage.secret-key");
    }
}
