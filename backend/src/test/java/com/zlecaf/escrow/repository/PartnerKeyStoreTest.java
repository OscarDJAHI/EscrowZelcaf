package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.PartnerKeyNonce;
import com.zlecaf.escrow.domain.WebhookSubscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Executable proof of the inbound HMAC key store and nonce anti-replay invariants
 * (Story 3.1). Runs against a real PostgreSQL with Flyway {@code V4} applied, so
 * the UNIQUE / FK / composite-UNIQUE constraints are the ones actually enforcing
 * behavior — not Hibernate DDL. Covers every row of the spec's I/O matrix:
 * active/inactive key resolution, inbound↔outbound secret isolation, per-key-id
 * replay rejection, cross-key-id nonce reuse, and the bounded retention purge.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PartnerKeyStoreTest {

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

    @Autowired
    private CompanyRepository companies;
    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptions;
    @Autowired
    private PartnerHmacKeyRepository partnerKeys;
    @Autowired
    private PartnerKeyNonceRepository nonces;

    private Company persistCompany(String name) {
        Company c = new Company();
        c.setName(name);
        return companies.save(c);
    }

    private PartnerHmacKey persistKey(String keyId, Long companyId, String secret, boolean active) {
        PartnerHmacKey k = new PartnerHmacKey();
        k.setKeyId(keyId);
        k.setCompanyId(companyId);
        k.setSecretKey(secret);
        k.setActive(active);
        return partnerKeys.save(k);
    }

    private PartnerKeyNonce newNonce(String keyId, String nonce, Instant seenAt) {
        PartnerKeyNonce n = new PartnerKeyNonce();
        n.setKeyId(keyId);
        n.setNonce(nonce);
        n.setSeenAt(seenAt);
        return n;
    }

    @Test
    @DisplayName("Active key resolves to its inbound secret and owning company")
    void activeKeyResolves() {
        Company company = persistCompany("Carrier Active");
        persistKey("key-active", company.getId(), "INBOUND-SECRET", true);

        Optional<PartnerHmacKey> resolved = partnerKeys.findByKeyIdAndActiveTrue("key-active");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getCompanyId()).isEqualTo(company.getId());
        assertThat(resolved.get().getSecretKey()).isEqualTo("INBOUND-SECRET");
    }

    @Test
    @DisplayName("Inactive key is not resolved (immediate revocation)")
    void inactiveKeyNotResolved() {
        Company company = persistCompany("Carrier Revoked");
        persistKey("key-inactive", company.getId(), "INBOUND-SECRET", false);

        assertThat(partnerKeys.findByKeyIdAndActiveTrue("key-inactive")).isEmpty();
    }

    @Test
    @DisplayName("Inbound key and outbound webhook secret coexist, independent, in separate tables")
    void inboundOutboundIsolation() {
        Company company = persistCompany("Carrier Both");

        WebhookSubscription outbound = new WebhookSubscription();
        outbound.setCompanyId(company.getId());
        outbound.setTargetUrl("https://partner.example/hook");
        outbound.setSecretKey("OUTBOUND-WEBHOOK-SECRET");
        outbound.setEventType("ALL");
        outbound.setActive(true);
        outbound = webhookSubscriptions.save(outbound);

        persistKey("key-both", company.getId(), "INBOUND-HMAC-SECRET", true);

        // Resolving the inbound credential yields the inbound secret only.
        PartnerHmacKey resolved = partnerKeys.findByKeyIdAndActiveTrue("key-both").orElseThrow();
        assertThat(resolved.getSecretKey()).isEqualTo("INBOUND-HMAC-SECRET");
        assertThat(resolved.getCompanyId()).isEqualTo(company.getId());

        // The outbound webhook secret is untouched and still distinct.
        WebhookSubscription reloaded = webhookSubscriptions.findById(outbound.getId()).orElseThrow();
        assertThat(reloaded.getSecretKey()).isEqualTo("OUTBOUND-WEBHOOK-SECRET");
    }

    @Test
    @DisplayName("Replaying (key_id, nonce) under the same key-id is rejected by the composite UNIQUE")
    void replaySameKeyIdRejected() {
        Company company = persistCompany("Carrier Replay");
        persistKey("key-replay", company.getId(), "S", true);

        assertThat(nonces.existsByKeyIdAndNonce("key-replay", "nonce-1")).isFalse();

        nonces.saveAndFlush(newNonce("key-replay", "nonce-1", Instant.now()));

        assertThat(nonces.existsByKeyIdAndNonce("key-replay", "nonce-1")).isTrue();

        assertThatThrownBy(() ->
                nonces.saveAndFlush(newNonce("key-replay", "nonce-1", Instant.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Same nonce is allowed under two distinct key-ids (uniqueness scoped per key-id)")
    void sameNonceDifferentKeyIdAllowed() {
        Company company = persistCompany("Carrier Scoped");
        persistKey("key-a", company.getId(), "SA", true);
        persistKey("key-b", company.getId(), "SB", true);

        nonces.saveAndFlush(newNonce("key-a", "shared-nonce", Instant.now()));
        // Same nonce string, different key-id: no constraint spans the two.
        nonces.saveAndFlush(newNonce("key-b", "shared-nonce", Instant.now()));

        assertThat(nonces.existsByKeyIdAndNonce("key-a", "shared-nonce")).isTrue();
        assertThat(nonces.existsByKeyIdAndNonce("key-b", "shared-nonce")).isTrue();
    }

    @Test
    @DisplayName("Bounded purge deletes nonces older than the cutoff and keeps the recent tail")
    void boundedPurge() {
        Company company = persistCompany("Carrier Purge");
        persistKey("key-purge", company.getId(), "SP", true);

        Instant now = Instant.now();
        Instant cutoff = now.minus(5, ChronoUnit.MINUTES);

        // Older than the cutoff -> must be purged.
        nonces.saveAndFlush(newNonce("key-purge", "old", now.minus(10, ChronoUnit.MINUTES)));
        // Newer than the cutoff (within the validity window) -> must survive.
        nonces.saveAndFlush(newNonce("key-purge", "fresh", now.minus(1, ChronoUnit.MINUTES)));

        long deleted = nonces.deleteBySeenAtBefore(cutoff);

        assertThat(deleted).isEqualTo(1);
        assertThat(nonces.existsByKeyIdAndNonce("key-purge", "old")).isFalse();
        assertThat(nonces.existsByKeyIdAndNonce("key-purge", "fresh")).isTrue();
    }
}
