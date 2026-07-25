package com.zlecaf.escrow.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 1.5 (NFR-P4) — le garde refuse le démarrage sur une surface d'API
 * ouverte en production.
 *
 * <p>Le garde valide dans son constructeur : on l'instancie directement, sans
 * booter de contexte Spring. Le comportement prouvé est identique (un
 * constructeur qui lève = un bean non créable = un contexte qui ne démarre pas),
 * pour un coût sans commune mesure.
 */
class ProductionApiSurfaceGuardTest {

    private static final String VALID_ORIGINS = "https://app.escrow.test,https://admin.escrow.test";

    @Test
    @DisplayName("allowlist valide + docs fermées -> construction silencieuse (l'application peut démarrer)")
    void validConfigurationBootsSilently() {
        assertThatCode(() -> new ProductionApiSurfaceGuard(VALID_ORIGINS, false))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "allowlist « {0} » -> démarrage refusé")
    @ValueSource(strings = {
            "",                        // variable non définie ou vide
            "   ",                     // blanche
            "*",                       // permissive
            "https://*.escrow.test",   // joker de sous-domaine
            "app.escrow.test",         // sans scheme
            "https://a.test/api",      // avec chemin
            "https://a.test/",         // '/' final
    })
    @DisplayName("allowlist absente, permissive ou malformée -> échec nommant ESCROW_CORS_ALLOWED_ORIGINS")
    void unusableAllowlistFailsFast(String allowlist) {
        assertThatThrownBy(() -> new ProductionApiSurfaceGuard(allowlist, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESCROW_CORS_ALLOWED_ORIGINS");
    }

    @Test
    @DisplayName("docs-exposed=true forcé par variable d'environnement -> échec (invariant non contournable)")
    void reopenedDocsFailFast() {
        assertThatThrownBy(() -> new ProductionApiSurfaceGuard(VALID_ORIGINS, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("escrow.api.docs-exposed");
    }

    @Test
    @DisplayName("les deux erreurs à la fois -> l'allowlist est signalée d'abord (rien ne passe)")
    void bothProblemsStillFail() {
        assertThatThrownBy(() -> new ProductionApiSurfaceGuard("", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ESCROW_CORS_ALLOWED_ORIGINS");
    }
}
