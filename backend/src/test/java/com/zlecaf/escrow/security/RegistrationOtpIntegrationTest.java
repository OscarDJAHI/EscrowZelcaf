package com.zlecaf.escrow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.EmailVerificationCodeRepository;
import com.zlecaf.escrow.repository.LegalConsentRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.support.CapturingEmailVerificationSender;
import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Story 2.4 — inscription vérifiée par OTP, sur du VRAI HTTP et une VRAIE base.
 *
 * <p>Le cœur de la classe est l'AC4 : l'inscription ne doit pas révéler qu'une adresse est
 * déjà enregistrée. La propriété se prouve en comparant deux réponses <b>octet pour
 * octet</b>, pas en vérifiant que chacune a « l'air correcte » séparément — c'est le
 * gabarit que la Story 1.10 a posé côté ressources et qu'on applique ici au compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(CapturingEmailVerificationSender.Config.class)
class RegistrationOtpIntegrationTest {

    private static final String STRONG = "Str0ng!Passw0rd";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, RegistrationOtpIntegrationTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CapturingEmailVerificationSender codes;
    @Autowired UserRepository users;
    @Autowired EmailVerificationCodeRepository verificationCodes;
    @Autowired LegalConsentRepository consents;

    private String email(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@escrow.test";
    }

