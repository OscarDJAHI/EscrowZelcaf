package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
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
import org.springframework.web.multipart.MultipartFile;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Observable proof that the {@code uq_partner_key_nonces_key_nonce} constraint
 * arbitrates a same-nonce race on a real Postgres (Story 3.4, report #5). Two
 * concurrent deposits carry the SAME {@code (key-id, nonce)}; exactly one is
 * persisted and the other is rejected as a replay ({@link UnauthorizedException}),
 * leaving exactly one evidence row and one nonce row.
 *
 * <p>Class-level {@link Propagation#NOT_SUPPORTED} so each {@code deposit} call
 * runs in — and commits — its own real transaction; a shared, auto-rolled-back
 * test transaction would serialise everything on one connection and prove nothing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EvidenceService.class, AuditService.class, EvidenceContentValidator.class,
        TransactionAccess.class, PartnerEvidenceService.class, PartnerSignatureVerifier.class,
        PartnerNonceConcurrencyIntegrationTest.TestConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PartnerNonceConcurrencyIntegrationTest {

    private static final String VALID_SECRET = "INBOUND-HMAC-SECRET-0123456789ABCDEF";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
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
    }

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

    private static byte[] pdfBytes() {
        return ("%PDF-1.4\n1 0 obj<</Type/Catalog>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("Two concurrent deposits with the same nonce: exactly one succeeds, the other is a replay, DB holds 1 evidence + 1 nonce")
    void concurrentSameNonceArbitratedByUniqueConstraint() throws Exception {
        Company company = new Company();
        company.setName("Carrier Concurrency");
        company = companies.save(company);

        User buyer = new User();
        buyer.setEmail("conc-buyer@example.com");
        buyer.setPasswordHash("x");
        buyer.setRole(Role.BUYER);
        buyer.setCompany(company);
        buyer = users.save(buyer);

        User seller = new User();
        seller.setEmail("conc-seller@example.com");
        seller.setPasswordHash("x");
        seller.setRole(Role.SELLER);
        seller = users.save(seller);

        EscrowTransaction tx = new EscrowTransaction();
        tx.setBuyerId(buyer.getId());
        tx.setSellerId(seller.getId());
        tx.setAmount(new BigDecimal("1000.00"));
        tx.setCurrency("USD");
        tx.setState(EscrowState.FUNDS_LOCKED);
        tx = transactions.save(tx);

        String keyId = "key-concurrency";
        PartnerHmacKey key = new PartnerHmacKey();
        key.setKeyId(keyId);
        key.setCompanyId(company.getId());
        key.setSecretKey(VALID_SECRET);
        key.setActive(true);
        partnerKeys.saveAndFlush(key);

        String nonce = "nonce-" + UUID.randomUUID();
        Long txId = tx.getId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<List<EvidenceFile>> f1 = pool.submit(depositTask(startGate, keyId, nonce, txId));
            Future<List<EvidenceFile>> f2 = pool.submit(depositTask(startGate, keyId, nonce, txId));

            startGate.countDown(); // release both at once

            int successes = 0;
            int replays = 0;
            for (Future<List<EvidenceFile>> f : List.of(f1, f2)) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException ex) {
                    assertThat(ex.getCause()).isInstanceOf(UnauthorizedException.class);
                    replays++;
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(replays).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // The constraint held: exactly one evidence row and exactly one nonce row.
        assertThat(evidenceFiles.countByTransactionIdAndStatus(txId, EvidenceStatus.ACTIVE)).isEqualTo(1);
        assertThat(nonces.existsByKeyIdAndNonce(keyId, nonce)).isTrue();
        assertThat(nonces.findAll()).hasSize(1);
    }

    private Callable<List<EvidenceFile>> depositTask(CountDownLatch startGate, String keyId,
                                                     String nonce, Long txId) {
        return () -> {
            MultipartFile file = new MockMultipartFile("files", "proof.pdf", "application/pdf", pdfBytes());
            List<MultipartFile> files = List.of(file);
            String timestamp = String.valueOf(Instant.now().getEpochSecond());
            String canonical = PartnerSignatureVerifier.canonicalString(
                    keyId, txId, timestamp, nonce, files, null, null);
            String signature = HmacSigner.sign(canonical, VALID_SECRET);
            startGate.await();
            return partnerEvidenceService.deposit(keyId, signature, timestamp, nonce, txId, files, null, null);
        };
    }
}
