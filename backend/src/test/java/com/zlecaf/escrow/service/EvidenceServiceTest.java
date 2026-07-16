package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Observable proof of the deposit round-trip against a real Postgres (Flyway
 * applies V1 + V2, JSONB audit works). Boots a JPA slice, imports the real
 * services and a fake in-memory {@link EvidenceStorage}, and proves: an ACTIVE
 * evidence row and an EVIDENCE_ADDED audit row are written in the same
 * transaction; non-parties, closed windows, oversized/empty files and a
 * single-invalid batch write nothing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EvidenceService.class, AuditService.class, EvidenceContentValidator.class,
        TransactionAccess.class, EvidenceServiceTest.TestConfig.class})
@Testcontainers
class EvidenceServiceTest {

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

    /** In-memory {@link EvidenceStorage} fake: no MinIO needed to prove the DB round-trip. */
    static class InMemoryEvidenceStorage implements EvidenceStorage {
        final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String store(Long transactionId, byte[] content, String contentType) {
            String key = transactionId + "/" + UUID.randomUUID();
            objects.put(key, content.clone());
            return key;
        }

        @Override
        public InputStream load(String storageKey) {
            byte[] bytes = objects.get(storageKey);
            if (bytes == null) {
                throw new IllegalStateException("No object under key " + storageKey);
            }
            return new ByteArrayInputStream(bytes);
        }
    }

    @Autowired
    private TestEntityManager em;
    @Autowired
    private EvidenceService evidenceService;
    @Autowired
    private EvidenceFileRepository evidenceFiles;
    @Autowired
    private AuditLogRepository auditLogs;

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

    private User persistUser(String email, Role role) {
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("x");
        user.setRole(role);
        return em.persistAndFlush(user);
    }

    private EscrowTransaction persistTransaction(Long buyerId, Long sellerId, EscrowState state) {
        EscrowTransaction tx = new EscrowTransaction();
        tx.setBuyerId(buyerId);
        tx.setSellerId(sellerId);
        tx.setAmount(new BigDecimal("1000.00"));
        tx.setCurrency("USD");
        tx.setState(state);
        return em.persistAndFlush(tx);
    }

    @Test
    @DisplayName("Valid deposit persists an ACTIVE row and an EVIDENCE_ADDED audit row in the same transaction")
    void validDepositPersistsEvidenceAndAudit() {
        User buyer = persistUser("buyer@example.com", Role.BUYER);
        User seller = persistUser("seller@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Hostile path in the filename must be reduced to a bare basename.
        MockMultipartFile file =
                new MockMultipartFile("files", "../../etc/receipt.pdf", "application/pdf", pdfBytes());

        List<EvidenceFile> result = evidenceService.deposit(actor, tx.getId(),
                List.of(file), "here is my proof", "2026-07-15T10:15:30+02:00");

        assertThat(result).hasSize(1);

        List<EvidenceFile> rows = evidenceFiles.findAll();
        assertThat(rows).hasSize(1);
        EvidenceFile row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(row.getTransactionId()).isEqualTo(tx.getId());
        assertThat(row.getUploadedByUserId()).isEqualTo(buyer.getId());
        assertThat(row.getUploaderType()).isEqualTo(UploaderType.BUYER);
        assertThat(row.getPartnerCompanyId()).isNull();
        assertThat(row.getOriginalFilename()).isEqualTo("receipt.pdf");
        assertThat(row.getMimeType()).isEqualTo("application/pdf");
        assertThat(row.getSizeBytes()).isEqualTo((long) pdfBytes().length);
        assertThat(row.getStorageKey()).matches("^" + tx.getId() + "/[0-9a-f-]{36}$");
        assertThat(row.getComment()).isEqualTo("here is my proof");
        assertThat(row.getCreatedAt()).isNotNull();

        List<AuditLog> audit = auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId());
        assertThat(audit).hasSize(1);
        JsonNode payload = audit.get(0).getPayload();
        assertThat(payload.get("action").asText()).isEqualTo("EVIDENCE_ADDED");
        assertThat(payload.get("evidenceId").asLong()).isEqualTo(row.getId());
        assertThat(payload.get("sha256").asText()).isNotBlank();
        assertThat(payload.get("clientCapturedAt").asText()).isEqualTo("2026-07-15T10:15:30+02:00");
        assertThat(audit.get(0).getPreviousState()).isEqualTo("FUNDS_LOCKED");
        assertThat(audit.get(0).getNextState()).isEqualTo("FUNDS_LOCKED");
    }

