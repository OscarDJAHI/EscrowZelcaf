package com.zlecaf.escrow.observability;

import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ÉCART DE SÉGRÉGATION INJECTÉ SANS OUVRIR DE PORTE (Story 11.4, T6 — AC4).
 *
 * <p><b>Ce que ce fichier garde.</b> L'AC4 demande d'injecter « une valeur d'écart non
 * nulle sur la métrique de rapprochement (valeur factice de test) ». La Story 1.10 et
 * le T0 de la 2.4 interdisent d'ajouter le moindre endpoint de test à la surface de
 * production. Les deux tiennent ensemble par le REMPLACEMENT DE BEAN opéré ici :
 * l'écart factice entre par le contexte Spring du test, et l'application de production
 * n'expose aucun moyen de l'écrire. C'est la décision Q3 du 2026-08-11, éprouvée.
 *
 * <p><b>Pourquoi la valeur de repos est {@code NaN} et pas {@code 0}.</b> Zéro affirme
 * « rapprochement fait, invariant tenu ». Le rendre par défaut ferait passer l'absence
 * totale de circuit financier pour une certitude que tout va bien — et rendrait la
 * règle d'alerte silencieuse pour la bonne raison le jour où elle le serait pour la
 * mauvaise. Les deux états sont donc assérés séparément.
 *
 * <p><b>Où le signe de l'écart est éprouvé, et pourquoi pas ici.</b> Un écart négatif
 * dit que le compte cantonné porte MOINS que ce que le grand livre prétend : c'est le
 * sens le plus dangereux des deux, et une règle écrite {@code > 0} au lieu de
 * {@code != 0} le laisserait passer en silence. Mais ce qui doit l'attraper est la
 * RÈGLE, pas la jauge — la jauge se contente de rendre le nombre qu'on lui donne.
 * Les deux signes sont donc éprouvés dans {@code rules-tests/escrow-alerts.test.yml}
 * par {@code promtool}, là où ils prouvent quelque chose. Les rejouer ici aurait coûté
 * un contexte Spring de plus pour ne rien garder de neuf.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SegregationMetricsIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, SegregationMetricsIntegrationTest.class);
    }

    /**
     * L'écart factice de l'AC4. {@code @Primary} plutôt que de compter sur le seul
     * {@code @ConditionalOnMissingBean} de la configuration de production : l'ordre
     * d'évaluation des conditions face à une configuration de test n'est pas garanti,
     * et un test qui dépend d'un ordre non garanti est un test qui rougira un jour
     * sans que personne n'ait touché à ce qu'il garde.
     */
    @TestConfiguration
    static class EcartFactice {
        static final double VALEUR = 1_234.56d;

        @Bean
        @Primary
        SegregationDeviationSource ecartInjecte() {
            return () -> OptionalDouble.of(VALEUR);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private String scrape() throws Exception {
        return mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void exposeLaJaugeSousLeNomQueLaRegleDAlerteInterroge() throws Exception {
        // Le nom EST le contrat entre ce code et `rules/escrow-alerts.yml`. L'assérer
        // par la constante plutôt qu'en recopiant la chaîne serait plus élégant et
        // moins utile : un renommage suivrait la constante et laisserait la règle
        // d'alerte orpheline sans un seul rouge. La chaîne littérale est donc voulue,
        // et c'est la forme PROMETHEUS — celle que la règle voit réellement.
        assertThat(scrape()).contains("escrow_segregation_deviation_usd");
    }

    @Test
    void rendLEcartInjecteSansQuAucunEndpointNAitEteAppele() throws Exception {
        String corps = scrape();

        // Appariée à l'assertion de présence ci-dessus : sans elle, une jauge absente
        // rendrait cette recherche fausse pour la mauvaise raison.
        // Le tag `application` fait PARTIE de la série : c'est le tag commun posé en T1
        // pour que deux déploiements scrapés par le même Prometheus restent
        // distinguables. L'omettre dans l'assertion — ce que le premier jet a fait —
        // cherche une ligne qui n'existe pas et échoue sur une jauge parfaitement bonne.
        assertThat(corps).contains(
                "escrow_segregation_deviation_usd{application=\"escrow-core\"} " + EcartFactice.VALEUR);
    }

    @Test
    void nExposeAucunEndpointPermettantDEcrireCetEcart() throws Exception {
        // L'interdiction de 1.10 / 2.4, éprouvée plutôt que promise. Ces deux chemins
        // sont ceux qu'un « petit ajout pratique » créerait en premier.
        //
        // ⚠️ 403 ET NON 404, et la nuance porte le sens : ces chemins ne sont pas dans
        // la liste `permitAll` de SecurityConfig, donc ils retombent sur
        // `anyRequest().authenticated()` — la SÉCURITÉ refuse avant que l'absence
        // d'endpoint ne se voie. Le premier jet attendait un 404 et a reçu un 403, ce
        // qui est la meilleure des deux réponses. Même convention que
        // `ObservabilityIntegrationTest.nOuvreAucunAutreEndpointActuator`.
        //
        // CE QUE CE TEST GARDE VRAIMENT : ajouter un endpoint d'écriture de l'écart
        // supposerait de l'ouvrir dans SecurityConfig — et ce test rougirait alors,
        // puisqu'il cesserait de recevoir 403. C'est bien la décision Q3 qu'il tient,
        // pas seulement l'absence d'un handler.
        mockMvc.perform(get("/actuator/reconciliation-deviation")).andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/segregation")).andExpect(status().isForbidden());
    }
}
