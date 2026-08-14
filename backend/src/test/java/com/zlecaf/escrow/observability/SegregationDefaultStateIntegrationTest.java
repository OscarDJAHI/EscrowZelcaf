package com.zlecaf.escrow.observability;

import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ÉTAT DE REPOS DE LA JAUGE DE SÉGRÉGATION (Story 11.4, T6 — AC4).
 *
 * <p><b>Pourquoi une SECONDE classe et pas un test de plus à côté des autres.</b> Le
 * cas éprouvé ici est l'ABSENCE de fournisseur injecté. Le mettre dans la classe qui
 * en déclare un ({@code SegregationMetricsIntegrationTest}) aurait été impossible :
 * la {@code @TestConfiguration} vaut pour toute la classe. Ce sont deux contextes
 * Spring distincts parce que ce sont deux mondes distincts — avec et sans circuit
 * financier — et c'est précisément la bascule que l'Epic 4 opérera.
 *
 * <p><b>Ce que le repos doit valoir.</b> {@code NaN}, jamais {@code 0}. C'est la
 * différence entre « je ne sais pas » et « j'ai vérifié, tout va bien ».
 *
 * <p><b>⚠️ Et le {@code NaN} ne suffit PAS à rendre la règle d'alerte muette.</b> Un
 * {@code escrow_segregation_deviation_usd != 0} tout simple DÉCLENCHE sur {@code NaN},
 * parce que {@code NaN != 0} est vrai en PromQL — c'est vrai pour {@code >}, {@code <}
 * et {@code ==} que la comparaison soit fausse, pas pour {@code !=}. La règle porte
 * donc une garde explicite ({@code and x == x}, seule expression que {@code NaN} ne
 * satisfait pas). Sans elle, l'alerte la plus critique du système partirait dès
 * aujourd'hui, sur un dépôt sans le moindre circuit financier — et serait coupée bien
 * avant le jour où elle sert. Trouvé par le harnais {@code promtool}, pas par la
 * relecture : c'est le genre d'erreur qu'un fichier YAML plausible ne montre jamais.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SegregationDefaultStateIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, SegregationDefaultStateIntegrationTest.class);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void auReposLaJaugeExisteEtVautNaN() throws Exception {
        String corps = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // La jauge EXISTE dès aujourd'hui : c'est ce qui prouve que le chemin
        // d'exposition fonctionne avant que l'Epic 4 n'ait la moindre valeur à
        // émettre. Une métrique simplement absente aurait laissé la règle d'alerte
        // invérifiable jusqu'au jour où elle compte.
        assertThat(corps).contains("escrow_segregation_deviation_usd{application=\"escrow-core\"} NaN");

        // ASSERTION NÉGATIVE APPARIÉE, et elle porte tout le sens de cette story :
        // une jauge à 0 affirmerait que l'invariant de ségrégation est TENU, alors
        // qu'il n'existe ni grand livre ni compte cantonné pour le tenir. C'est le
        // mensonge tranquille que le NaN évite.
        assertThat(corps).doesNotContain("escrow_segregation_deviation_usd{application=\"escrow-core\"} 0.0");
    }
}
