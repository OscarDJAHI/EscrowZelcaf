package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.service.HmacSigner;
import com.zlecaf.escrow.service.PartnerSignatureVerifier;
import com.zlecaf.escrow.support.PostgresTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import com.zlecaf.escrow.support.CapturingEmailVerificationSender;
import com.zlecaf.escrow.support.VerifiedAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Story 1.10 (NFR-P9) — l'anti-énumération prouvée <b>sur la vraie chaîne HTTP</b>,
 * avec de vrais JWT et une vraie signature partenaire, contre un Postgres réel.
 *
 * <p><b>Pourquoi une suite d'intégration et pas des tests de service.</b> Tout ce que
 * le dépôt savait prouver jusqu'ici se jouait au niveau service, où le <em>statut
 * n'existe pas</em> : un test qui asservit « lève {@code NotFoundException} » ne dit
 * rien du 404 réellement servi, ni du {@code code} de l'enveloppe, ni des en-têtes.
 * Or l'oracle vit exactement là — dans ce que l'attaquant peut <em>observer</em>.
 * Aucun test anti-IDOR au niveau HTTP n'existait, d'où celui-ci.
 *
 * <p><b>Le critère est l'égalité, pas le statut.</b> Chaque scénario capture DEUX
 * réponses — « ressource d'un tiers » et « identifiant inexistant » — et les compare
 * intégralement : statut, corps entier (horodatage normalisé, puisque c'est le seul
 * champ légitimement variable) et en-têtes hors {@code Date}/{@code Content-Length}.
 * Asservir « les deux font 404 » laisserait passer deux {@code code} différents, ce
 * qui est précisément l'oracle que la story existe pour fermer (AD-10 : le client
 * classe sur le {@code code}, donc un attaquant le lit aussi).
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(CapturingEmailVerificationSender.Config.class)
class AntiEnumerationIntegrationTest {

    /** Seul chemin par lequel un test connaît un code : le port, jamais un endpoint. */
    @org.springframework.beans.factory.annotation.Autowired
    private CapturingEmailVerificationSender verificationCodes;

