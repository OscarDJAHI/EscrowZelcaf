package com.zlecaf.escrow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.support.PostgresTestSupport;

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
class PasswordAndRevocationIntegrationTest {

    private static final String STRONG = "Str0ng!Passw0rd";
    private static final String STRONG2 = "An0ther!Passw0rd";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, PasswordAndRevocationIntegrationTest.class);
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
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("bytes")))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("categories")));
    }

    @Test
    @DisplayName("revue 1.6 : un mot de passe > 72 OCTETS (mais < 72 caractères) est rejeté en WEAK_PASSWORD")
    void multiByteOverlongPasswordRejectedWithContractualCode() throws Exception {
        // Double garde. (1) La borne était comptée en caractères : ce mot de passe
        // passait, bcrypt n'en hachait que les 72 premiers octets et tout suffixe
        // authentifiait ensuite. (2) Le @Size(max=72) du DTO rejetait au-delà AVANT
        // la politique, en VALIDATION_ERROR au lieu du WEAK_PASSWORD contractuel de
        // l'AC #1 ; le garde-fou DoS a été desserré pour que la politique tranche.
        String accented = "Ééàèù1!".repeat(8); // 56 caractères, 96 octets
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Map.of("email", "longbytes@escrow.test", "password", accented))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WEAK_PASSWORD"));
    }

    // --- Revue 1.6 : les deux nouvelles routes exigent bien un JWT ----------------

    @Test
    @DisplayName("revue 1.6 : /auth/logout sans jeton -> 403, jamais un 500 hors enveloppe")
    void logoutRequiresAuthentication() throws Exception {
        // Le joker `/api/v1/auth/**` en permitAll laissait ces deux routes ouvertes :
        // le principal arrivait null au contrôleur -> NPE -> 500 nu, sans le champ
        // `code` obligatoire, atteignable sans authentification. Aucun test ne les
        // appelait sans jeton, d'où 343 tests verts au-dessus du trou.
        mvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("revue 1.6 : /auth/change-password sans jeton -> 403")
    void changePasswordRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + STRONG + "\",\"newPassword\":\"" + STRONG2 + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("revue 1.6 : /auth/logout avec un jeton déjà révoqué -> 403 (double logout, second onglet)")
    void logoutWithAlreadyRevokedTokenIsRefused() throws Exception {
        String token = registerAndGetToken("doublelogout@escrow.test");
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
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
                .andExpect(status().isBadRequest())
                // Assertion sur le CODE et pas seulement le statut (revue 1.6) : sans
                // elle, le test passait à l'identique si le rejet venait de la
                // validation de bean, et ne prouvait donc pas que la vérification de
                // l'ancien mot de passe s'était exécutée.
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Rejet -> aucune révocation : le jeton fonctionne toujours.
        mvc.perform(get("/api/v1/escrow").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("revue 1.6 : réutiliser le mot de passe courant est refusé (rotation illusoire)")
    void changePasswordRejectsSamePassword() throws Exception {
        String token = registerAndGetToken("samepwd@escrow.test");

        // Avant : 204 + révocation de toutes les sessions sans rien changer.
        // L'utilisateur se croyait protégé alors que le secret compromis vivait encore.
        mvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + STRONG + "\",\"newPassword\":\"" + STRONG + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        // Aucune révocation collatérale : la session reste utilisable.
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
