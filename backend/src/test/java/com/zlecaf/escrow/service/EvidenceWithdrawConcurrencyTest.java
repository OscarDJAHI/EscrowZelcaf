package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
 * Observable proof that the dispute evidence floor (FR-6) holds under a real race.
 * Two concurrent withdrawals of the two last ACTIVE pieces of a DISPUTED
 * transaction contend on the same pessimistically-locked transaction row: the
 * pessimistic lock serialises them, so exactly one commits and the other, on
 * recounting the committed ACTIVE total, is refused — leaving at least one piece.
 *
 * <p>Like {@link EscrowDisputeServiceTest}, this class is
 * {@link Propagation#NOT_SUPPORTED} so each {@code withdraw} call runs in (and
 * commits) its <em>own</em> real transaction — a shared, auto-rolled-back test
 * transaction would serialise everything on one connection and prove nothing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({EvidenceService.class, AuditService.class, EvidenceContentValidator.class,
        TransactionAccess.class, EvidenceWithdrawConcurrencyTest.TestConfig.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EvidenceWithdrawConcurrencyTest {

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

    /** In-memory {@link EvidenceStorage} fake: withdrawal never touches it, but the port must be wired. */
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
    private EvidenceService evidenceService;
    @Autowired
    private UserRepository users;
    @Autowired
    private EscrowTransactionRepository transactions;
    @Autowired
    private EvidenceFileRepository evidenceFiles;

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

    private EvidenceFile persistActiveEvidence(Long txId, Long uploaderId, UploaderType type) {
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
        e.setStatus(EvidenceStatus.ACTIVE);
        return evidenceFiles.save(e);
    }

    @Test
    @DisplayName("Two concurrent withdrawals of the two last ACTIVE pieces: exactly one succeeds, the floor holds at 1")
    void concurrentWithdrawalsKeepTheFloor() throws Exception {
        User buyer = persistUser("concbuyer@example.com", Role.BUYER);
        User seller = persistUser("concseller@example.com", Role.SELLER);
        EscrowTransaction tx = persistTransaction(buyer.getId(), seller.getId(), EscrowState.DISPUTED);
        AuthPrincipal actor = new AuthPrincipal(buyer.getId(), buyer.getEmail(), Role.BUYER);

        // Exactly two ACTIVE pieces owned by the same depositor; each thread targets one.
        EvidenceFile pieceA = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER);
        EvidenceFile pieceB = persistActiveEvidence(tx.getId(), buyer.getId(), UploaderType.BUYER);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<EvidenceDto> f1 = pool.submit(withdrawTask(startGate, actor, tx.getId(), pieceA.getId()));
            Future<EvidenceDto> f2 = pool.submit(withdrawTask(startGate, actor, tx.getId(), pieceB.getId()));

            startGate.countDown();   // release both threads at once

            int successes = 0;
            int conflicts = 0;
            for (Future<EvidenceDto> f : List.of(f1, f2)) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                    successes++;
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    // The loser is refused by the dispute floor alone: it recounts the
                    // committed ACTIVE total behind the pessimistic transaction-row lock
                    // and sees only one piece left. withdraw() never writes the transaction
                    // row, so there is no @Version bump and no optimistic failure on this
                    // path — the floor ConflictException is the sole reachable loser outcome.
                    assertThat(cause).isInstanceOf(ConflictException.class);
                    conflicts++;
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        // The floor held: exactly one ACTIVE piece remains in the dispute.
        assertThat(evidenceFiles.countByTransactionIdAndStatus(tx.getId(), EvidenceStatus.ACTIVE))
                .isEqualTo(1);
    }

    private Callable<EvidenceDto> withdrawTask(CountDownLatch startGate, AuthPrincipal actor,
                                               Long txId, Long evidenceId) {
        return () -> {
            startGate.await();
            return evidenceService.withdraw(actor, txId, evidenceId);
        };
    }
}
