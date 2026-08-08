package com.zlecaf.escrow.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.support.PostgresTestSupport;

import org.junit.jupiter.api.DisplayName;
import com.zlecaf.escrow.support.CapturingEmailVerificationSender;
import com.zlecaf.escrow.support.VerifiedAccounts;
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
 * Revue 1.6 (décision D1) — la vérification de l'ancien mot de passe sur
 * {@code /auth/change-password} est soumise à l'anti-bruteforce de la Story 1.3.
 *
 * <p>Avant : 30 mauvais {@code oldPassword} d'affilée renvoyaient 30 × 400 et
 * jamais de 429 (reproduit pendant la revue). Le limiteur ne filtrait que
 * {@code /login} et {@code /register}, et ne comptait que les 401/409 — or
 * change-password rejette en 400, <b>délibérément</b> : un 401 ferait purger la
 * session par l'intercepteur du frontend sur une simple faute de frappe. Le 400
 * compte donc comme échec d'authentification sur <b>ce seul</b> chemin. Un jeton
 * volé ne se convertit plus en prise de contrôle par devinette illimitée.
 *
 * <p><b>Classe séparée à dessein</b> : le limiteur compte aussi par origine et
 * son état est porté par un bean singleton. Abaisser le seuil dans la suite
 * voisine faisait fuir le compteur d'un test à l'autre et verrouillait des cas
 * sans rapport. Une propriété distincte donne ici un contexte Spring — donc un
 * limiteur — à part.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(CapturingEmailVerificationSender.Config.class)
class ChangePasswordRateLimitIntegrationTest {

    /** Seul chemin par lequel un test connaît un code : le port, jamais un endpoint. */
    @org.springframework.beans.factory.annotation.Autowired
    private CapturingEmailVerificationSender verificationCodes;

    private static final String STRONG = "Str0ng!Passw0rd";
    private static final String STRONG2 = "An0ther!Passw0rd";
    private static final int MAX_ATTEMPTS = 3;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, ChangePasswordRateLimitIntegrationTest.class);
        registry.add("escrow.auth.ratelimit.max-attempts", () -> String.valueOf(MAX_ATTEMPTS));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("D1 : deviner l'ancien mot de passe finit en 429, pas en oracle illimité")
    void changePasswordIsRateLimited() throws Exception {
        String token = registerAndGetToken("bruteforce@escrow.test");
        String body = "{\"oldPassword\":\"wrong-guess\",\"newPassword\":\"" + STRONG2 + "\"}";

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            mvc.perform(post("/api/v1/auth/change-password")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(post("/api/v1/auth/change-password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    /**
     * Compte AUTHENTIFIÉ. Depuis la Story 2.4 l'inscription ne rend plus de session : elle
     * crée un compte non vérifié et envoie un code. Ce test veut un utilisateur qui peut
     * appeler l'API, pas éprouver le parcours d'inscription — la fabrique partagée traverse
     * donc les deux vrais endpoints et rend le jeton.
     */
    private String registerAndGetToken(String email) throws Exception {
        return VerifiedAccounts.createVerified(mvc, objectMapper, verificationCodes, email);
    }
}
