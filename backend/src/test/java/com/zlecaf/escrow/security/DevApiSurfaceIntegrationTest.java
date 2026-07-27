package com.zlecaf.escrow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zlecaf.escrow.support.PostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 1.5 (NFR-P4) — garde-fou : AUCUN profil actif, donc dev et CI restent
 * pleinement utilisables.
 *
 * <p>Le durcissement de la surface d'API est conditionné au profil {@code prod}.
 * Ce test échoue dès qu'un changement déborde sur le comportement par défaut :
 * la documentation doit rester servie et le CORS permissif, exactement comme
 * avant la story. C'est le pendant de
 * {@link ProductionApiSurfaceIntegrationTest}, qui prouve l'inverse sous profil.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DevApiSurfaceIntegrationTest {

    private static final String ARBITRARY_ORIGIN = "https://un-hote-de-dev-quelconque.test:5173";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, DevApiSurfaceIntegrationTest.class);
        // Aucune allowlist : c'est le défaut de développement (CORS permissif).
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("Hors profil prod, la spec OpenAPI reste servie sans authentification (200 JSON)")
    void openApiSpecStillServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("openapi")));
    }

    @Test
    @DisplayName("Hors profil prod, le CORS reste permissif : toute origine est acceptée (requête réelle)")
    void corsRemainsPermissiveOnActualRequest() throws Exception {
        // Statut non asservi : /actuator/health est DOWN (503) dans ce contexte de test,
        // RabbitMQ n'étant pas dans les Testcontainers. Ce qui compte ici est que la
        // requête traverse le CorsFilter (corps de l'endpoint, pas « Invalid CORS
        // request ») et reçoive l'en-tête CORS pour une origine arbitraire.
        mvc.perform(get("/actuator/health").header("Origin", ARBITRARY_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Origin", ARBITRARY_ORIGIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\"")));
    }

    @Test
    @DisplayName("Hors profil prod, le préflight d'une origine quelconque est accepté")
    void corsRemainsPermissiveOnPreflight() throws Exception {
        mvc.perform(options("/api/v1/escrow/1")
                        .header("Origin", ARBITRARY_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ARBITRARY_ORIGIN));
    }
}
