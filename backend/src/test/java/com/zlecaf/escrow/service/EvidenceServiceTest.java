package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.ErrorCode;
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
import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import com.zlecaf.escrow.service.scan.MalwareScanUnavailableException;
import com.zlecaf.escrow.service.scan.MalwareScanGateway;
import com.zlecaf.escrow.service.scan.ScanVerdict;
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
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
// SecretCipher + EncryptedStringConverter : les colonnes de secrets sont converties
// depuis la Story 1.7, Hibernate les réclame à la construction du métamodèle.
@Import({EvidenceService.class, AuditService.class, EvidenceContentValidator.class,
        TransactionAccess.class, SecretCipher.class, EncryptedStringConverter.class,
        EvidenceServiceTest.TestConfig.class})
class EvidenceServiceTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, EvidenceServiceTest.class);
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
        MalwareScanGateway malwareScanner() {
            return new ProgrammableMalwareScanGateway();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    /**
     * Faux {@link MalwareScanGateway} programmable (Story 1.8). « Sain » par défaut pour
     * que tout ce que cette classe prouvait déjà reste prouvé ; les cas de rejet et
     * d'indisponibilité sont armés test par test.
     *
     * <p>Il compte ses appels : c'est le seul moyen de prouver l'ORDRE des gardes —
     * qu'un fichier de 12 Mo ou un GIF déguisé en {@code .pdf} est refusé <em>sans
     * jamais</em> atteindre le moteur. Un test qui vérifierait seulement le code
     * d'erreur passerait tout aussi bien si le scan tournait d'abord.
     */
    static class ProgrammableMalwareScanGateway implements MalwareScanGateway {
        /** Nombre d'octets exact des contenus à déclarer infectés (identité par taille + hash suffirait ; la taille suffit ici). */
        volatile java.util.function.Predicate<byte[]> infectedWhen = content -> false;
        volatile boolean unavailable = false;
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ScanVerdict scan(byte[] content) {
            calls.incrementAndGet();
            if (unavailable) {
                throw new MalwareScanUnavailableException("panne simulée");
            }
            return infectedWhen.test(content) ? ScanVerdict.infected("Test.Simulated-Signature") : ScanVerdict.clean();
        }

        void reset() {
            infectedWhen = content -> false;
            unavailable = false;
            calls.set(0);
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
                // Mirror the real port contract so the storage-miss → 404
                // translation stays exercised by the honest round-trip harness.
                throw new EvidenceNotFoundException(storageKey, null);
            }
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void delete(String storageKey) {
            // Idempotent, no-op on a missing key — mirrors the real port contract.
            objects.remove(storageKey);
        }

        /** Preloads a binary under an explicit key so a persisted row can be downloaded. */
        void seed(String storageKey, byte[] content) {
            objects.put(storageKey, content.clone());
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
    @Autowired
    private EvidenceStorage storage;
    @Autowired
    private MalwareScanGateway scanner;

    /**
     * Le faux scanner et le faux stockage sont des singletons de contexte, partagés
     * par toutes les méthodes : sans remise à zéro, un test qui arme le scanner — ou
     * qui laisse un objet dans la carte — contaminerait les suivants. La transaction
     * de test, elle, est rejouée à zéro par Spring ; ces deux doubles ne le sont pas.
     */
    @org.junit.jupiter.api.BeforeEach
    void resetDoubles() {
        ((ProgrammableMalwareScanGateway) scanner).reset();
        ((InMemoryEvidenceStorage) storage).objects.clear();
    }

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
    @DisplayName("A non-party gets the SAME opaque 404 as an unknown id and writes nothing")
    void nonPartyIsNotFound() {
        User buyer = persistUser("buyer2@example.com", Role.BUYER);
        User seller = persistUser("seller2@example.com", Role.SELLER);
        User stranger = persistUser("stranger@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        // Story 1.10 : 404 et non 403. Le stranger ne doit pas pouvoir distinguer
        // « cette transaction existe mais n'est pas la tienne » de « cet identifiant
        // n'existe pas » — les deux passent par ApiExceptions.transactionNotFound().
        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(pdf("proof.pdf")), null, null))
                .isInstanceOf(NotFoundException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = EscrowState.class, names = {"INITIATED", "RELEASED", "REFUNDED"})
    @DisplayName("Depositing outside the state window is a ConflictException and writes nothing")
    void closedStateWindowConflicts(EscrowState closedState) {
        String suffix = closedState.name().toLowerCase();
        User buyer = persistUser("buyer3-" + suffix + "@example.com", Role.BUYER);
        User seller = persistUser("seller3-" + suffix + "@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), closedState);
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
    @DisplayName("list rejects a non-party with the SAME opaque 404 as an unknown id")
    void listNonPartyIsNotFound() {
        User buyer = persistUser("buyerL5@example.com", Role.BUYER);
        User seller = persistUser("sellerL5@example.com", Role.SELLER);
        User stranger = persistUser("strangerL5@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.list(actor, tx.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("list on an unknown transaction throws NotFoundException")
    void listUnknownTransactionNotFound() {
        User buyer = persistUser("buyerL6@example.com", Role.BUYER);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.list(actor, 999_999L))
                .isInstanceOf(NotFoundException.class);
    }

    // --- Story 1.4: secure download ---

    private byte[] drain(InputStream in) {
        try (InputStream s = in) {
            return s.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("download returns the stored binary identical byte-for-byte, with filename/mime/size")
    void downloadRoundTripsBinaryIdentically() {
        User buyer = persistUser("buyerD1@example.com", Role.BUYER);
        User seller = persistUser("sellerD1@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Deposit, then download: an honest round-trip through the real storage port.
        MockMultipartFile file = new MockMultipartFile("files", "receipt.pdf", "application/pdf", pdfBytes());
        EvidenceFile deposited = evidenceService.deposit(actor, tx.getId(), List.of(file), null, null).get(0);

        EvidenceDownload d = evidenceService.download(actor, tx.getId(), deposited.getId());

        assertThat(d.filename()).isEqualTo("receipt.pdf");
        assertThat(d.contentType()).isEqualTo("application/pdf");
        assertThat(d.sizeBytes()).isEqualTo((long) pdfBytes().length);
        assertThat(drain(d.content())).isEqualTo(pdfBytes());
    }

    @Test
    @DisplayName("download serves a WITHDRAWN piece normally (restitution is not masking)")
    void downloadServesWithdrawnPiece() {
        User buyer = persistUser("buyerD2@example.com", Role.BUYER);
        User seller = persistUser("sellerD2@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile withdrawn = persistEvidence(tx.getId(), seller.getId(), UploaderType.SELLER,
                t0, EvidenceStatus.WITHDRAWN, t0.plusSeconds(30), seller.getId());
        // The row references a storage key; preload its binary so the port can serve it.
        ((InMemoryEvidenceStorage) storage).seed(withdrawn.getStorageKey(), pdfBytes());

        EvidenceDownload d = evidenceService.download(actor, tx.getId(), withdrawn.getId());

        assertThat(drain(d.content())).isEqualTo(pdfBytes());
    }

    @Test
    @DisplayName("download of a piece belonging to another transaction is a 404 (anti-IDOR)")
    void downloadForeignEvidenceIsNotFound() {
        User buyer = persistUser("buyerD3@example.com", Role.BUYER);
        User seller = persistUser("sellerD3@example.com", Role.SELLER);
        EscrowTransaction txA = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        EscrowTransaction txB = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Piece lives on txA; the caller is a party to both but asks via txB's id.
        EvidenceFile onA = persistActiveEvidence(txA.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.download(actor, txB.getId(), onA.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("download of an unknown piece is a 404")
    void downloadUnknownEvidenceIsNotFound() {
        User buyer = persistUser("buyerD4@example.com", Role.BUYER);
        User seller = persistUser("sellerD4@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.download(actor, tx.getId(), 999_999L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("download by a non-party is the opaque 404, before any piece lookup")
    void downloadNonPartyIsNotFound() {
        User buyer = persistUser("buyerD5@example.com", Role.BUYER);
        User seller = persistUser("sellerD5@example.com", Role.SELLER);
        User stranger = persistUser("strangerD5@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        EvidenceFile piece = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.download(actor, tx.getId(), piece.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("download on an unknown transaction is a 404")
    void downloadUnknownTransactionIsNotFound() {
        User buyer = persistUser("buyerD6@example.com", Role.BUYER);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        assertThatThrownBy(() -> evidenceService.download(actor, 999_999L, 1L))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("download translates a missing storage object to a 404")
    void downloadMissingBinaryIsNotFound() {
        User buyer = persistUser("buyerD7@example.com", Role.BUYER);
        User seller = persistUser("sellerD7@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Metadata row persisted but no binary was ever seeded under its storage key.
        EvidenceFile orphan = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.download(actor, tx.getId(), orphan.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    // --- Story 2.3: logical withdrawal with a dispute evidence floor ---

    @Test
    @DisplayName("withdraw flips the actor's own ACTIVE piece to WITHDRAWN and writes an EVIDENCE_WITHDRAWN audit row")
    void withdrawFlipsStatusStampsMetadataAndAudits() {
        User buyer = persistUser("buyerW1@example.com", Role.BUYER);
        User seller = persistUser("sellerW1@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        EvidenceFile piece = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        EvidenceDto result = evidenceService.withdraw(actor, tx.getId(), piece.getId());

        assertThat(result.status()).isEqualTo(EvidenceStatus.WITHDRAWN);

        EvidenceFile row = evidenceFiles.findById(piece.getId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.WITHDRAWN);
        assertThat(row.getWithdrawnAt()).isNotNull();
        assertThat(row.getWithdrawnByUserId()).isEqualTo(buyer.getId());

        List<AuditLog> audit = auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId());
        assertThat(audit).hasSize(1);
        JsonNode payload = audit.get(0).getPayload();
        assertThat(payload.get("action").asText()).isEqualTo("EVIDENCE_WITHDRAWN");
        assertThat(payload.get("evidenceId").asLong()).isEqualTo(piece.getId());
        assertThat(payload.get("actorRole").asText()).isEqualTo("BUYER");
        assertThat(audit.get(0).getPreviousState()).isEqualTo("FUNDS_LOCKED");
        assertThat(audit.get(0).getNextState()).isEqualTo("FUNDS_LOCKED");
    }

    @Test
    @DisplayName("withdraw succeeds in DISPUTED while other ACTIVE pieces remain (floor not breached)")
    void withdrawInDisputeSucceedsWhenOtherActiveRemain() {
        User buyer = persistUser("buyerW2@example.com", Role.BUYER);
        User seller = persistUser("sellerW2@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile mine = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0);
        persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0.plusSeconds(60));

        EvidenceDto result = evidenceService.withdraw(actor, tx.getId(), mine.getId());

        assertThat(result.status()).isEqualTo(EvidenceStatus.WITHDRAWN);
        assertThat(evidenceFiles.countByTransactionIdAndStatus(tx.getId(), EvidenceStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("withdraw of a third party's piece is a 403 and mutates nothing")
    void withdrawThirdPartyPieceIsForbidden() {
        User buyer = persistUser("buyerW3@example.com", Role.BUYER);
        User seller = persistUser("sellerW3@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Piece deposited by the seller; the buyer must not be able to withdraw it.
        EvidenceFile foreign = persistActiveEvidence(tx.getId(), seller.getId(), UploaderType.SELLER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.withdraw(actor, tx.getId(), foreign.getId()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(evidenceFiles.findById(foreign.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("Story 1.10 : le 403 « piece a soi » ne revele rien — l'appelant VOIT deja cette piece par list()")
    void withdrawForeignPieceStays403BecauseTheCallerAlreadySeesIt() {
        // L'exception assumee de la Story 1.10, rendue PROUVEE plutot que declaree.
        //
        // L'invariant n'est pas « tout refus devient 404 », c'est « ne jamais reveler
        // ce que l'appelant n'a pas le droit de savoir ». Le test jumeau ci-dessus
        // prouve la premiere moitie (le refus reste un 403 honnete) ; celui-ci prouve
        // la seconde, la seule qui rend le 403 defendable : la piece refusee est deja
        // listee a cet appelant par GET /{id}/evidence. Repondre 404 sur une piece
        // qu'il vient de lire ne cacherait rien et degraderait un message legitime.
        //
        // Sans cette seconde assertion, l'exception ne se distinguerait pas d'un oubli
        // — et le jour ou la visibilite de list() se restreindrait aux pieces propres,
        // le 403 deviendrait un veritable oracle sans qu'aucun test ne bronche.
        User buyer = persistUser("buyerW3b@example.com", Role.BUYER);
        User seller = persistUser("sellerW3b@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        EvidenceFile foreign = persistActiveEvidence(tx.getId(), seller.getId(), UploaderType.SELLER,
                Instant.parse("2026-07-15T10:00:00Z"));

        // (1) Le refus reste un 403 code FORBIDDEN, et non le 404 opaque.
        assertThatThrownBy(() -> evidenceService.withdraw(actor, tx.getId(), foreign.getId()))
                .isInstanceOf(ForbiddenException.class)
                .extracting(e -> ((ForbiddenException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN);

        // (2) ... parce que la MEME piece lui est deja visible. C'est ce qui fait que
        // le refus honnete n'apprend rien a personne.
        assertThat(evidenceService.list(actor, tx.getId()))
                .extracting(EvidenceDto::id)
                .contains(foreign.getId());
    }

    @Test
    @DisplayName("withdraw of the last ACTIVE piece in DISPUTED hits the evidence floor (409) and mutates nothing")
    void withdrawLastActiveInDisputeHitsFloor() {
        User buyer = persistUser("buyerW4@example.com", Role.BUYER);
        User seller = persistUser("sellerW4@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Exactly one ACTIVE piece, owned by the actor: withdrawing it would leave zero.
        EvidenceFile onlyActive = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.withdraw(actor, tx.getId(), onlyActive.getId()))
                .isInstanceOf(ConflictException.class);

        assertThat(evidenceFiles.findById(onlyActive.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = EscrowState.class, names = {"RELEASED", "REFUNDED"})
    @DisplayName("withdraw in a terminal state is a 409 (Story 2.2 lock) and mutates nothing")
    void withdrawInTerminalStateConflicts(EscrowState terminalState) {
        String suffix = terminalState.name().toLowerCase();
        User buyer = persistUser("buyerW5-" + suffix + "@example.com", Role.BUYER);
        User seller = persistUser("sellerW5-" + suffix + "@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), terminalState);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        EvidenceFile piece = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.withdraw(actor, tx.getId(), piece.getId()))
                .isInstanceOf(ConflictException.class);

        assertThat(evidenceFiles.findById(piece.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("withdraw of an already-WITHDRAWN piece is a 409 and writes no new audit")
    void withdrawAlreadyWithdrawnConflicts() {
        User buyer = persistUser("buyerW6@example.com", Role.BUYER);
        User seller = persistUser("sellerW6@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile withdrawn = persistEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                t0, EvidenceStatus.WITHDRAWN, t0.plusSeconds(30), buyer.getId());

        assertThatThrownBy(() -> evidenceService.withdraw(actor, tx.getId(), withdrawn.getId()))
                .isInstanceOf(ConflictException.class);

        assertThat(evidenceFiles.findById(withdrawn.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.WITHDRAWN);
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("withdraw of a piece belonging to another transaction is a 404 (anti-IDOR)")
    void withdrawForeignPieceIsNotFound() {
        User buyer = persistUser("buyerW7@example.com", Role.BUYER);
        User seller = persistUser("sellerW7@example.com", Role.SELLER);
        EscrowTransaction txA = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        EscrowTransaction txB = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Piece lives on txA; the caller is a party to both but asks via txB's id.
        EvidenceFile onA = persistActiveEvidence(txA.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.withdraw(actor, txB.getId(), onA.getId()))
                .isInstanceOf(NotFoundException.class);

        assertThat(evidenceFiles.findById(onA.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.ACTIVE);
    }

    @Test
    @DisplayName("withdraw of my last own piece in DISPUTED succeeds while a counterparty piece holds the floor (FR-6 counts all parties)")
    void withdrawInDisputeSucceedsWhenCounterpartyPieceRemains() {
        User buyer = persistUser("buyerW8@example.com", Role.BUYER);
        User seller = persistUser("sellerW8@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        // My only piece; the seller holds the other ACTIVE piece. Withdrawing mine
        // leaves me at zero but the dispute at one — the floor counts ALL parties,
        // so it must NOT be scoped to the actor's own pieces.
        EvidenceFile mine = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0);
        persistActiveEvidence(tx.getId(), seller.getId(), UploaderType.SELLER, t0.plusSeconds(60));

        EvidenceDto result = evidenceService.withdraw(actor, tx.getId(), mine.getId());

        assertThat(result.status()).isEqualTo(EvidenceStatus.WITHDRAWN);
        assertThat(evidenceFiles.countByTransactionIdAndStatus(tx.getId(), EvidenceStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    @DisplayName("withdraw by a non-party (neither buyer nor seller) is the opaque 404 and mutates nothing")
    void withdrawByNonPartyIsNotFound() {
        User buyer = persistUser("buyerW9@example.com", Role.BUYER);
        User seller = persistUser("sellerW9@example.com", Role.SELLER);
        User stranger = persistUser("strangerW9@example.com", Role.BUYER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        // A user who is party to no side of this transaction: resolveRole must answer
        // the opaque 404 before the own-piece guard is ever reached (Story 1.10) —
        // which is exactly what keeps that guard's honest 403 from leaking anything.
        AuthPrincipal actor = new AuthPrincipal(stranger.getId(), stranger.getEmail(), Role.BUYER);

        EvidenceFile piece = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER,
                Instant.parse("2026-07-15T10:00:00Z"));

        assertThatThrownBy(() -> evidenceService.withdraw(actor, tx.getId(), piece.getId()))
                .isInstanceOf(NotFoundException.class);

        assertThat(evidenceFiles.findById(piece.getId()).orElseThrow().getStatus())
                .isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    // --- Story 5.1: contract hardening (list cap, file cap, download audit, DTO attribution) ---

    @Test
    @DisplayName("list is hard-capped at MAX_LIST_RESULTS rows even when more evidence exists")
    void listIsCappedAtMaxResults() {
        User buyer = persistUser("buyerCap@example.com", Role.BUYER);
        User seller = persistUser("sellerCap@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        int over = PlatformLimits.MAX_LIST_RESULTS + 1;
        for (int i = 0; i < over; i++) {
            EvidenceFile e = new EvidenceFile();
            e.setTransactionId(tx.getId());
            e.setUploadedByUserId(buyer.getId());
            e.setUploaderType(UploaderType.BUYER);
            e.setPartnerCompanyId(null);
            e.setOriginalFilename("evidence.pdf");
            e.setMimeType("application/pdf");
            e.setSizeBytes(123L);
            e.setStorageKey(tx.getId() + "/" + UUID.randomUUID());
            e.setComment("proof");
            e.setStatus(EvidenceStatus.ACTIVE);
            e.setCreatedAt(t0.plusSeconds(i));
            em.persist(e);
        }
        em.flush();

        assertThat(evidenceService.list(actor, tx.getId())).hasSize(PlatformLimits.MAX_LIST_RESULTS);
    }

    @Test
    @DisplayName("A deposit of more than MAX_FILES_PER_DEPOSIT files is rejected 400 (before buffering) and writes nothing")
    void depositOverFileCapRejected() {
        User buyer = persistUser("buyerFcap@example.com", Role.BUYER);
        User seller = persistUser("sellerFcap@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i <= PlatformLimits.MAX_FILES_PER_DEPOSIT; i++) {
            files.add(pdf("f" + i + ".pdf"));
        }

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(), files, null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("A deposit of exactly MAX_FILES_PER_DEPOSIT files succeeds")
    void depositAtFileCapSucceeds() {
        User buyer = persistUser("buyerFcapOk@example.com", Role.BUYER);
        User seller = persistUser("sellerFcapOk@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        List<MultipartFile> files = new ArrayList<>();
        for (int i = 0; i < PlatformLimits.MAX_FILES_PER_DEPOSIT; i++) {
            files.add(pdf("f" + i + ".pdf"));
        }

        List<EvidenceFile> result = evidenceService.deposit(actor, tx.getId(), files, null, null);

        assertThat(result).hasSize(PlatformLimits.MAX_FILES_PER_DEPOSIT);
        assertThat(evidenceFiles.count()).isEqualTo(PlatformLimits.MAX_FILES_PER_DEPOSIT);
    }

    @Test
    @DisplayName("download writes an EVIDENCE_DOWNLOADED audit row (actor, role, evidence id)")
    void downloadWritesDownloadAudit() {
        User buyer = persistUser("buyerDaudit@example.com", Role.BUYER);
        User seller = persistUser("sellerDaudit@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        MockMultipartFile file = new MockMultipartFile("files", "receipt.pdf", "application/pdf", pdfBytes());
        EvidenceFile deposited = evidenceService.deposit(actor, tx.getId(), List.of(file), null, null).get(0);

        EvidenceDownload d = evidenceService.download(actor, tx.getId(), deposited.getId());
        drain(d.content()); // consume the stream as the web layer would

        List<AuditLog> audit = auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId());
        AuditLog download = audit.stream()
                .filter(a -> "EVIDENCE_DOWNLOADED".equals(a.getPayload().path("action").asText()))
                .findFirst().orElseThrow();
        assertThat(download.getActionBy()).isEqualTo(buyer.getId());
        assertThat(download.getPayload().get("actorRole").asText()).isEqualTo("BUYER");
        assertThat(download.getPayload().get("evidenceId").asLong()).isEqualTo(deposited.getId());
        assertThat(download.getPreviousState()).isEqualTo("FUNDS_LOCKED");
        assertThat(download.getNextState()).isEqualTo("FUNDS_LOCKED");
    }

    // --- Story 5.2: recent-tail selection + nullable size_bytes on download ---

    @Test
    @DisplayName("the bounded DESC overload keeps the MOST RECENT rows (recent tail), not the oldest")
    void descOverloadKeepsMostRecentRows() {
        User buyer = persistUser("buyerRT@example.com", Role.BUYER);
        User seller = persistUser("sellerRT@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile oldest = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0);
        EvidenceFile middle = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0.plusSeconds(60));
        EvidenceFile newest = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0.plusSeconds(120));

        // LIMIT 2 over 3 rows: the DESC overload must return the two NEWEST, newest
        // first — proving the limit keeps the recent tail (an ASC limit would have
        // returned oldest+middle and silently dropped `newest`).
        List<EvidenceFile> recent = evidenceFiles.findByTransactionIdOrderByCreatedAtDescIdDesc(
                tx.getId(), PageRequest.of(0, 2));

        assertThat(recent).extracting(EvidenceFile::getId)
                .containsExactly(newest.getId(), middle.getId());
        assertThat(recent).doesNotContain(oldest);
    }

    @Test
    @DisplayName("download of a piece with a NULL size_bytes streams the binary without NPE and reports a null size")
    void downloadWithNullSizeBytesDoesNotThrow() {
        User buyer = persistUser("buyerNull@example.com", Role.BUYER);
        User seller = persistUser("sellerNull@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Persist a row whose size_bytes is NULL (nullable column), then seed its
        // binary so the storage port can serve it. The unboxing bug would NPE here.
        EvidenceFile e = new EvidenceFile();
        e.setTransactionId(tx.getId());
        e.setUploadedByUserId(buyer.getId());
        e.setUploaderType(UploaderType.BUYER);
        e.setPartnerCompanyId(null);
        e.setOriginalFilename("evidence.pdf");
        e.setMimeType("application/pdf");
        e.setSizeBytes(null);
        e.setStorageKey(tx.getId() + "/" + UUID.randomUUID());
        e.setComment("proof");
        e.setStatus(EvidenceStatus.ACTIVE);
        e.setCreatedAt(Instant.parse("2026-07-15T10:00:00Z"));
        EvidenceFile persisted = em.persistAndFlush(e);
        ((InMemoryEvidenceStorage) storage).seed(persisted.getStorageKey(), pdfBytes());

        EvidenceDownload d = evidenceService.download(actor, tx.getId(), persisted.getId());

        assertThat(d.sizeBytes()).isNull();
        assertThat(drain(d.content())).isEqualTo(pdfBytes());
    }

    @Test
    @DisplayName("EvidenceDto exposes withdrawnAt/withdrawnByUserId after a withdraw; both null for an ACTIVE piece")
    void dtoExposesWithdrawalAttribution() {
        User buyer = persistUser("buyerDto@example.com", Role.BUYER);
        User seller = persistUser("sellerDto@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        Instant t0 = Instant.parse("2026-07-15T10:00:00Z");
        EvidenceFile toWithdraw = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0);
        EvidenceFile staysActive = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER, t0.plusSeconds(60));

        EvidenceDto withdrawnDto = evidenceService.withdraw(actor, tx.getId(), toWithdraw.getId());
        assertThat(withdrawnDto.withdrawnAt()).isNotNull();
        assertThat(withdrawnDto.withdrawnByUserId()).isEqualTo(buyer.getId());

        EvidenceDto activeDto = evidenceService.list(actor, tx.getId()).stream()
                .filter(dto -> dto.id().equals(staysActive.getId()))
                .findFirst().orElseThrow();
        assertThat(activeDto.withdrawnAt()).isNull();
        assertThat(activeDto.withdrawnByUserId()).isNull();
    }

    // --- Story 1.8: malware scan at ingestion ---

    /** PDF valide portant un marqueur, pour que le faux scanner puisse cibler UN fichier d'un lot. */
    private static byte[] markedPdfBytes(String marker) {
        return ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\n% " + marker + "\ntrailer<</Root 1 0 R>>\n%%EOF")
                .getBytes(StandardCharsets.US_ASCII);
    }

    private static MockMultipartFile markedPdf(String filename, String marker) {
        return new MockMultipartFile("files", filename, "application/pdf", markedPdfBytes(marker));
    }

    private ProgrammableMalwareScanGateway fakeScanner() {
        return (ProgrammableMalwareScanGateway) scanner;
    }

    private int storedObjectCount() {
        return ((InMemoryEvidenceStorage) storage).objects.size();
    }

    @Test
    @DisplayName("A clean deposit calls the scanner exactly once per file — the scan is really wired in")
    void cleanDepositScansEveryFileOnce() {
        User buyer = persistUser("buyerAV0@example.com", Role.BUYER);
        User seller = persistUser("sellerAV0@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Deux fichiers sains : le comportement observable est INCHANGÉ (mêmes lignes,
        // mêmes audits) et le scan n'ajoute qu'un aller-retour par fichier, sur les
        // octets déjà en mémoire — aucune relecture.
        evidenceService.deposit(actor, tx.getId(),
                List.of(pdf("a.pdf"), markedPdf("b.pdf", "SECOND")), null, null);

        assertThat(fakeScanner().calls.get()).isEqualTo(2);
        assertThat(evidenceFiles.count()).isEqualTo(2);
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId()))
                .extracting(a -> a.getPayload().path("action").asText())
                .containsOnly("EVIDENCE_ADDED");
    }

    // Les cas à VERDICT POSITIF (fichier infecté, lot dont un fichier est infecté,
    // contenu du message de rejet) ne peuvent pas vivre ici : ils auditent, et
    // `recordEvidenceRejectedByScan` est en REQUIRES_NEW. Cette classe est un slice
    // dont la transaction de test ne commite JAMAIS, si bien que la seconde
    // transaction ne voit pas la ligne `escrow_transactions` du test et l'INSERT
    // d'audit échoue sur `audit_logs_transaction_id_fkey` — un artefact du harnais,
    // pas du code. Ils sont donc dans EvidenceMalwareAuditIntegrationTest
    // (Propagation.NOT_SUPPORTED), où les assertions portent en plus sur la vérité
    // COMMITÉE : c'est le seul endroit où « l'audit survit au rollback » veut dire
    // quelque chose.

    @Test
    @DisplayName("A scanner that cannot answer fails the deposit (never a silent store-without-scan)")
    void scannerUnavailableFailsTheDeposit() {
        User buyer = persistUser("buyerAV4@example.com", Role.BUYER);
        User seller = persistUser("sellerAV4@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        fakeScanner().unavailable = true;

        // Le service laisse remonter l'exception du port ; c'est GlobalExceptionHandler
        // qui la traduit en 502 SCAN_UNAVAILABLE (prouvé par EvidenceScanErrorMappingTest).
        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(),
                List.of(pdf("proof.pdf")), null, null))
                .isInstanceOf(MalwareScanUnavailableException.class);

        assertThat(evidenceFiles.count()).isZero();
        assertThat(storedObjectCount()).isZero();
        // Aucun audit : une panne de scanner n'est PAS un événement de sécurité à
        // consigner dans une table append-only à rétention >= 5 ans.
        assertThat(auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId())).isEmpty();
    }

    @Test
    @DisplayName("An oversized file is refused by the cheap guard WITHOUT ever reaching the scanner")
    void oversizedFileNeverReachesTheScanner() {
        User buyer = persistUser("buyerAV5@example.com", Role.BUYER);
        User seller = persistUser("sellerAV5@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        byte[] tooBig = new byte[(int) (EvidenceService.MAX_FILE_SIZE + 1)];
        MockMultipartFile file = new MockMultipartFile("files", "big.pdf", "application/pdf", tooBig);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(), List.of(file), null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(fakeScanner().calls.get()).isZero();
    }

    @Test
    @DisplayName("A GIF disguised as a .pdf is refused by Tika WITHOUT ever reaching the scanner")
    void offWhitelistTypeNeverReachesTheScanner() {
        User buyer = persistUser("buyerAV6@example.com", Role.BUYER);
        User seller = persistUser("sellerAV6@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Magic bytes GIF87a, extension et Content-Type mensongers.
        // Ecrit en tableau d'octets et NON en litteral de chaine : la forme precedente
        // portait les octets de controle BRUTS (0x00 compris), ce qui rendait ce fichier
        // binaire aux yeux de git et INVISIBLE a grep (code de sortie 1, aucune ligne) —
        // exactement le defaut trouve sur AuthView.vue a la 2e revue de suivi de la Story
        // 1.9. Un fichier de test que grep ne voit pas echappe en silence a toutes les
        // greps de verification des stories, y compris celles qui pretendent le couvrir.
        byte[] gif = {'G', 'I', 'F', '8', '7', 'a', 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, ','};
        MockMultipartFile file = new MockMultipartFile("files", "invoice.pdf", "application/pdf", gif);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(), List.of(file), null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(fakeScanner().calls.get()).isZero();
    }

    @Test
    @DisplayName("An empty file is refused before the scanner too — the cheap guards keep their precedence")
    void emptyFileNeverReachesTheScanner() {
        User buyer = persistUser("buyerAV7@example.com", Role.BUYER);
        User seller = persistUser("sellerAV7@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.FUNDS_LOCKED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        MockMultipartFile file = new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> evidenceService.deposit(actor, tx.getId(), List.of(file), null, null))
                .isInstanceOf(BadRequestException.class);

        assertThat(fakeScanner().calls.get()).isZero();
    }
}
