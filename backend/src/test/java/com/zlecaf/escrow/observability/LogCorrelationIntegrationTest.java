package com.zlecaf.escrow.observability;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.service.AuditService;
import com.zlecaf.escrow.support.CapturingEmailVerificationSender;
import com.zlecaf.escrow.support.PostgresTestSupport;
import com.zlecaf.escrow.support.VerifiedAccounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LOGS CORRÉLÉS, ÉCRITURE D'AUDIT COMPRISE (Story 11.4, AC1 — second volet).
 *
 * <p><b>Ce que ce fichier garde.</b> L'AC1 exige que « la recherche par cet identifiant
 * restitue toutes les lignes d'une même requête, écriture d'audit comprise ». Deux
 * propriétés distinctes s'y cachent, et elles se prouvent séparément : les lignes portent
 * un identifiant DE REQUÊTE (donc identique au sein d'une requête, différent d'une requête
 * à l'autre), et cet identifiant survit jusqu'à l'écriture d'audit, qui vit plusieurs
 * couches sous le contrôleur.
 *
 * <p><b>Pourquoi l'audit est le témoin choisi.</b> Il est le point le plus PROFOND du
 * parcours — service transactionnel appelé depuis un contrôleur, sur le fil de la requête.
 * Si l'identifiant l'atteint, il atteint tout ce qui est moins profond. Et c'est la seule
 * ligne que l'AC nomme explicitement.
 *
 * <p><b>L'identifiant est le `traceId` de Micrometer, et il n'y en a pas d'autre</b>
 * (décision T0). Ces tests tournent à 0 % d'échantillonnage (`application.properties` de
 * test) : s'ils passent, ils démontrent au passage que l'échantillonnage ne gouverne que
 * l'EXPORT de la trace, jamais l'alimentation du MDC — l'affirmation écrite dans
 * `logback-spring.xml`, ici mise à l'épreuve plutôt que répétée.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Le collecteur de codes de vérification n'est pas un bean par défaut : il est fourni par
