package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import com.zlecaf.escrow.service.scan.MalwareScanGateway;
import com.zlecaf.escrow.service.scan.ScanVerdict;
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.support.PostgresTestSupport;
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
import org.springframework.web.multipart.MultipartFile;

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

/**
 * Executable proof of a successful signed partner deposit against a real Postgres
 * with Flyway V1→V5 applied (Story 3.4, report #4). Boots a JPA slice, imports the
 * whole partner deposit path and a fake in-memory storage, signs a real request,
 * and asserts the persisted {@code evidence_files} row is a valid CARRIER_PARTNER
 * attribution ({@code partner_company_id} set, {@code uploaded_by_user_id} null),
 * satisfying the {@code ck_evidence_attribution} CHECK — plus the nonce is consumed
 * and an {@code EVIDENCE_ADDED} audit row with a null actor is written.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// SecretCipher + EncryptedStringConverter : partner_hmac_keys.secret_key est converti
// depuis la Story 1.7, Hibernate les réclame à la construction du métamodèle.
@Import({EvidenceService.class, AuditService.class, EvidenceContentValidator.class,
        TransactionAccess.class, PartnerEvidenceService.class, PartnerSignatureVerifier.class,
        SecretCipher.class, EncryptedStringConverter.class,
        PartnerEvidenceDepositIntegrationTest.TestConfig.class})
class PartnerEvidenceDepositIntegrationTest {

    private static final String VALID_SECRET = "INBOUND-HMAC-SECRET-0123456789ABCDEF";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, PartnerEvidenceDepositIntegrationTest.class);
        registry.add("spring.flyway.enabled", () -> "true");
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

        /**
         * Story 1.8 : {@code MalwareScanGateway} est desormais une dependance
         * OBLIGATOIRE d'{@code EvidenceService}. Ce faux scanner rend TOUJOURS
         * « sain » pour que cette classe continue de prouver exactement ce qu'elle
         * prouvait — jamais en desactivant le scan, qui n'a volontairement aucun
         * interrupteur.
         */
        @Bean
        MalwareScanGateway malwareScanner() {
            return content -> ScanVerdict.clean();
        }
    }

    /** In-memory {@link EvidenceStorage} fake so the deposit round-trip needs no MinIO. */
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
    private PartnerEvidenceService partnerEvidenceService;
    @Autowired
    private CompanyRepository companies;
    @Autowired
    private UserRepository users;
    @Autowired
    private EscrowTransactionRepository transactions;
    @Autowired
    private PartnerHmacKeyRepository partnerKeys;
    @Autowired
    private PartnerKeyNonceRepository nonces;
    @Autowired
    private EvidenceFileRepository evidenceFiles;
    @Autowired
    private AuditLogRepository auditLogs;

    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("A signed partner deposit persists a real CARRIER_PARTNER row, consumes the nonce, and audits with a null actor")
    void signedDepositPersistsPartnerAttribution() {
        Company company = new Company();
        company.setName("Carrier Deposit");
        company = companies.save(company);

        User buyer = new User();
        buyer.setEmail("partner-buyer@example.com");
        buyer.setPasswordHash("x");
        buyer.setRole(Role.BUYER);
        buyer.setCompany(company); // the partner company must be a party to the tx
        buyer = users.save(buyer);

        User seller = new User();
        seller.setEmail("partner-seller@example.com");
        seller.setPasswordHash("x");
        seller.setRole(Role.SELLER);
        seller = users.save(seller);

        EscrowTransaction tx = new EscrowTransaction();
        tx.setBuyerId(buyer.getId());
        tx.setSellerId(seller.getId());
        tx.setAmount(new BigDecimal("1000.00"));
        tx.setCurrency("USD");
        tx.setState(EscrowState.FUNDS_LOCKED); // deposit window open
        tx = transactions.save(tx);

        String keyId = "key-deposit";
        PartnerHmacKey key = new PartnerHmacKey();
        key.setKeyId(keyId);
        key.setCompanyId(company.getId());
        key.setSecretKey(VALID_SECRET);
        key.setActive(true);
        partnerKeys.saveAndFlush(key);

        // Build a genuinely signed request over the frozen canonical string.
        MultipartFile file = new MockMultipartFile("files", "proof.pdf", "application/pdf", pdfBytes());
        List<MultipartFile> files = List.of(file);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = "nonce-" + UUID.randomUUID();
        String comment = "delivered at dock 3";
        String clientCapturedAt = "2026-07-16T10:15:30+02:00";
        String canonical = PartnerSignatureVerifier.canonicalString(
                keyId, tx.getId(), timestamp, nonce, files, comment, clientCapturedAt);
        String signature = HmacSigner.sign(canonical, VALID_SECRET);

        List<EvidenceFile> result = partnerEvidenceService.deposit(
                keyId, signature, timestamp, nonce, tx.getId(), files, comment, clientCapturedAt);

        assertThat(result).hasSize(1);

        List<EvidenceFile> rows = evidenceFiles.findAll();
        assertThat(rows).hasSize(1);
        EvidenceFile row = rows.get(0);
        assertThat(row.getUploaderType()).isEqualTo(UploaderType.CARRIER_PARTNER);
        assertThat(row.getPartnerCompanyId()).isEqualTo(company.getId());
        assertThat(row.getUploadedByUserId()).isNull();
        assertThat(row.getStatus()).isEqualTo(EvidenceStatus.ACTIVE);
        assertThat(row.getTransactionId()).isEqualTo(tx.getId());

        // The nonce was consumed in the same transaction.
        assertThat(nonces.existsByKeyIdAndNonce(keyId, nonce)).isTrue();

        // Audit: EVIDENCE_ADDED with a null actor (partner deposit has no human actor).
        List<AuditLog> audit = auditLogs.findByTransactionIdOrderByTimestampAsc(tx.getId());
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).getActionBy()).isNull();
        assertThat(audit.get(0).getPayload().get("action").asText()).isEqualTo("EVIDENCE_ADDED");
    }
}