    @Test
    @DisplayName("A non-party is rejected with ForbiddenException and writes nothing")
    void nonPartyIsForbidden() {
        User buyer = persistUser("buyer2@example.com", Role.BUYER);
        User seller = persistUser("seller2@example.com", Role.SELLER);
        User stranger = persistUser("stranger@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(pdf("proof.pdf")), null, null))
                .isInstanceOf(ForbiddenException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("Depositing outside the state window is a ConflictException and writes nothing")
    void closedStateWindowConflicts() {
        User buyer = persistUser("buyer3@example.com", Role.BUYER);
        User seller = persistUser("seller3@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.RELEASED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(pdf("proof.pdf")), null, null))
                .isInstanceOf(ConflictException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("A batch with one invalid file writes nothing (all-or-nothing)")
    void batchWithOneInvalidWritesNothing() {
        User buyer = persistUser("buyer4@example.com", Role.BUYER);
        User seller = persistUser("seller4@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.SHIPPED);
        AuthPrincipal actor = new AuthPrincipal(seller.getId(), seller.getEmail(), Role.SELLER);

        MockMultipartFile valid = new MockMultipartFile("files", "photo.jpg", "image/jpeg", jpegBytes());
        MockMultipartFile empty = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(valid, empty), null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("An oversized file is rejected with BadRequestException")
    void oversizedFileRejected() {
        User buyer = persistUser("buyer5@example.com", Role.BUYER);
        User seller = persistUser("seller5@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        byte[] tooBig = new byte[(int) (EvidenceService.MAX_FILE_SIZE + 1)];
        MockMultipartFile file = new MockMultipartFile("files", "big.pdf", "application/pdf", tooBig);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(file), null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(evidenceFiles.count()).isZero();
    }

    @Test
    @DisplayName("A multi-file batch persists every file with its own audit entry")
    void multiFileDepositPersistsAll() {
        User buyer = persistUser("buyer7@example.com", Role.BUYER);
        User seller = persistUser("seller7@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.SHIPPED);
        AuthPrincipal actor = new AuthPrincipal(seller.getId(), seller.getEmail(), Role.SELLER);

        MockMultipartFile jpg = new MockMultipartFile("files", "photo.jpg", "image/jpeg", jpegBytes());
        MockMultipartFile pdf = new MockMultipartFile("files", "receipt.pdf", "application/pdf", pdfBytes());

        List<EvidenceFile> result = evidenceService.deposit(actor, tx.getId(),
                List.of(jpg, pdf), null, null);

        assertThat(result).hasSize(2);
        assertThat(evidenceFiles.findAll())
                .extracting(EvidenceFile::getUploaderType)
                .containsOnly(UploaderType.SELLER);
        assertThat(evidenceFiles.findAll())
                .extracting(EvidenceFile::getMimeType)
                .containsExactlyInAnyOrder("image/jpeg", "application/pdf");
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).hasSize(2);
    }

    @Test
    @DisplayName("A malformed clientCapturedAt is rejected and writes nothing")
    void invalidClientCapturedAtRejected() {
        User buyer = persistUser("buyer8@example.com", Role.BUYER);
        User seller = persistUser("seller8@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(pdf("proof.pdf")), null, "not-a-timestamp"))
                .isInstanceOf(BadRequestException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("An over-length filename is truncated to the column bound, not a 500")
    void overlongFilenameIsTruncated() {
        User buyer = persistUser("buyer9@example.com", Role.BUYER);
        User seller = persistUser("seller9@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        String longName = "a".repeat(300) + ".pdf";
        MockMultipartFile file = new MockMultipartFile("files", longName, "application/pdf", pdfBytes());

        List<EvidenceFile> result = evidenceService.deposit(actor, tx.getId(),
                List.of(file), null, null);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOriginalFilename()).hasSize(255).endsWith(".pdf");
    }

    @Test
    @DisplayName("An empty file is rejected with BadRequestException")
    void emptyFileRejected() {
        User buyer = persistUser("buyer6@example.com", Role.BUYER);
        User seller = persistUser("seller6@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        MockMultipartFile file = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(file), null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(evidenceFiles.count()).isZero();
    }

    // --- Story 1.3: chronological list ---

    /**
     * Persists one evidence row directly (bypassing the deposit path) so tests can
     * pin created_at and status explicitly. @PrePersist only sets created_at when
     * null, so an explicit value survives. Storage keys are unique (uq constraint).
     */
    private EvidenceFile persistEvidence(Long txId, Long uploaderId, UploaderType type,
                                         Instant createdAt, EvidenceStatus status,
                                         Instant withdrawnAt, Long withdrawnByUserId) {
        EvidenceFile e = new EvidenceFile();
        e.setTransactionId(txId);
        e.setUploadedByUserId(uploaderId);
        e.setUploaderType(type);
        e.setPartnerCompanyId(null);
        e.setOriginalFilename("evidence.pdf");
        e.setMimeType("application/pdf");
        e.setSizeBytes(123L);
        e.setStorageKey(txId + "/" + UUID.randomUUID());
        e.setComment("proof");
        e.setStatus(status);
        e.setCreatedAt(createdAt);
        e.setWithdrawnAt(withdrawnAt);
        e.setWithdrawnByUserId(withdrawnByUserId);
        return em.persistAndFlush(e);
    }

    private EvidenceFile persistActiveEvidence(Long txId, Long uploaderId, UploaderType type, Instant createdAt) {
        return persistEvidence(txId, uploaderId, type, createdAt, EvidenceStatus.ACTIVE, null, null);
    }

    @Test
    @DisplayName("list returns every piece sorted by created_at ascending, regardless of insertion order")
    void listSortsByCreatedAtAscending() {
        User buyer = persistUser("buyerL1@example.com", Role.BUYER);
        User seller = persistUser("sellerL1@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        // Persist out of chronological order to prove the query, not insertion, sorts.
        EvidenceFile middle = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0.plusSeconds(60));
        EvidenceFile oldest = persistActiveEvidence(tx.getId(), seller.getId(), UploaderType.SELLER, t0);
        EvidenceFile newest = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0.plusSeconds(120));

        List<EvidenceDto> result = evidenceService.list(actor, tx.getId());

        assertThat(result).extracting(EvidenceDto::id)
                .containsExactly(oldest.getId(), middle.getId(), newest.getId());
        assertThat(result).extracting(EvidenceDto::createdAt)
                .containsExactly(t0, t0.plusSeconds(60), t0.plusSeconds(120));
    }

    @Test
    @DisplayName("list breaks created_at ties deterministically by id ascending")
    void listTieBreaksByIdWhenCreatedAtEqual() {
        User buyer = persistUser("buyerL7@example.com", Role.BUYER);
        User seller = persistUser("sellerL7@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Same created_at for both rows (as a batch deposit stamps within one instant):
        // order must fall back to id ascending, i.e. insertion order.
        Instant tie = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile first = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, tie);
        EvidenceFile second = persistActiveEvidence(tx.getId(), seller.getId(), UploaderType.SELLER, tie);

        List<EvidenceDto> result = evidenceService.list(actor, tx.getId());

        assertThat(result).extracting(EvidenceDto::id)
                .containsExactly(first.getId(), second.getId());
    }

    @Test
    @DisplayName("list surfaces pieces from every uploader (BUYER/SELLER/ADMIN) to a buyer caller — contradictory visibility")
    void listReturnsAllUploaderTypes() {
        User buyer = persistUser("buyerL2@example.com", Role.BUYER);
        User seller = persistUser("sellerL2@example.com", Role.SELLER);
        User admin = persistUser("adminL2@example.com", Role.ADMIN);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0);
        persistActiveEvidence(tx.getId(), seller.getId(), UploaderType.SELLER, t0.plusSeconds(1));
        persistActiveEvidence(tx.getId(), admin.getId(), UploaderType.ADMIN, t0.plusSeconds(2));

        List<EvidenceDto> result = evidenceService.list(actor, tx.getId());

        assertThat(result).hasSize(3)
                .extracting(EvidenceDto::uploaderType)
                .containsExactly(UploaderType.BUYER, UploaderType.SELLER, UploaderType.ADMIN);
    }

    @Test
    @DisplayName("list keeps a WITHDRAWN piece visible alongside an ACTIVE one, with its status")
    void listIncludesWithdrawnPieces() {
        User buyer = persistUser("buyerL3@example.com", Role.BUYER);
        User seller = persistUser("sellerL3@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile active = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0);
        // WITHDRAWN row must carry withdrawn_at + withdrawn_by_user_id (V3 CHECK).
        EvidenceFile withdrawn = persistEvidence(tx.getId(), seller.getId(), UploaderType.SELLER,
                t0.plusSeconds(60), EvidenceStatus.WITHDRAWN, t0.plusSeconds(90), seller.getId());

        List<EvidenceDto> result = evidenceService.list(actor, tx.getId());

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(active.getId());
        assertThat(result.get(0).status()).isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(result.get(1).id()).isEqualTo(withdrawn.getId());
        assertThat(result.get(1).status()).isEqualTo(EvidenceStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("list returns an empty list for a transaction with no evidence")
    void listReturnsEmptyWhenNoEvidence() {
        User buyer = persistUser("buyerL4@example.com", Role.BUYER);
        User seller = persistUser("sellerL4@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(seller.getId(), seller.getEmail(), Role.SELLER);

        assertThat(evidenceService.list(actor, tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("list rejects a non-party with ForbiddenException")
    void listNonPartyIsForbidden() {
        User buyer = persistUser("buyerL5@example.com", Role.BUYER);
        User seller = persistUser("sellerL5@example.com", Role.SELLER);
        User stranger = persistUser("strangerL5@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.list(actor, tx.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("list on an unknown transaction throws NotFoundException")
    void listUnknownTransactionNotFound() {
        User buyer = persistUser("buyerL6@example.com", Role.BUYER);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.list(actor, 999_999L))
                .isInstanceOf(NotFoundException.class);
    }
}
