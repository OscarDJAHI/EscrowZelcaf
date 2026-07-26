package com.zlecaf.escrow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 1.6 (NFR-P5) — politique de mot de passe + révocation JWT effective côté
 * serveur, sur la vraie chaîne (JwtAuthFilter compris) avec Postgres réel.
 *
 * <p>Première suite à exercer un JWT réel de bout en bout : login → accès à un
 * endpoint protégé → révocation (logout / changement de mot de passe) → le MÊME
 * jeton, non expiré, est désormais refusé (403). Prouve que la révocation vit
 * côté serveur (version de jeton en base), pas seulement dans le client.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PasswordAndRevocationIntegrationTest {

    private static final String STRONG = "Str0ng!Passw0rd";
    private static final String STRONG2 = "An0ther!Passw0rd";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    // --- AC #1 : politique de mot de passe ---------------------------------------

    @Test
    @DisplayName("AC#1 : inscription avec un mot de passe faible -> 400 WEAK_PASSWORD + règles explicitées")
    void weakPasswordRejectedAtRegister() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"weak@escrow.test\",\"password\":\"password\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("caractères")));
    }

    // --- AC #2 : logout serveur révoque le jeton ---------------------------------

    @Test
    @DisplayName("AC#2 : après logout, le MÊME jeton (non expiré) est refusé côté serveur (403)")
    void logoutRevokesTokenServerSide() throws Exception {
        String token = registerAndGetToken("logout@escrow.test");

        // Le jeton accède à un endpoint protégé.
        mvc.perform(get("/api/v1/escrow").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Déconnexion serveur.
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // Le MÊME jeton ne passe plus (révocation côté serveur, pas seulement client).
        mvc.perform(get("/api/v1/escrow").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // --- AC #3 : changement de mot de passe révoque + politique ------------------

    @Test
    @DisplayName("AC#3 : changement de mot de passe -> ancien jeton refusé, nouveau mot de passe accepté au login")
    void changePasswordRevokesOldSessionsAndSetsNewPassword() throws Exception {
        String token = registerAndGetToken("change@escrow.test");

        mvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + STRONG + "\",\"newPassword\":\"" + STRONG2 + "\"}"))
                .andExpect(status().isNoContent());

        // L'ancien jeton est révoqué.
        mvc.perform(get("/api/v1/escrow").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        // L'ancien mot de passe ne fonctionne plus ; le nouveau, si.
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"change@escrow.test\",\"password\":\"" + STRONG + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"change@escrow.test\",\"password\":\"" + STRONG2 + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC#3 : changement avec un mauvais ancien mot de passe -> rejeté, session intacte")
    void changePasswordWithWrongOldRejected() throws Exception {
        String token = registerAndGetToken("wrongold@escrow.test");

        mvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"not-the-old-one\",\"newPassword\":\"" + STRONG2 + "\"}"))
                .andExpect(status().isBadRequest());

        // Rejet -> aucune révocation : le jeton fonctionne toujours.
        mvc.perform(get("/api/v1/escrow").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC#3 : changement vers un nouveau mot de passe faible -> 400 WEAK_PASSWORD, session intacte")
    void changePasswordToWeakRejected() throws Exception {
        String token = registerAndGetToken("weaknew@escrow.test");

        mvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + STRONG + "\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));

        mvc.perform(get("/api/v1/escrow").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /** Inscrit un utilisateur avec un mot de passe conforme et renvoie son JWT. */
    private String registerAndGetToken(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + STRONG + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }
}