    private static final String STRONG = "Str0ng!Passw0rd";
    /** Identifiant hors de toute plage attribuée : la sonde « ça n'existe pas ». */
    private static final long UNKNOWN_TX_ID = 999_999L;
    private static final long UNKNOWN_EVIDENCE_ID = 888_888L;
    private static final String PARTNER_SECRET = "INBOUND-HMAC-SECRET-0123456789ABCDEF";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, AntiEnumerationIntegrationTest.class);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EvidenceFileRepository evidenceFiles;
    @Autowired AuditLogRepository auditLogs;
    @Autowired CompanyRepository companies;
    @Autowired PartnerHmacKeyRepository partnerKeys;
    @Autowired PartnerKeyNonceRepository nonces;
    @Autowired com.zlecaf.escrow.repository.EscrowTransactionRepository transactions;

    /** JWT de A (acheteur, partie), de C (étranger à tout) et l'id de la transaction A↔B. */
    private String tokenA;
    private String tokenC;
    private long txAB;

    @BeforeEach
    void seed() throws Exception {
        // Emails uniques par exécution. La base est propre à cette classe
        // (`PostgresTestSupport.registerDatabase` ci-dessus), mais `seed()` est un
        // `@BeforeEach` : les méthodes de test partagent donc la MÊME base, et
        // `users.email` est UNIQUE.
        // Réutiliser des littéraux ferait échouer l'inscription dès la deuxième méthode
        // et rendrait le résultat dépendant de l'ordre d'exécution — exactement le genre
        // de test qui devient rouge un mardi sans qu'une ligne de production ait bougé.
        String run = UUID.randomUUID().toString().substring(0, 8);
        tokenA = register("a-" + run + "@escrow.test");
        String emailB = "b-" + run + "@escrow.test";
        register(emailB);
        tokenC = register("c-" + run + "@escrow.test");
        txAB = createEscrow(tokenA, emailB);
    }

    // --- AC #1 : les sept routes utilisateur --------------------------------------

    @Test
    @DisplayName("AC#1 : sur CHAQUE endpoint escrow/preuve, « transaction d'un tiers » et « id inexistant » sont indistinguables")
    void everyUserEndpointGivesTheSameAnswerToAStrangerAndToAnUnknownId() throws Exception {
        // Un test par endpoint aurait été plus lisible et strictement moins utile :
        // ce qui doit tenir, c'est la propriété SUR TOUTE LA SURFACE. Une boucle sur
        // la liste des endpoints rend impossible d'en durcir un et d'oublier les
        // autres — le mode d'échec réel, puisque les gardes sont centralisées mais les
        // routes ne le sont pas.
        for (Endpoint endpoint : userEndpoints()) {
            Probe foreign = probe(endpoint.request(tokenC, txAB));
            Probe unknown = probe(endpoint.request(tokenC, UNKNOWN_TX_ID));

            assertThat(foreign)
                    .as("%s : la réponse au non-partie doit être identique à celle d'un id inconnu", endpoint.name())
                    .isEqualTo(unknown);
            assertThat(foreign.status())
                    .as("%s : et cette réponse commune doit être le 404 qui ne confirme rien", endpoint.name())
                    .isEqualTo(404);
            assertThat(field(foreign, "code"))
                    .as("%s : un code opaque, le meme pour les deux causes", endpoint.name())
                    .isEqualTo("TRANSACTION_NOT_FOUND");
            // Egalite EXACTE et non « ne contient pas l'identifiant » : ce dernier
            // serait un piege, « 404 » contient deja « 4 ». C'est de toute facon la
            // propriete plus forte — le message est un litteral constant, donc il ne
            // PEUT pas porter d'identifiant.
            assertThat(field(foreign, "message"))
                    .as("%s : un message constant, sans identifiant par construction", endpoint.name())
                    .isEqualTo("Transaction not found");
        }
    }

    @Test
    @DisplayName("AC#1 : une sonde d'énumération n'écrit rien — ni ligne de preuve, ni entrée d'audit")
    void probingWritesNothing() throws Exception {
        long evidenceBefore = evidenceFiles.count();
        long auditBefore = auditLogs.count();

        for (Endpoint endpoint : userEndpoints()) {
            probe(endpoint.request(tokenC, txAB));
            probe(endpoint.request(tokenC, UNKNOWN_TX_ID));
        }

        // L'indistinguabilité des réponses ne vaudrait rien si le refus laissait une
        // trace différenciée : une table d'audit qui grossit d'une ligne pour le cas
        // « tiers » et pas pour le cas « inexistant » serait le même oracle, décalé
        // d'un canal — et un vecteur d'inondation d'une table append-only (AD-25).
        assertThat(evidenceFiles.count()).as("aucune ligne de preuve écrite").isEqualTo(evidenceBefore);
        assertThat(auditLogs.count()).as("aucune entrée d'audit écrite").isEqualTo(auditBefore);
    }

    // --- AC #2 : le canal partenaire HMAC ----------------------------------------

    @Test
    @DisplayName("AC#2 : société signataire non partie vs transaction inconnue — mêmes réponses (angle mort SEC-L2)")
    void partnerChannelIsAsOpaqueAsTheHumanOne() throws Exception {
        // L'anti-énumération ne s'arrête pas au canal humain. Une clé HMAC valide
        // authentifie une société ; elle ne lui donne aucun droit de cartographier
        // l'espace des identifiants de la plateforme. La société créée ici n'est
        // partie à AUCUNE transaction (A et B n'ont pas de société).
        Company outsider = new Company();
        outsider.setName("Outsider Logistics");
        outsider = companies.save(outsider);

        String keyId = "key-antienum-" + UUID.randomUUID();
        PartnerHmacKey key = new PartnerHmacKey();
        key.setKeyId(keyId);
        key.setCompanyId(outsider.getId());
        key.setSecretKey(PARTNER_SECRET);
        key.setActive(true);
        partnerKeys.saveAndFlush(key);

        // Deux nonces DISTINCTS : la règle 3.2 est inchangée, et rejouer le même nonce
        // ferait répondre 401 AUTH_FAILED à la seconde sonde — le test passerait au
        // vert pour la mauvaise raison, en comparant deux refus d'authentification.
        Probe foreign = probe(signedPartnerDeposit(keyId, txAB, "nonce-" + UUID.randomUUID()));
        Probe unknown = probe(signedPartnerDeposit(keyId, UNKNOWN_TX_ID, "nonce-" + UUID.randomUUID()));

        assertThat(foreign).isEqualTo(unknown);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(field(foreign, "code")).isEqualTo("TRANSACTION_NOT_FOUND");
        assertThat(field(foreign, "message")).isEqualTo("Transaction not found");

        // Aucun des deux refus ne consomme de nonce (la règle 3.2 ne consomme qu'après
        // un dépôt RÉUSSI) : là encore, une consommation asymétrique serait un oracle
        // observable par le partenaire lui-même au rejeu.
        assertThat(nonces.count()).isZero();
    }

    // --- AC #3 : le niveau pièce --------------------------------------------------

    @Test
    @DisplayName("AC#3 : pièce inconnue vs pièce d'une AUTRE transaction — mêmes réponses, sans identifiant")
    void evidenceLevelLookupIsSealed() throws Exception {
        // Ici l'appelant EST partie : ce qu'on protège n'est plus l'existence de la
        // transaction mais celle d'une pièce d'un autre dossier. La requête scellée
        // findByIdAndTransactionId ne distingue déjà pas les deux cas ; ce test asservit
        // que l'enveloppe rendue ne les distingue pas non plus.
        long otherTx = createEscrow(tokenA, secondSellerEmail());
        EvidenceFile onOtherTx = seedActiveEvidence(otherTx);

        Probe foreignPiece = probe(get("/api/v1/escrow/{id}/evidence/{eid}/download", txAB, onOtherTx.getId())
                .header("Authorization", "Bearer " + tokenA));
        Probe unknownPiece = probe(get("/api/v1/escrow/{id}/evidence/{eid}/download", txAB, UNKNOWN_EVIDENCE_ID)
                .header("Authorization", "Bearer " + tokenA));

        assertThat(foreignPiece).isEqualTo(unknownPiece);
        assertThat(foreignPiece.status()).isEqualTo(404);
        assertThat(field(foreignPiece, "code")).isEqualTo("RESOURCE_NOT_FOUND");
        // Le message ne répète plus l'identifiant de pièce : sans quoi la comparaison
        // ci-dessus n'aurait pu être qu'« à identifiant près », donc jamais littérale,
        // donc jamais vraiment asservie.
        assertThat(field(foreignPiece, "message")).isEqualTo("Evidence not found");
    }

    // --- AC #4 : les rejets natifs de Spring, sur la vraie chaîne -----------------

    // Le `message` est asservi LITTÉRALEMENT dans les trois, et pas seulement le
    // statut et le `code` : le message par défaut de Spring nomme le chemin demandé,
    // les méthodes réellement supportées, le nom du paramètre et la valeur reçue. Sans
    // ces assertions, un retour au message par défaut passerait au vert tout en
    // rouvrant exactement la fuite que ces branches existent pour fermer.

    @Test
    @DisplayName("Une sous-ressource inexistante est un 404 codé, plus un 500 classé TRANSIENT")
    void unknownSubResourceIsACoded404() throws Exception {
        mvc.perform(get("/api/v1/escrow/{id}/inexistant", txAB).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    @DisplayName("Un identifiant non numérique est un 400 codé, plus un 500")
    void nonNumericIdIsACoded400() throws Exception {
        mvc.perform(get("/api/v1/escrow/abc").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                // Ni le nom du paramètre (`id`), ni la valeur reçue (`abc` — de la
                // donnée d'attaquant renvoyée telle quelle) ne franchissent la frontière.
                .andExpect(jsonPath("$.message").value("Invalid request parameter"));
    }

    @Test
    @DisplayName("Une méthode non supportée est un 405 codé portant Allow, plus un 500")
    void unsupportedMethodIsACoded405() throws Exception {
        mvc.perform(delete("/api/v1/escrow/{id}", txAB).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("Method not supported"))
                // L'en-tête `Allow` est posé par le handler lui-même : cette advice
                // n'étend pas ResponseEntityExceptionHandler, donc la ResponseEntity
                // construite à la main remplace le travail de
                // DefaultHandlerExceptionResolver, `Allow` compris. Asservi sur la VRAIE
                // chaîne, où le conteneur pourrait sinon donner l'illusion inverse.
                .andExpect(header().string("Allow", containsString("GET")));
    }

    // --- Le contrat de la Story 1.9, préservé ------------------------------------

    @Test
    @DisplayName("Story 1.9 préservée : sans jeton c'est un 403 NU (session morte), avec jeton un 404 CODÉ (verdict métier)")
    void bareForbiddenStaysTheSessionSignal() throws Exception {
        // Le discriminant client posé hier par la 1.9 : un 403 **nu** (aucune
        // enveloppe, aucun `code`) déconnecte, un 4xx **codé** ne déconnecte pas.
        // Multiplier les 403 applicatifs sur cette surface rapprochait deux
        // vocabulaires que la 1.9 venait de séparer ; le 404 codé les tient écartés.
        MvcResult noToken = mvc.perform(get("/api/v1/escrow/{id}", txAB))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(noToken.getResponse().getContentAsString())
                .as("un 403 de sécurité reste NU : aucun champ `code` à classifier")
                .doesNotContain("\"code\"");

        mvc.perform(get("/api/v1/escrow/{id}", txAB).header("Authorization", "Bearer " + tokenC))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    // --- La frontière assumée -----------------------------------------------------

    @Test
    @DisplayName("Exception assumée : une PARTIE qui retire la pièce de l'autre reçoit 403 FORBIDDEN — elle la voit déjà")
    void ownPieceGuardStaysAnHonest403() throws Exception {
        // Le seul 403 applicatif restant, et il ne révèle rien : la pièce refusée est
        // déjà listée à cet appelant par GET /{id}/evidence, prouvé une ligne plus bas.
        // C'est la formulation qui compte — « ne jamais révéler ce que l'appelant n'a
        // pas le droit de savoir », et non « tout refus devient 404 ».
        EvidenceFile sellerPiece = seedActiveEvidence(txAB);

        mvc.perform(get("/api/v1/escrow/{id}/evidence", txAB).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(sellerPiece.getId()));

        mvc.perform(post("/api/v1/escrow/{id}/evidence/{eid}/withdraw", txAB, sellerPiece.getId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // --- helpers ------------------------------------------------------------------

    /**
     * Une réponse HTTP réduite à ce qu'un attaquant peut observer. {@code record}
     * volontairement : l'égalité structurelle qu'il donne gratuitement EST le critère
     * de la story — comparer champ par champ laisserait la porte ouverte à un
     * discriminant qu'on aurait oublié d'énumérer.
     */
    private record Probe(int status, String body, Map<String, List<String>> headers) {}

    private Probe probe(RequestBuilder request) throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (String name : result.getResponse().getHeaderNames()) {
            // `Date` bouge à la seconde. `Content-Length` est exclu parce que le corps,
            // lui, n'est comparé qu'APRÈS normalisation du `timestamp` : deux
            // horodatages de longueurs différentes (Instant.toString() omet les
            // millisecondes nulles) donneraient deux Content-Length différents pour deux
            // corps que la story déclare identiques — un faux rouge sur le seul champ
            // qu'on s'autorise à faire varier. Tout le reste est comparé, y compris les
            // en-têtes de sécurité : un CSP ou un Vary divergent serait un discriminant
            // aussi utilisable que le statut.
            if (name.equalsIgnoreCase("Date") || name.equalsIgnoreCase("Content-Length")) {
                continue;
            }
            headers.put(name.toLowerCase(java.util.Locale.ROOT), result.getResponse().getHeaders(name));
        }
        return new Probe(result.getResponse().getStatus(), normalizeTimestamp(
                result.getResponse().getContentAsString()), headers);
    }

    /** Un champ de l'enveloppe, lu comme le lirait un client (et un attaquant). */
    private String field(Probe probe, String name) throws Exception {
        return objectMapper.readTree(probe.body()).get(name).asText();
    }

    /**
     * Neutralise le SEUL champ légitimement variable de l'enveloppe. Un
     * {@code replaceAll} borné au champ {@code timestamp} plutôt qu'un « effacer tout
     * ce qui ressemble à une date » : masquer plus large reviendrait à masquer
     * justement ce qu'on cherche à comparer.
     */
    private static String normalizeTimestamp(String body) {
        return body.replaceAll("\"timestamp\"\\s*:\\s*\"[^\"]*\"", "\"timestamp\":\"<normalised>\"");
    }

    /** Un endpoint sondable, nommé pour que l'échec dise lequel a divergé. */
    private record Endpoint(String name, java.util.function.BiFunction<String, Long, RequestBuilder> builder) {
        RequestBuilder request(String token, long txId) {
            return builder.apply(token, txId);
        }
    }

    /**
     * Les <b>sept</b> routes utilisateur qu'un porteur de JWT peut sonder sur une
     * transaction. Avec la route partenaire HMAC couverte par
     * {@link #partnerChannelIsAsOpaqueAsTheHumanOne()}, cela fait les <b>huit</b>
     * endpoints que {@code TransactionAccess} protège d'une seule garde — le décompte
     * est le même des deux côtés, et c'est ce qui rend « toute la surface » vérifiable.
     *
     * <p>Le niveau pièce y figure avec des identifiants arbitraires : la garde
     * d'appartenance tranche AVANT toute recherche de pièce, donc la réponse doit
     * rester la même que la pièce existe ou non — c'est précisément l'ordre des
     * gardes qui est asservi ici.
     */
    private static List<Endpoint> userEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        endpoints.add(new Endpoint("GET /{id}", (token, id) ->
                get("/api/v1/escrow/{id}", id).header("Authorization", "Bearer " + token)));
        endpoints.add(new Endpoint("POST /{id}/event", (token, id) ->
                post("/api/v1/escrow/{id}/event", id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"PAY_FUNDS\"}")));
        endpoints.add(new Endpoint("POST /{id}/dispute", (token, id) ->
                multipart("/api/v1/escrow/{id}/dispute", id)
                        .file(pdfPart())
                        .param("comment", "the item never arrived")
                        .header("Authorization", "Bearer " + token)));
        endpoints.add(new Endpoint("POST /{id}/evidence", (token, id) ->
                multipart("/api/v1/escrow/{id}/evidence", id)
                        .file(pdfPart())
                        .header("Authorization", "Bearer " + token)));
        endpoints.add(new Endpoint("GET /{id}/evidence", (token, id) ->
                get("/api/v1/escrow/{id}/evidence", id).header("Authorization", "Bearer " + token)));
        endpoints.add(new Endpoint("GET /{id}/evidence/{eid}/download", (token, id) ->
                get("/api/v1/escrow/{id}/evidence/{eid}/download", id, UNKNOWN_EVIDENCE_ID)
                        .header("Authorization", "Bearer " + token)));
        endpoints.add(new Endpoint("POST /{id}/evidence/{eid}/withdraw", (token, id) ->
                post("/api/v1/escrow/{id}/evidence/{eid}/withdraw", id, UNKNOWN_EVIDENCE_ID)
                        .header("Authorization", "Bearer " + token)));
        return endpoints;
    }

    private RequestBuilder signedPartnerDeposit(String keyId, long txId, String nonce) {
        MockMultipartFile file = pdfPart();
        List<MultipartFile> files = List.of(file);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String canonical = PartnerSignatureVerifier.canonicalString(keyId, txId, timestamp, nonce, files, null, null);
        return multipart("/api/v1/partner/escrow/{id}/evidence", txId)
                .file(file)
                .header("X-Escrow-Key-Id", keyId)
                .header("X-Escrow-Signature", HmacSigner.sign(canonical, PARTNER_SECRET))
                .header("X-Escrow-Timestamp", timestamp)
                .header("X-Escrow-Nonce", nonce);
    }

    private static MockMultipartFile pdfPart() {
        return new MockMultipartFile("files", "proof.pdf", "application/pdf",
                ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                        .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Sème une pièce ACTIVE directement en base, attribuée au VENDEUR de la
     * transaction. Passer par l'API demanderait un stockage objet et un antivirus
     * joignables, alors qu'aucune garde testée ici ne va jusqu'au binaire : la
     * requête scellée et la garde « pièce à soi » tranchent bien avant.
     */
    private EvidenceFile seedActiveEvidence(long txId) {
        EvidenceFile evidence = new EvidenceFile();
        evidence.setTransactionId(txId);
        // Attribution humaine cohérente avec le CHECK ck_evidence_attribution (V3) :
        // un uploader utilisateur, aucune société partenaire. L'uploader n'est PAS A,
        // ce qui est ce qui rend la garde « pièce à soi » atteignable.
        evidence.setUploadedByUserId(sellerIdOf(txId));
        evidence.setUploaderType(UploaderType.SELLER);
        evidence.setOriginalFilename("counterparty.pdf");
        evidence.setMimeType("application/pdf");
        evidence.setSizeBytes(64L);
        evidence.setStorageKey(txId + "/" + UUID.randomUUID());
        evidence.setStatus(EvidenceStatus.ACTIVE);
        evidence.setCreatedAt(Instant.now());
        return evidenceFiles.saveAndFlush(evidence);
    }

    private Long sellerIdOf(long txId) {
        return transactions.findById(txId).orElseThrow().getSellerId();
    }

    private String secondSellerEmail() throws Exception {
        String email = "d-" + UUID.randomUUID().toString().substring(0, 8) + "@escrow.test";
        register(email);
        return email;
    }

    /** Inscrit un utilisateur conforme à la politique de mot de passe et rend son JWT. */
    /**
     * Compte AUTHENTIFIÉ. Depuis la Story 2.4 l'inscription ne rend plus de session : elle
     * crée un compte non vérifié et envoie un code. Ce test veut un utilisateur qui peut
     * appeler l'API, pas éprouver le parcours d'inscription — la fabrique partagée traverse
     * donc les deux vrais endpoints et rend le jeton.
     */
    private String register(String email) throws Exception {
        return VerifiedAccounts.createVerified(mvc, objectMapper, verificationCodes, email);
    }

    /** Crée une transaction escrow réelle via l'API et rend son identifiant. */
    private long createEscrow(String buyerToken, String sellerEmail) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/escrow")
                        .header("Authorization", "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerEmail\":\"" + sellerEmail + "\",\"amount\":1000.00,"
                                + "\"currency\":\"USD\",\"description\":\"anti-enumeration fixture\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }
}
