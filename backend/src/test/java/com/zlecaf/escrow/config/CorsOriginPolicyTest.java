package com.zlecaf.escrow.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Story 1.5 (NFR-P4) — parsing et validation de l'allowlist CORS.
 *
 * <p>Test unitaire pur (aucun contexte Spring) : la politique est le point où
 * une valeur d'opérateur douteuse doit devenir un échec de démarrage plutôt
 * qu'un 403 inexplicable en production.
 */
class CorsOriginPolicyTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("null ou blanc -> liste vide (= allowlist non fournie, mode dev permissif)")
        void blankYieldsEmptyList() {
            assertThat(CorsOriginPolicy.parse(null)).isEmpty();
            assertThat(CorsOriginPolicy.parse("")).isEmpty();
            assertThat(CorsOriginPolicy.parse("   ")).isEmpty();
        }

        @Test
        @DisplayName("découpe sur la virgule et retire les espaces autour de chaque entrée")
        void trimsAroundCommas() {
            assertThat(CorsOriginPolicy.parse("  https://a.test ,\thttps://b.test:8443  "))
                    .containsExactly("https://a.test", "https://b.test:8443");
        }

        @Test
        @DisplayName("ignore les entrées vides (virgule finale, double virgule) — pas d'origine fantôme")
        void ignoresEmptyEntries() {
            assertThat(CorsOriginPolicy.parse("https://a.test,,  ,https://b.test,"))
                    .containsExactly("https://a.test", "https://b.test");
        }

        @Test
        @DisplayName("une seule origine reste une liste d'un élément")
        void singleOrigin() {
            assertThat(CorsOriginPolicy.parse("https://app.escrow.test"))
                    .containsExactly("https://app.escrow.test");
        }
    }

    @Nested
    @DisplayName("requireValidProductionOrigins")
    class RequireValidProductionOrigins {

        @Test
        @DisplayName("liste valide (http, https, port explicite) -> acceptée telle quelle")
        void validListAccepted() {
            List<String> origins = CorsOriginPolicy.parse(
                    "https://app.escrow.test, https://admin.escrow.test:8443, http://interne.escrow.test");
            assertThatCode(() -> CorsOriginPolicy.requireValidProductionOrigins(origins))
                    .doesNotThrowAnyException();
            assertThat(CorsOriginPolicy.requireValidProductionOrigins(origins)).isEqualTo(origins);
        }

        @Test
        @DisplayName("liste vide ou null -> échec nommant ESCROW_CORS_ALLOWED_ORIGINS")
        void emptyListRejected() {
            assertThatThrownBy(() -> CorsOriginPolicy.requireValidProductionOrigins(List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CORS_ALLOWED_ORIGINS");
            assertThatThrownBy(() -> CorsOriginPolicy.requireValidProductionOrigins(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CORS_ALLOWED_ORIGINS");
        }

        @ParameterizedTest(name = "entrée refusée : {0}")
        @ValueSource(strings = {
                "*",                          // joker nu : inopérant en comparaison exacte
                "https://*.escrow.test",      // joker de sous-domaine : ne matcherait jamais
                "app.escrow.test",            // scheme absent
                "ftp://app.escrow.test",      // scheme non http(s)
                "https://",                   // hôte absent
                "https://a.test/",            // '/' final : l'en-tête Origin n'en porte pas
                "https://a.test/api",         // chemin : une origine n'en a pas
        })
        @DisplayName("entrée permissive ou malformée -> échec nommant la variable ET l'entrée fautive")
        void malformedEntryRejected(String rejected) {
            List<String> origins = CorsOriginPolicy.parse("https://ok.escrow.test," + rejected);
            assertThatThrownBy(() -> CorsOriginPolicy.requireValidProductionOrigins(origins))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CORS_ALLOWED_ORIGINS")
                    .hasMessageContaining(rejected);
        }

        @Test
        @DisplayName("le joker est refusé même seul et bien formé — c'est le piège silencieux visé")
        void wildcardAloneRejected() {
            assertThatThrownBy(() -> CorsOriginPolicy.requireValidProductionOrigins(
                    CorsOriginPolicy.parse("*")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CORS_ALLOWED_ORIGINS");
        }
    }
}