// sa propre configuration de test, comme le fait déjà `AntiEnumerationIntegrationTest`.
// Sans lui, `VerifiedAccounts` ne peut pas mener l'inscription à son terme.
@Import(CapturingEmailVerificationSender.Config.class)
class LogCorrelationIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, LogCorrelationIntegrationTest.class);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingEmailVerificationSender verificationCodes;

    private ListAppender<ILoggingEvent> capture;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void attacherLeCollecteur() {
        // Branché sur le logger d'`AuditService` et sur lui seul : c'est la ligne que l'AC
        // nomme. Collecter la racine ferait remonter des centaines d'événements de
        // démarrage Spring, et l'assertion porterait alors sur ce qui traîne dans le lot.
        auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuditService.class);
        capture = new ListAppender<>();
        capture.setContext(auditLogger.getLoggerContext());
        capture.start();
        auditLogger.addAppender(capture);
        auditLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void detacherLeCollecteur() {
        auditLogger.detachAppender(capture);
        capture.stop();
    }

    /** Le `traceId` porté par la PREMIÈRE ligne d'audit collectée. */
    private String traceIdDeLEcritureDAudit() {
        List<ILoggingEvent> evenements = capture.list;
        assertThat(evenements)
                .as("aucune ligne de log n'a été émise par l'écriture d'audit — "
                        + "sans elle, l'AC1 est intenable quel que soit le format des logs")
                .isNotEmpty();
        return evenements.get(0).getMDCPropertyMap().get("traceId");
    }

    /** Crée une transaction escrow réelle : c'est elle qui provoque l'écriture d'audit. */
    private void creerUneTransaction() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String acheteur = VerifiedAccounts.createVerified(
                mvc, objectMapper, verificationCodes, "acheteur-" + run + "@corp.example");
        String vendeur = "vendeur-" + run + "@corp.example";
        VerifiedAccounts.createVerified(mvc, objectMapper, verificationCodes, vendeur);

        mvc.perform(post("/api/v1/escrow")
                        .header("Authorization", "Bearer " + acheteur)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerEmail\":\"" + vendeur + "\",\"amount\":1000.00,"
                                + "\"currency\":\"USD\",\"description\":\"fixture correlation\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void lEcritureDAuditPorteLIdentifiantDeCorrelationDeSaRequete() throws Exception {
        creerUneTransaction();

        // POSITIVE. `AuditService` ne journalisait RIEN avant cette story : il persistait
        // des lignes en silence. L'identifiant doit maintenant l'atteindre à travers le
        // contrôleur, le service escrow et la transaction.
        assertThat(traceIdDeLEcritureDAudit())
                .as("l'écriture d'audit doit porter le traceId de la requête qui l'a provoquée")
                .isNotNull()
                .isNotBlank();
    }

    @Test
    void deuxRequetesDISTINCTESNePartagentPasLeurIdentifiant() throws Exception {
        // NÉGATIVE APPARIÉE à la positive ci-dessus, et elle porte sa propre exigence.
        // Un identifiant constant — une chaîne figée, un champ mal câblé — satisferait le
        // test précédent en beauté et rendrait la corrélation INUTILE : chercher par cet
        // identifiant restituerait les lignes de TOUTES les requêtes. C'est le couple des
        // deux tests qui décrit ce que l'AC veut, jamais l'un des deux seul.
        creerUneTransaction();
        String premier = traceIdDeLEcritureDAudit();

        capture.list.clear();
        creerUneTransaction();
        String second = traceIdDeLEcritureDAudit();

        assertThat(premier).isNotNull();
        assertThat(second).isNotNull().isNotEqualTo(premier);
    }

    @Test
    void leMdcNeFUITPasSurLeFilUneFoisLaRequeteTerminee() throws Exception {
        // LE TEST QU'ON OUBLIE, et le défaut qu'il attrape est invisible à l'œil : un
        // serveur réutilise ses fils, et un MDC non vidé attribue les lignes de la requête
        // SUIVANTE à la trace précédente. Les logs restent parfaitement lisibles — ils sont
        // simplement faux, et ils le sont pour la requête d'un autre utilisateur.
        //
        // MockMvc exécute la requête sur le fil du test : ce fil est donc exactement celui
        // qu'un serveur recyclerait, et l'assertion porte sur l'état qu'il laisse derrière.
        assertThat(MDC.get("traceId")).as("le fil doit être propre AVANT la requête").isNull();

        creerUneTransaction();

        assertThat(MDC.get("traceId"))
                .as("le traceId doit avoir quitté le MDC quand la requête se termine")
                .isNull();
    }

    @Test
    void lEncodeurJSONEmbarqueLeMDCEtDoncLIdentifiant() {
        // Les trois tests ci-dessus prouvent que l'identifiant est DANS le MDC. Celui-ci
        // prouve qu'il en SORT : un encodeur configuré sans `includeMdc` produit un JSON
        // parfaitement valide et TOTALEMENT décorrélé — l'AC1 tomberait sans qu'aucune
        // ligne ne paraisse anormale.
        //
        // L'encodeur interrogé est CELUI QUI TOURNE, récupéré depuis le contexte Logback
        // réel. Le reconstruire à la main n'aurait prouvé que la configuration du test.
        LoggerContext contexte = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> appender =
                contexte.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME).getAppender("JSON");

        assertThat(appender)
                .as("l'appender JSON de logback-spring.xml doit être attaché à la racine")
                .isInstanceOf(OutputStreamAppender.class);

        MDC.put("traceId", "trace-temoin-11-4");
        try {
            ch.qos.logback.classic.spi.LoggingEvent evenement = new ch.qos.logback.classic.spi.LoggingEvent(
                    getClass().getName(), contexte.getLogger(getClass()), Level.INFO, "témoin", null, null);
            evenement.setMDCPropertyMap(MDC.getCopyOfContextMap());

            byte[] encode = ((OutputStreamAppender<ILoggingEvent>) appender).getEncoder().encode(evenement);
            String json = new String(encode, StandardCharsets.UTF_8);

            // Appariée : le JSON contient le message ET l'identifiant. Sans la première
            // moitié, un encodeur rendant une chaîne vide satisferait « ne contient pas
            // n'importe quoi » sans rien encoder du tout.
            assertThat(json).contains("\"message\":\"témoin\"");
            assertThat(json).contains("trace-temoin-11-4");
        } finally {
            MDC.remove("traceId");
        }
    }
}
