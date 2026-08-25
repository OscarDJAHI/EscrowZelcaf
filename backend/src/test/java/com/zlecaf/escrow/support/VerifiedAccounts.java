package com.zlecaf.escrow.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fabrique de comptes AUTHENTIFIÉS pour les tests qui ont besoin d'un JWT, pas du parcours
 * d'inscription.
 *
 * <p><b>Pourquoi elle existe.</b> Jusqu'à la Story 2.4, {@code POST /auth/register} rendait
 * une session immédiate, et huit classes de test s'en servaient comme raccourci pour
 * obtenir un jeton. L'inscription crée désormais un compte NON VÉRIFIÉ et ne rend aucune
 * session (AC1/AC2) : ce raccourci a disparu. Rustiner chaque classe pour qu'elle repasse
 * au vert aurait dispersé la même correction en huit endroits, et la neuvième — écrite
 * demain — aurait redécouvert le problème seule.
 *
 * <p>Ces tests ne veulent pas éprouver l'inscription ; ils veulent un utilisateur qui
 * existe et qui peut appeler l'API. C'est ce que cette classe leur donne, en passant par
 * les VRAIS endpoints — inscription, puis vérification avec le code capté au port. Aucun
 * accès direct au dépôt : un compte fabriqué en contournant le service divergerait
 * silencieusement du compte que produit l'application.
 *
 * @see CapturingEmailVerificationSender la capture du code, qui remplace la boîte mail
 */
public final class VerifiedAccounts {

    /** Conforme à la politique de la Story 1.6 : ≥12 caractères, 4 catégories. */
    public static final String STRONG_PASSWORD = "Str0ng!Passw0rd";

    private VerifiedAccounts() {}

    /** Adresse unique — les tests partagent une base, deux classes ne doivent pas se marcher dessus. */
    public static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@escrow.test";
    }

    /**
     * Inscrit, vérifie, et rend le JWT.
     *
     * @param codes le port de capture, seul endroit d'où un test peut lire un code —
     *              il n'existe aucun endpoint de lecture d'OTP, et il ne doit pas en exister.
     */
    public static String createVerified(MockMvc mvc, ObjectMapper objectMapper,
                                        CapturingEmailVerificationSender codes,
                                        String email) throws Exception {
        return createVerified(mvc, objectMapper, codes, email, null);
    }

    /** Variante précisant le rôle plateforme ({@code BUYER} par défaut côté serveur). */
    public static String createVerified(MockMvc mvc, ObjectMapper objectMapper,
                                        CapturingEmailVerificationSender codes,
                                        String email, String role) throws Exception {
        String roleField = role == null ? "" : ",\"role\":\"" + role + "\"";
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + STRONG_PASSWORD + "\","
                                + "\"companyName\":\"Acme SARL\",\"consentAccepted\":true" + roleField + "}"))
                // 202 et non 201 : l'inscription ne confirme pas qu'un compte a été créé,
                // sans quoi elle confirmerait que l'adresse était libre (NFR-P9).
                .andExpect(status().isAccepted());

        String code = codes.lastCodeFor(email);
        if (code == null) {
            throw new IllegalStateException(
                    "Aucun code capté pour " + email + " — l'inscription n'a rien envoyé au port.");
        }

        MvcResult verified = mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(verified.getResponse().getContentAsString()).get("token").asText();
    }
}
