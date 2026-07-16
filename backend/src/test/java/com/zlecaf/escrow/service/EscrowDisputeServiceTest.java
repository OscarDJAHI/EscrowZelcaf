package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.AuditLog;
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
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.dto.EscrowDtos.DisputeOpenedDto;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
@Import({EscrowService.class, EvidenceService.class, EscrowStateMachine.class, AuditService.class,
        EvidenceContentValidator.class, TransactionAccess.class, EscrowDisputeServiceTest.TestConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EscrowDisputeServiceTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway owns the schema; Hibernate must not try to create-drop it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
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
    @DisplayName("An invalid file rolls the whole opening back: no DISPUTED, no evidence, no success audit")
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
        // The transition's recordSuccess joined the (now rolled-back) transaction.
        assertThat(auditFor(tx.getId())).isEmpty();
    }

    // --- 500: object-store failure rolls back atomically (AD-1 gate, non-negotiable) ---

    @Test
    @DisplayName("A storage failure during deposit rolls back the transition: tx stays FUNDS_LOCKED, no evidence, no success audit")
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
        assertThat(auditFor(tx.getId()).stream().anyMatch(EscrowDisputeServiceTest::isTransitionSuccess)).isFalse();
        assertThat(auditFor(tx.getId())).isEmpty();
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
                .satisfies(ex -> assertThat(((TransitionException) ex).getReason())
                        .isEqualTo(TransitionException.Reason.ILLEGAL_TRANSITION));

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.DISPUTED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        // recordFailure runs in its own (REQUIRES_NEW) transaction, so it survives.
        List<AuditLog> audit = auditFor(tx.getId());
        assertThat(audit).hasSize(1);
        assertThat(isRejected(audit.get(0))).isTrue();
    }

    @Test
    @DisplayName("Opening a terminal RELEASED transaction is an illegal transition (409) and never transitions")
    void openDisputeTerminalReleasedIsIllegal() {
        User buyer = persistUser("dispbuyer7@example.com", Role.BUYER);
        User seller = persistUser("dispseller7@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.RELEASED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(TransitionException.class)
                .satisfies(ex -> assertThat(((TransitionException) ex).getReason())
                        .isEqualTo(TransitionException.Reason.ILLEGAL_TRANSITION));

        assertThat(stateOf(tx.getId())).isEqualTo(EscrowState.RELEASED);
        assertThat(evidenceFor(tx.getId())).isEmpty();
        assertThat(auditFor(tx.getId())).anyMatch(EscrowDisputeServiceTest::isRejected);
    }

    // --- 403: non-party (rejected before any transition, no audit) ---

    @Test
    @DisplayName("A non-party is rejected with ForbiddenException before any transition and writes nothing")
    void openDisputeNonPartyIsForbidden() {
        User buyer = persistUser("dispbuyer8@example.com", Role.BUYER);
        User seller = persistUser("dispseller8@example.com", Role.SELLER);
        User stranger = persistUser("dispstranger8@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> escrowService.openDispute(actor, tx.getId(),
                List.of(pdf("receipt.pdf")), "the item never arrived", null))
                .isInstanceOf(ForbiddenException.class);

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
                .satisfies(ex -> assertThat(((TransitionException) ex).getReason())
                        .isEqualTo(TransitionException.Reason.UNAUTHORIZED));

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
                .isInstanceOf(com.zlecaf.escrow.web.ApiExceptions.NotFoundException.class);
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
}