    private MvcResult register(String address) throws Exception {
        return mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + STRONG + "\","
                                + "\"companyName\":\"Acme SARL\",\"consentAccepted\":true}"))
                .andReturn();
    }

    /**
     * Enveloppe d'erreur débarrassée de son horodatage.
     *
     * <p>{@code GlobalExceptionHandler} y met {@code Instant.now()} : deux réponses émises à
     * des instants différents diffèrent TOUJOURS, et une comparaison octet pour octet naïve
     * échouerait même sur deux réponses parfaitement indistinguables. Ce qu'on compare est
     * ce qu'un attaquant peut exploiter — statut, code, message — et sûrement pas l'heure
     * qu'il est.
     */
    private static String withoutTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\",?", "");
    }

    private MvcResult verify(String address, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"code\":\"" + code + "\"}"))
                .andReturn();
    }

    // --- AC1 : ce que l'inscription crée -------------------------------------

    // `@Transactional` UNIQUEMENT pour garder une session ouverte : `User.company` est en
    // LAZY, et y toucher hors session lève. Les autres tests s'en passent.
    @Test
    @org.springframework.transaction.annotation.Transactional
    @DisplayName("AC1 : compte NON vérifié, entreprise rattachée, créateur gestionnaire, consentement horodaté")
    void registrationCreatesUnverifiedAccountWithCompanyAndConsent() throws Exception {
        String address = email("ac1");
        register(address).getResponse();

        User user = users.findByEmail(address).orElseThrow();
        assertThat(user.isEmailVerified()).as("le compte naît NON vérifié").isFalse();
        assertThat(user.getCompany()).isNotNull();
        assertThat(user.getCompany().getName()).isEqualTo("Acme SARL");
        assertThat(user.isCompanyManager()).as("FR-P9 : le créateur est gestionnaire").isTrue();

        assertThat(consents.findByUserIdOrderByConsentedAtDesc(user.getId()))
                .as("consentement persisté avec horodatage serveur (FR-P27)")
                .hasSize(1)
                .allSatisfy(c -> assertThat(c.getConsentedAt()).isNotNull());

        assertThat(codes.hasSentTo(address)).as("un code est parti au port").isTrue();
        assertThat(codes.lastCodeFor(address)).matches("\\d{6}");
    }

    @Test
    @DisplayName("AC1/FR-P16 : ADMIN reste inaccessible depuis ce parcours")
    void adminRoleStillRefusedAtRegistration() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email("admin") + "\",\"password\":\"" + STRONG + "\","
                                + "\"companyName\":\"Acme\",\"consentAccepted\":true,\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("AC1 : sans consentement, aucun compte — un booléen absent est un refus, pas un défaut")
    void missingConsentCreatesNothing() throws Exception {
        String address = email("noconsent");
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + STRONG + "\","
                                + "\"companyName\":\"Acme\"}"))
                .andExpect(status().isBadRequest());
        assertThat(users.findByEmail(address)).isEmpty();
    }

    // --- AC4 : l'inscription ne divulgue rien --------------------------------

    @Test
    @DisplayName("AC4 : adresse connue et adresse inconnue rendent la MÊME réponse, octet pour octet")
    void registrationResponseIsIndistinguishable() throws Exception {
        String known = email("known");
        register(known); // l'adresse existe désormais

        MvcResult second = register(known);
        MvcResult fresh = register(email("fresh"));

        assertThat(second.getResponse().getStatus())
                .isEqualTo(fresh.getResponse().getStatus());
        assertThat(withoutTimestamp(second.getResponse().getContentAsString()))
                .as("corps identiques — c'est l'assertion qui porte NFR-P9")
                .isEqualTo(withoutTimestamp(fresh.getResponse().getContentAsString()));
        assertThat(second.getResponse().getStatus())
                .as("202 et non 201 : un « Created » affirmerait que l'adresse était libre")
                .isEqualTo(202);
    }

    @Test
    @DisplayName("AC4 : une seconde inscription sur une adresse connue ne crée RIEN et n'envoie RIEN")
    void secondRegistrationIsInert() throws Exception {
        String address = email("inert");
        register(address);
        User user = users.findByEmail(address).orElseThrow();
        long companyId = user.getCompany().getId();
        codes.clear();

        register(address);

        assertThat(users.findByEmail(address).orElseThrow().getCompany().getId())
                .as("aucune entreprise supplémentaire rattachée")
                .isEqualTo(companyId);
        assertThat(consents.findByUserIdOrderByConsentedAtDesc(user.getId()))
                .as("aucun consentement fabriqué au nom de quelqu'un d'autre")
                .hasSize(1);
        assertThat(codes.hasSentTo(address))
                .as("aucun code envoyé : sinon n'importe qui inonderait la boîte d'un tiers")
                .isFalse();
    }

    // --- AC2 : vérification --------------------------------------------------

    @Test
    @DisplayName("AC2 : le bon code vérifie le compte et ouvre la session")
    void correctCodeVerifiesAndIssuesSession() throws Exception {
        String address = email("ok");
        register(address);

        MvcResult result = verify(address, codes.lastCodeFor(address));
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText())
                .isNotBlank();
        assertThat(users.findByEmail(address).orElseThrow().isEmailVerified()).isTrue();
    }

    @Test
    @DisplayName("AC2 : un compte non vérifié ne se connecte PAS — jamais de jeton")
    void unverifiedAccountCannotLogIn() throws Exception {
        String address = email("unverified");
        register(address);

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\",\"password\":\"" + STRONG + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("OTP_INVALID"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("AC2 : le code est à USAGE UNIQUE — le rejouer échoue")
    void codeCannotBeReplayed() throws Exception {
        String address = email("replay");
        register(address);
        String code = codes.lastCodeFor(address);
        verify(address, code);

        assertThat(verify(address, code).getResponse().getStatus()).isEqualTo(401);
    }

    // --- AC3 : échecs, tous indistinguables ----------------------------------

    @Test
    @DisplayName("AC3 : mauvais code, adresse inconnue et compte sans code en attente donnent la MÊME réponse")
    void everyVerificationFailureLooksTheSame() throws Exception {
        String address = email("wrong");
        register(address);
        String real = codes.lastCodeFor(address);
        String wrong = real.equals("000000") ? "111111" : "000000";

        MvcResult badCode = verify(address, wrong);
        MvcResult unknownAddress = verify(email("ghost"), "123456");

        assertThat(badCode.getResponse().getStatus()).isEqualTo(unknownAddress.getResponse().getStatus());
        assertThat(withoutTimestamp(badCode.getResponse().getContentAsString()))
                .as("« expiré » ou « inconnu » au lieu de « faux » confirmerait qu'un code a été émis "
                        + "pour cette adresse — donc qu'elle est inscrite")
                .isEqualTo(withoutTimestamp(unknownAddress.getResponse().getContentAsString()));
        assertThat(users.findByEmail(address).orElseThrow().isEmailVerified()).isFalse();
    }

    @Test
    @DisplayName("AC3 : le plafond de tentatives INVALIDE le code, même le vrai ne passe plus ensuite")
    void attemptCapKillsTheCode() throws Exception {
        String address = email("cap");
        register(address);
        String real = codes.lastCodeFor(address);
        String wrong = real.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            verify(address, wrong);
        }

        // Un code que l'on SAIT attaqué ne doit pas survivre jusqu'à son expiration.
        assertThat(verify(address, real).getResponse().getStatus()).isEqualTo(401);
        assertThat(verificationCodes.findByUserId(users.findByEmail(address).orElseThrow().getId()))
                .as("le code est détruit, pas seulement bloqué")
                .isEmpty();
    }

    // --- AC4 : renvoi limité -------------------------------------------------

    @Test
    @DisplayName("AC4 : un renvoi immédiat est refusé en 429 avec son délai")
    void resendIsThrottled() throws Exception {
        String address = email("resend");
        register(address);

        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + address + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                // Le compte à rebours affiché vient de l'horloge SERVEUR (AD-11) ; calculé
                // par le client, il se remettrait à zéro au rechargement de la page.
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("AC4 : un renvoi vers une adresse inconnue répond 202 — aucune divulgation")
    void resendOnUnknownAddressDisclosesNothing() throws Exception {
        mvc.perform(post("/api/v1/auth/resend-verification")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email("nobody") + "\"}"))
                .andExpect(status().isAccepted());
    }
}
