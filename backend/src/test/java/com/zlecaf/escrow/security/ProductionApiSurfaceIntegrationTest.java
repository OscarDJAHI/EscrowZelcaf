package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.zlecaf.escrow.support.PostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Story 1.5 (NFR-P4) — surface d'API bornée sous le profil {@code prod}.
 *
 * <p>Même harnais que {@code SecurityHeadersIntegrationTest} : MockMvc + Postgres
 * réel (Testcontainers). MockMvc applique la vraie chaîne de filtres du contexte,
 * dont le {@code CorsFilter} installé par {@code http.cors(...)} — les rejets
 * CORS sont donc éprouvés là où ils se produisent réellement, avant tout
 * contrôleur (403 « Invalid CORS request », hors enveloppe d'erreur applicative).
 *
 * <p>L'allowlist est injectée par {@code @DynamicPropertySource} : aucune origine
 * de production n'est versionnée dans le dépôt.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionApiSurfaceIntegrationTest {

    private static final String ALLOWED_ORIGIN = "https://app.escrow.test";
    private static final String UNKNOWN_ORIGIN = "https://evil.test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, ProductionApiSurfaceIntegrationTest.class);
        // Allowlist exploitable, sinon ProductionApiSurfaceGuard refuse le démarrage.
        registry.add("escrow.api.cors-allowed-origins", () -> ALLOWED_ORIGIN);
    }

    @Autowired
    MockMvc mvc;

    // --- CORS : allowlist exacte -------------------------------------------------

    @Test
    @DisplayName("Préflight depuis une origine allowlistée : 200 + Access-Control-Allow-Origin")
    void preflightFromAllowedOrigin() throws Exception {
        mvc.perform(options("/api/v1/escrow/1")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
    }

    @Test
    @DisplayName("Préflight depuis une origine inconnue : 403, aucun Access-Control-Allow-Origin")
    void preflightFromUnknownOrigin() throws Exception {
        mvc.perform(options("/api/v1/escrow/1")
                        .header("Origin", UNKNOWN_ORIGIN)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Requête réelle depuis une origine inconnue : 403, aucun Access-Control-Allow-Origin")
    void actualRequestFromUnknownOrigin() throws Exception {
        // /actuator/health est permitAll : un 403 ici ne peut venir QUE du CorsFilter.
        // (Sur une route authentifiée, le 403 de sécurité masquerait le rejet CORS.)
        // Le corps « Invalid CORS request » est écrit par DefaultCorsProcessor AVANT
        // tout contrôleur : ni enveloppe d'erreur applicative, ni GlobalExceptionHandler.
        mvc.perform(get("/actuator/health").header("Origin", UNKNOWN_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid CORS request")));
    }

    @Test
    @DisplayName("Requête réelle depuis une origine allowlistée : traverse le CorsFilter et porte l'en-tête CORS")
    void actualRequestFromAllowedOrigin() throws Exception {
        // Contraste avec le test précédent : même route, même méthode, seule l'origine
        // change — ce qui prouve que le 403 ci-dessus est bien dû à l'allowlist.
        // On n'asservit PAS le statut : dans ce contexte de test, /actuator/health est
        // DOWN (503) car RabbitMQ n'est pas dans les Testcontainers. Le discriminant
        // pertinent est ailleurs : la réponse porte le corps de l'endpoint (donc la
        // requête a bien traversé le CorsFilter) et l'en-tête Access-Control-Allow-Origin.
        mvc.perform(get("/actuator/health").header("Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\"")));
    }

    @Test
    @DisplayName("Retry-After est exposé aux origines allowlistées (backoff du 429 de la Story 1.3 lisible)")
    void retryAfterExposedToAllowedOrigin() throws Exception {
        mvc.perform(get("/actuator/health").header("Origin", ALLOWED_ORIGIN))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsString("Retry-After")));
    }

    @Test
    @DisplayName("Same-origin NON listé : requête traitée normalement (le PWA de prod n'est pas cassé)")
    void sameOriginNotInAllowlistIsNotACorsRequest() throws Exception {
        // MockMvc sert sur http://localhost:80 ; cette origine n'est PAS dans
        // l'allowlist. CorsUtils.isCorsRequest compare l'Origin au scheme/hôte/port
        // DE LA REQUÊTE et renvoie false s'ils coïncident : la requête n'entre jamais
        // dans le traitement CORS. C'est ce qui permet au PWA same-origin de prod
        // (VITE_API_BASE="", /api proxifié) de fonctionner sans figurer dans la liste.
        // Un 403 ici signalerait un X-Forwarded-Proto/Host mal posé au reverse-proxy.
        // Le corps de l'endpoint (et non « Invalid CORS request ») prouve la traversée ;
        // l'absence d'Access-Control-Allow-Origin prouve qu'aucun traitement CORS n'a eu
        // lieu — la requête n'a jamais eu besoin d'être allowlistée.
        mvc.perform(get("/actuator/health").header("Origin", "http://localhost"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\"")));
    }

    // --- Documentation d'API fermée ----------------------------------------------

    @ParameterizedTest(name = "documentation fermée : {0}")
    @ValueSource(strings = {
            "/v3/api-docs",
            "/v3/api-docs.yaml",
            "/swagger-ui/index.html",
            "/swagger-ui.html",
    })
    @DisplayName("Sans JWT, les quatre chemins de documentation ne renvoient ni 2xx ni contenu OpenAPI")
    void apiDocumentationClosed(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();

        // 403 (matcher permitAll retiré -> anyRequest().authenticated()) OU 404
        // (handlers springdoc désactivés) : les deux fermetures sont indépendantes,
        // on asservit donc le refus, pas un code figé qui casserait au moindre
        // changement d'ordre interne de springdoc.
        assertThat(result.getResponse().getStatus())
                .as("statut de %s", path)
                .isIn(403, 404);
        assertThat(result.getResponse().getContentAsString())
                .as("corps de %s — aucune fuite de spec, de version ni de routes", path)
                .doesNotContain("openapi")
                .doesNotContain("ZLECAf B2B Escrow API")
                .doesNotContain("swagger");
    }
}
