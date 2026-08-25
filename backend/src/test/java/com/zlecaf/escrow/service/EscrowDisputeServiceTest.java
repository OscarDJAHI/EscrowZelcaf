package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.EscrowEvent;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import com.zlecaf.escrow.service.scan.MalwareScanGateway;
import com.zlecaf.escrow.service.scan.MalwareScanUnavailableException;
import com.zlecaf.escrow.service.scan.ScanVerdict;
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EscrowDtos.DisputeOpenedDto;
import com.zlecaf.escrow.web.dto.EscrowDtos.TransactionDetailDto;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.multipart.MultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Observable proof of the composite, atomic dispute opening against a real
 * Postgres (Flyway migrations applied, JSONB audit works). The transition to
 * DISPUTED and the mandatory evidence deposit either both commit or both roll
 * back — the AD-1 atomicity gate.
 *
 * <p>Unlike {@link EvidenceServiceTest}, this class runs each service call in
 * its <em>own</em> real transaction: the class is marked
 * {@link Propagation#NOT_SUPPORTED} so the {@code @DataJpaTest} test method is
 * NOT wrapped in a transaction. That is what lets a rollback (or a
 * REQUIRES_NEW failure audit) actually commit/roll-back to the database, so a
 * fresh repository read observes the committed truth — not dirty in-memory
 * state that a shared test transaction would mask. Because nothing is auto
 * rolled back between methods, every assertion is scoped by transaction id.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// SecretCipher + EncryptedStringConverter : les colonnes de secrets sont converties
// depuis la Story 1.7, Hibernate les réclame à la construction du métamodèle.
@Import({EscrowService.class, EvidenceService.class, EscrowStateMachine.class, AuditService.class,
        EvidenceContentValidator.class, TransactionAccess.class, SecretCipher.class,
        EncryptedStringConverter.class, EscrowDisputeServiceTest.TestConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscrowDisputeServiceTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, EscrowDisputeServiceTest.class);
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway owns the schema; Hibernate must not try to create-drop it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    // --- Revue 1.8 : la route de litige composite, jamais couverte par l'antivirus ---

    @Test
    @DisplayName("Revue 1.8 — une PANNE de scanner sur la route de litige n'est PAS auditee")
    void disputeScannerOutageIsNotAudited() {
        // Le catch (RuntimeException) d'openDispute avalait MalwareScanUnavailableException
        // comme les autres et ecrivait une ligne recordFailure. Deux consequences : il
        // violait l'invariant explicite de la story (« indisponibilite NON auditee »,
        // sans quoi un incident d'infrastructure inonde une table append-only a
        // retention >= 5 ans, une ligne par tentative), et il y persistait l'hote et le
        // port de clamd via ex.getMessage() — que GlobalExceptionHandler prend
        // precisement soin de tenir hors de la reponse HTTP.
        User buyer = persistUser("dispmw1@example.com", Role.BUYER);
        User seller = persistUser("dispmw1s@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        scanner.unavailable = true;
        try {
            assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                    List.of(pdf("receipt.pdf")), "the item never arrived", null))
                    .isInstanceOf(MalwareScanUnavailableException.class);
        } finally {
            scanner.unavailable = false;
        }

        // Rien n'a bouge, et RIEN n'a ete audite.
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("Revue 1.8 — un fichier INFECTE sur la route de litige : rollback complet, et l'audit porte l'etat DURABLE")
    void disputeWithInfectedFileRollsBackAndAuditsTheDurableState() {
        User buyer = persistUser("dispmw2@example.com", Role.BUYER);
        User seller = persistUser("dispmw2s@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        scanner.infectedWhen = content -> true;
        try {
            assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                    List.of(pdf("receipt.pdf")), "the item never arrived", null))
                    .isInstanceOf(BadRequestException.class);
        } finally {
            scanner.infectedWhen = content -> false;
        }

        // La transition est annulee : la transaction n'a jamais ete DISPUTED.
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();

        // Le rejet survit au rollback (REQUIRES_NEW). DEUX lignes, qui disent deux
        // faits distincts : « ce fichier a ete refuse pour malware » (la trace du
        // NFR-P7, avec le detail) et « cette tentative d'ouverture de litige a
        // echoue » (comportement PREEXISTANT, identique pour un fichier trop gros ou
        // un stockage injoignable). La seconde n'est pas un doublon : la supprimer
        // ferait disparaitre la trace qu'un litige a ete tente. Contrairement a une
        // panne de scanner, un rejet EST auditable — l'invariant de la story porte sur
        // l'indisponibilite, pas sur le rejet.
        List<AuditLog> audit = auditFor(tx.getId());
        assertThat(audit).hasSize(2);
        AuditLog rejection = audit.stream()
                .filter(a -> a.getPayload().has("action")
                        && "EVIDENCE_REJECTED_MALWARE".equals(a.getPayload().get("action").asText()))
                .findFirst().orElseThrow();
        assertThat(audit).anySatisfy(a ->
                assertThat(a.getPayload().path("outcome").asText()).isEqualTo("REJECTED"));

        // ...et il porte l'etat REELLEMENT commite. openDispute bascule l'entite en
        // DISPUTED AVANT de deposer : auditer l'instantane de l'appelant gravait
        // DISPUTED — un etat que cette transaction n'a jamais atteint — pour toujours,
        // dans une table WORM.
        assertThat(rejection.getPreviousState()).isEqualTo(EscrowState.FUNDS_LOCKED.name());
        assertThat(rejection.getNextState()).isEqualTo(EscrowState.FUNDS_LOCKED.name());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        EvidenceStorage evidenceStorage() {
            return new InMemoryEvidenceStorage();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        /**
         * Story 1.8 : {@code MalwareScanGateway} est desormais une dependance
         * OBLIGATOIRE d'{@code EvidenceService}. Sain par DEFAUT pour que cette classe
         * continue de prouver exactement ce qu'elle prouvait — jamais en desactivant
         * le scan, qui n'a volontairement aucun interrupteur.
         *
         * <p>Scriptable depuis la revue 1.8 : il rendait auparavant TOUJOURS « sain »,
         * si bien qu'aucun fichier infecte ni aucune panne de scanner n'a jamais
         * traverse la route de litige composite. C'est exactement pour cela que les
         * defauts de cette route — indisponibilite auditee contre l'invariant, hote et
         * port de clamd graves dans une table a retention >= 5 ans, etat jamais commite
         * — sont passes inapercus alors que la story cite les trois routes.
         */
        @Bean
        ScriptedScanner malwareScanner() {
            return new ScriptedScanner();
        }
    }

    /** Faux scanner pilotable test par test (revue 1.8). */
    static class ScriptedScanner implements MalwareScanGateway {
        volatile java.util.function.Predicate<byte[]> infectedWhen = content -> false;
        volatile boolean unavailable = false;

        @Override
        public ScanVerdict scan(byte[] content) {
            if (unavailable) {
                throw new MalwareScanUnavailableException("panne simulee");
            }
            return infectedWhen.test(content) ? ScanVerdict.infected("Test.Signature") : ScanVerdict.clean();
        }
    }

    /**
     * In-memory {@link EvidenceStorage} fake: no MinIO needed to prove the DB
     * round-trip. {@code failOnStore} simulates an unreachable object store
     * (MinIO down) so the rollback gate can be exercised.
     */
    static class InMemoryEvidenceStorage implements EvidenceStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        volatile boolean failOnStore = false;

        @Override
        public String store(Long transactionId, byte[] content, String contentType) {
            if (failOnStore) {
                throw new RuntimeException("simulated object-store outage");
            }
            String key = transactionId + "/" + UUID.randomUUID();
            objects.put(key, content.clone());
            return key;
        }

        @Override
        public InputStream load(String storageKey) {
            byte[] bytes = objects.get(storageKey);
            if (bytes == null) {
                throw new EvidenceNotFoundException(storageKey, null);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String storageKey) {
            objects.remove(storageKey);
        }
    }

    @Autowired
    private EscrowService escrowService;
    @Autowired
    private UserRepository users;
    @Autowired
    private EscrowTransactionRepository transactions;
    @Autowired
    private EvidenceFileRepository evidenceFiles;
    @Autowired
    private AuditLogRepository auditLogs;
    @Autowired
    private EvidenceStorage storage;
    @Autowired
    private ScriptedScanner scanner;

    // --- fixtures (committed via the repositories, since the test is non-transactional) ---

    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] jpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00,
                0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9};
    }

    private static MockMultipartFile pdf(String filename) {
        return new MockMultipartFile("files", filename, "application/pdf", pdfBytes());
    }

    private static MockMultipartFile jpeg(String filename) {
        return new MockMultipartFile("files", filename, "image/jpeg", jpegBytes());
    }

    private User persistUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("x");
        user.setRole(role);
        return users.save(user);
    }

    private EscrowTransaction persistTransaction(Long buyerId, Long sellerId, EscrowState state) {
        EscrowTransaction tx = new EscrowTransaction();
        tx.setBuyerId(buyerId);
        tx.setSellerId(sellerId);
        tx.setAmount(new BigDecimal("1000.00"));
        tx.setCurrency("USD");
        tx.setState(state);
        return transactions.save(tx);
    }

    private EscrowState stateOf(Long txId) {
        return transactions.findById(txId).orElseThrow().getState();
    }

    private List<AuditLog> auditFor(Long txId) {
        // Causal order (timestamp, then id): the compliance trail must show the
        // OPEN_DISPUTE transition before the EVIDENCE_ADDED rows it caused, even
        // when they share a timestamp within the same transaction.
        return auditLogs.findByTransactionIdOrderByTimestampAscIdAsc(txId);
    }

    private List<EvidenceFile> evidenceFor(Long txId) {
        return evidenceFiles.findByTransactionIdOrderByCreatedAtAscIdAsc(txId);
    }

    private static boolean isTransitionSuccess(AuditLog log) {
        JsonNode p = log.getPayload();
        return "SUCCESS".equals(p.path("outcome").asText()) && "OPEN_DISPUTE".equals(p.path("event").asText());
    }

    private static boolean isEvidenceAdded(AuditLog log) {
        return "EVIDENCE_ADDED".equals(log.getPayload().path("action").asText());
    }

    private static boolean isRejected(AuditLog log) {
        return "REJECTED".equals(log.getPayload().path("outcome").asText());
    }

    // --- happy path ---

    @Test
    @DisplayName("Opening a dispute from FUNDS_LOCKED transitions to DISPUTED and attaches evidence in one atomic act")
    void openDisputeHappyPath() {
        User buyer = persistUser("dispbuyer1@example.com", Role.BUYER);
        User seller = persistUser("dispseller1@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        DisputeOpenedDto result = escrowService.openDispute(actor, tx.getId(),
                List.of(jpeg("photo.jpg"), pdf("receipt.pdf")),
                "the item never arrived", "2026-07-15T10:15:30+02:00");

        // Response contract: DISPUTED transaction + the created evidence pieces.
        assertThat(result.transaction().state()).isEqualTo(EscrowState.DISPUTED);
        assertThat(result.evidence()).hasSize(2)
                .extracting(EvidenceDto::status).containsOnly(EvidenceStatus.ACTIVE);

        // Committed state (fresh read, real transaction boundary).
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.DISPUTED);

        List<EvidenceFile> rows = evidenceFor(tx.getId());
        assertThat(rows).hasSize(2)
                .allSatisfy(e -> {
                    assertThat(e.getStatus()).isEqualTo(EvidenceStatus.ACTIVE);
                    assertThat(e.getTransactionId()).isEqualTo(tx.getId());
                });

        // Audit: exactly one OPEN_DISPUTE transition (FUNDS_LOCKED -> DISPUTED) and
        // one EVIDENCE_ADDED per file, all committed in the same transaction.
        List<AuditLog> audit = auditFor(tx.getId());
        assertThat(audit).hasSize(3);
        AuditLog transition = audit.stream().filter(EscrowDisputeServiceTest::isTransitionSuccess)
                .findFirst().orElseThrow();
        assertThat(transition.getPreviousState()).isEqualTo("FUNDS_LOCKED");
        assertThat(transition.getNextState()).isEqualTo("DISPUTED");
        assertThat(audit.stream().filter(EscrowDisputeServiceTest::isEvidenceAdded).count()).isEqualTo(2);
        // Causal order in the compliance trail: the OPEN_DISPUTE transition must
        // come first, even though it shares a timestamp with the evidence rows.
        assertThat(isTransitionSuccess(audit.get(0))).isTrue();
        assertThat(isEvidenceAdded(audit.get(1))).isTrue();
        assertThat(isEvidenceAdded(audit.get(2))).isTrue();
    }

    // --- 400: composite pre-conditions (no transition happens at all) ---

    @Test
    @DisplayName("Opening with no files is a 400 and never transitions")
    void openDisputeNoFilesIsBadRequest() {
        User buyer = persistUser("dispbuyer2@example.com", Role.BUYER);
        User seller = persistUser("dispseller2@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(), "the item never arrived", null))
                .isInstanceOf(BadRequestException.class);

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("Opening with a comment shorter than 10 characters (after trim) is a 400 and never transitions")
    void openDisputeShortCommentIsBadRequest() {
        User buyer = persistUser("dispbuyer3@example.com", Role.BUYER);
        User seller = persistUser("dispseller3@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // 9 significant chars padded with spaces: trim() must reject it.
        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "   too few   ", null))
                .isInstanceOf(BadRequestException.class);

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    // --- 400: reused ingestion validation rolls back the transition (AR-13 + AD-1) ---

    @Test
    @DisplayName("An invalid file rolls the whole opening back (no DISPUTED, no evidence, no success audit) but leaves a durable REJECTED audit")
    void openDisputeInvalidFileRollsBack() {
        User buyer = persistUser("dispbuyer4@example.com", Role.BUYER);
        User seller = persistUser("dispseller4@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // 0-byte file: rejected by the reused EvidenceService validation, identical
        // to POST /{id}/evidence. The transition ran first, so this proves rollback.
        MockMultipartFile empty = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(empty), "the item never arrived", null))
                .isInstanceOf(BadRequestException.class);

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        // The transition's recordSuccess joined the (now rolled-back) transaction,
        // but the deposit-stage failure is durably recorded via REQUIRES_NEW: a
        // rejected opening leaves the same audit trail as an illegal transition.
        List<AuditLog> audit = auditFor(tx.getId());
        assertThat(audit).hasSize(1);
        assertThat(isRejected(audit.get(0))).isTrue();
        assertThat(audit.stream().anyMatch(EscrowDisputeServiceTest::isTransitionSuccess)).isFalse();
    }

    // --- 500: object-store failure rolls back atomically (AD-1 gate, non-negotiable) ---

    @Test
    @DisplayName("A storage failure during deposit rolls back the transition (tx stays FUNDS_LOCKED, no evidence, no success audit) but leaves a durable REJECTED audit")
    void openDisputeStorageFailureRollsBackTransition() {
        User buyer = persistUser("dispbuyer5@example.com", Role.BUYER);
        User seller = persistUser("dispseller5@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        InMemoryEvidenceStorage fake = (InMemoryEvidenceStorage) storage;
        fake.failOnStore = true;
        try {
            // Assert the failure is specifically the object-store outage — not an
            // unrelated 400/403 that would make the rollback assertions pass for
            // the wrong reason and silently stop exercising this gate.
            assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                    List.of(pdf("receipt.pdf")), "the item never arrived", null))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("simulated object-store outage");
        } finally {
            fake.failOnStore = false;
        }

        // Atomicity: the transition committed nothing because the deposit failed.
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        // No SUCCESS audit (rolled back), but the deposit-stage failure is durably
        // recorded via REQUIRES_NEW — a storage outage no longer vanishes silently.
        List<AuditLog> audit = auditFor(tx.getId());
        assertThat(audit.stream().anyMatch(EscrowDisputeServiceTest::isTransitionSuccess)).isFalse();
        assertThat(audit).hasSize(1);
        assertThat(isRejected(audit.get(0))).isTrue();
    }

    // --- 409: state not openable (illegal transition, recordFailure written) ---

    @Test
    @DisplayName("Opening an already-DISPUTED transaction is an illegal transition (409) and writes a failure audit")
    void openDisputeAlreadyDisputedIsIllegal() {
        User buyer = persistUser("dispbuyer6@example.com", Role.BUYER);
        User seller = persistUser("dispseller6@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getCode())
                        .isEqualTo(ErrorCode.ILLEGAL_TRANSITION));

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.DISPUTED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        // recordFailure runs in its own (REQUIRES_NEW) transaction, so it survives.
        List<AuditLog> audit = auditFor(tx.getId());
        assertThat(audit).hasSize(1);
        assertThat(isRejected(audit.get(0))).isTrue();
    }

    @Test
    @DisplayName("Opening a RELEASED transaction that was never disputed is TRANSACTION_TERMINAL (409) and never transitions")
    void openDisputeTerminalReleasedIsTerminal() {
        User buyer = persistUser("dispbuyer7@example.com", Role.BUYER);
        User seller = persistUser("dispseller7@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.RELEASED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getCode())
                        .isEqualTo(ErrorCode.TRANSACTION_TERMINAL));

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.RELEASED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).anyMatch(EscrowDisputeServiceTest::isRejected);
    }

    /**
     * The pair the state alone cannot separate. Both transactions end RELEASED and
     * both reject an OPEN_DISPUTE replay with a 409 — but one was arbitrated after a
     * dispute and the other simply completed on delivery, and the client is told
     * which. Only the audit trail carries that history, so this proof needs the real
     * database: the transaction is walked through DISPUTED -> RELEASED by the service
     * itself, never persisted straight into its end state.
     */
    @Test
    @DisplayName("Replaying a dispute onto an arbitrated (RELEASED-via-dispute) transaction is DISPUTE_ALREADY_RESOLVED")
    void openDisputeAfterArbitrationIsAlreadyResolved() {
        User buyer = persistUser("dispbuyer7b@example.com", Role.BUYER);
        User seller = persistUser("dispseller7b@example.com", Role.SELLER);
        User admin = persistUser("dispadmin7b@example.com", Role.ADMIN);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal buyerActor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);
        AuthPrincipal adminActor = new AuthPrincipal(admin.getId(), admin.getEmail(), Role.ADMIN);

        // Real history: a dispute is opened, then an admin arbitrates it to RELEASED.
        escrowService.openDispute(buyerActor, tx.getId(), List.of(pdf("receipt.pdf")),
                "the item never arrived", null);
        escrowService.applyEvent(adminActor, tx.getId(), EscrowEvent.RESOLVE_RELEASE);
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.RELEASED);

        assertThatThrownBy(() -> escrowService.openDispute(buyerActor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getCode())
                        .isEqualTo(ErrorCode.DISPUTE_ALREADY_RESOLVED));
    }

    @Test
    @DisplayName("A REFUNDED transaction is only reachable through a dispute, so a replay is DISPUTE_ALREADY_RESOLVED")
    void openDisputeAfterRefundIsAlreadyResolved() {
        User buyer = persistUser("dispbuyer7c@example.com", Role.BUYER);
        User seller = persistUser("dispseller7c@example.com", Role.SELLER);
        User admin = persistUser("dispadmin7c@example.com", Role.ADMIN);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal buyerActor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);
        AuthPrincipal adminActor = new AuthPrincipal(admin.getId(), admin.getEmail(), Role.ADMIN);

        escrowService.openDispute(buyerActor, tx.getId(), List.of(pdf("receipt.pdf")),
                "the item never arrived", null);
        escrowService.applyEvent(adminActor, tx.getId(), EscrowEvent.RESOLVE_REFUND);
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.REFUNDED);

        assertThatThrownBy(() -> escrowService.openDispute(buyerActor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getCode())
                        .isEqualTo(ErrorCode.DISPUTE_ALREADY_RESOLVED));
    }

    // --- 404: non-party (rejected before any transition, no audit) ---

    @Test
    @DisplayName("A non-party gets the SAME opaque 404 as an unknown id, before any transition, and writes nothing")
    void openDisputeNonPartyIsNotFound() {
        User buyer = persistUser("dispbuyer8@example.com", Role.BUYER);
        User seller = persistUser("dispseller8@example.com", Role.SELLER);
        User stranger = persistUser("dispstranger8@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        // Story 1.10 : NotFoundException et non plus ForbiddenException. Le stranger
        // ne doit pas pouvoir distinguer « ce litige existe mais n'est pas le tien »
        // de « cet identifiant n'existe pas » — les deux repondent desormais
        // ApiExceptions.transactionNotFound(), meme type, meme code, meme message.
        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(NotFoundException.class);

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        // Membership is checked before the state-machine call, so no failure is logged.
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    // --- 403: role not authorised (seller opening from SHIPPED, matrix allows only BUYER) ---

    @Test
    @DisplayName("A seller opening a dispute from SHIPPED is unauthorised (403) and never transitions, but writes a failure audit")
    void openDisputeSellerFromShippedIsUnauthorised() {
        User buyer = persistUser("dispbuyer9@example.com", Role.BUYER);
        User seller = persistUser("dispseller9@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.SHIPPED);
        AuthPrincipal actor = new AuthPrincipal(seller.getId(), seller.getEmail(), Role.SELLER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED_TRANSITION));

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.SHIPPED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).anyMatch(EscrowDisputeServiceTest::isRejected);
    }

    // --- 404: unknown transaction ---

    @Test
    @DisplayName("Opening a dispute on an unknown transaction is a 404")
    void openDisputeUnknownTransactionIsNotFound() {
        User buyer = persistUser("dispbuyer10@example.com", Role.BUYER);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, 999_999L,
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(NotFoundException.class);
    }

    // --- buyer opening from SHIPPED is allowed (matrix: SHIPPED + OPEN_DISPUTE -> BUYER) ---

    @Test
    @DisplayName("A buyer opening a dispute from SHIPPED is allowed and transitions to DISPUTED")
    void openDisputeBuyerFromShippedSucceeds() {
        User buyer = persistUser("dispbuyer11@example.com", Role.BUYER);
        User seller = persistUser("dispseller11@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.SHIPPED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        DisputeOpenedDto result = escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item is damaged", null);

        assertThat(result.transaction().state()).isEqualTo(EscrowState.DISPUTED);
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.DISPUTED);
        assertThat(evidenceFor(tx.getId())).hasSize(1);
    }

    // --- the generic /event path must NOT open a dispute (mandatory-evidence backdoor) ---

    @Test
    @DisplayName("The generic applyEvent path rejects OPEN_DISPUTE so a dispute cannot be opened without evidence")
    void applyEventRejectsOpenDisputeToForceCompositeEndpoint() {
        User buyer = persistUser("dispbuyer12@example.com", Role.BUYER);
        User seller = persistUser("dispseller12@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.applyEvent(actor, tx.getId(), EscrowEvent.OPEN_DISPUTE))
                .isInstanceOf(BadRequestException.class);

        // No evidence-less dispute: the transaction stays FUNDS_LOCKED, nothing audited.
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    // --- Story 5.1: shared file cap on dispute opening + audit-trail cap ---

    @Test
    @DisplayName("Opening a dispute with more than MAX_FILES_PER_DEPOSIT files is a 400 before any transition or buffering")
    void openDisputeOverFileCapIsBadRequest() {
        User buyer = persistUser("dispcap1@example.com", Role.BUYER);
        User seller = persistUser("dispcap1s@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i <= PlatformLimits.MAX_FILES_PER_DEPOSIT; i++) {
            files.add(pdf("f" + i + ".pdf"));
        }

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                files, "the item never arrived", null))
                .isInstanceOf(BadRequestException.class);

        // No transition, no evidence, no audit: rejected before the state change.
        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.FUNDS_LOCKED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("getDetail hard-caps the audit trail at MAX_LIST_RESULTS rows even when more exist")
    void getDetailCapsAuditTrail() {
        User buyer = persistUser("detcap1@example.com", Role.BUYER);
        User seller = persistUser("detcap1s@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        ObjectMapper om = new ObjectMapper();
        int over = PlatformLimits.MAX_LIST_RESULTS + 5;
        for (int i = 0; i < over; i++) {
            AuditLog log = new AuditLog();
            log.setTransactionId(tx.getId());
            log.setActionBy(buyer.getId());
            log.setPreviousState("FUNDS_LOCKED");
            log.setNextState("FUNDS_LOCKED");
            ObjectNode payload = om.createObjectNode();
            payload.put("action", "EVIDENCE_ADDED");
            log.setPayload(payload);
            auditLogs.save(log);
        }

        TransactionDetailDto detail = escrowService.getDetail(actor, tx.getId());

        assertThat(detail.auditLogs()).hasSize(PlatformLimits.MAX_LIST_RESULTS);
    }

    // --- Story 5.2: recent-tail selection on the audit trail ---

    /** Persists one audit row with an explicit timestamp (survives @PrePersist, which only fills a null). */
    private AuditLog persistAuditAt(Long txId, Long actorId, Instant timestamp) {
        AuditLog log = new AuditLog();
        log.setTransactionId(txId);
        log.setActionBy(actorId);
        log.setPreviousState("FUNDS_LOCKED");
        log.setNextState("FUNDS_LOCKED");
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("action", "EVIDENCE_ADDED");
        log.setPayload(payload);
        log.setTimestamp(timestamp);
        return auditLogs.save(log);
    }

    @Test
    @DisplayName("the bounded DESC trail overload keeps the MOST RECENT rows (recent tail), newest first")
    void auditDescOverloadKeepsMostRecentRows() {
        User buyer = persistUser("audRT@example.com", Role.BUYER);
        User seller = persistUser("audRTs@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        AuditLog oldest = persistAuditAt(tx.getId(), buyer.getId(), t0);
        AuditLog middle = persistAuditAt(tx.getId(), buyer.getId(), t0.plusSeconds(60));
        AuditLog newest = persistAuditAt(tx.getId(), buyer.getId(), t0.plusSeconds(120));

        // LIMIT 2 over 3 rows: the DESC overload returns the two NEWEST, newest first
        // (an ASC limit would have returned oldest+middle and dropped `newest`).
        List<AuditLog> recent = auditLogs.findByTransactionIdOrderByTimestampDescIdDesc(
                tx.getId(), PageRequest.of(0, 2));

        assertThat(recent).extracting(AuditLog::getId)
                .containsExactly(newest.getId(), middle.getId());
        assertThat(recent).extracting(AuditLog::getId).doesNotContain(oldest.getId());
    }

    @Test
    @DisplayName("getDetail keeps the MOST RECENT audit rows when over the cap, restored in ascending order")
    void getDetailKeepsRecentTailInAscendingOrder() {
        User buyer = persistUser("audTail@example.com", Role.BUYER);
        User seller = persistUser("audTails@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        int over = PlatformLimits.MAX_LIST_RESULTS + 5;
        for (int i = 0; i < over; i++) {
            persistAuditAt(tx.getId(), buyer.getId(), t0.plusSeconds(i));
        }

        TransactionDetailDto detail = escrowService.getDetail(actor, tx.getId());

        // The 5 oldest are dropped, the newest MAX_LIST_RESULTS are kept, and the
        // in-memory reverse restores ascending order: first = t0+5, last = t0+over-1.
        assertThat(detail.auditLogs()).hasSize(PlatformLimits.MAX_LIST_RESULTS);
        assertThat(detail.auditLogs()).extracting(dto -> dto.timestamp())
                .isSortedAccordingTo(Instant::compareTo);
        assertThat(detail.auditLogs().get(0).timestamp()).isEqualTo(t0.plusSeconds(5));
        assertThat(detail.auditLogs().get(detail.auditLogs().size() - 1).timestamp())
                .isEqualTo(t0.plusSeconds(over - 1L));
    }
}
