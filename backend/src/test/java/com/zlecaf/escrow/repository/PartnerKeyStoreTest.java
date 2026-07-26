package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.PartnerKeyNonce;
import com.zlecaf.escrow.domain.WebhookSubscription;
import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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
// Les deux colonnes secret_key sont converties depuis la Story 1.7 : Hibernate
// réclame le convertisseur (et son SecretCipher) dès la construction du métamodèle,
// or une tranche @DataJpaTest exclut le scan de composants.
@Import({SecretCipher.class, EncryptedStringConverter.class})
@Testcontainers
class PartnerKeyStoreTest {

    /** Inbound HMAC secret satisfying the entity's >= 32 UTF-8 byte floor (36 bytes). */
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
        persistKey("key-active", company.getId(), VALID_SECRET, true);

        Optional<PartnerHmacKey> resolved = partnerKeys.findByKeyIdAndActiveTrue("key-active");

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getCompanyId()).isEqualTo(company.getId());
        assertThat(resolved.get().getSecretKey()).isEqualTo(VALID_SECRET);
    }

    @Test
    @DisplayName("Inactive key is not resolved (immediate revocation)")
    void inactiveKeyNotResolved() {
        Company company = persistCompany("Carrier Revoked");
        persistKey("key-inactive", company.getId(), VALID_SECRET, false);

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

        persistKey("key-both", company.getId(), VALID_SECRET, true);

        // Resolving the inbound credential yields the inbound secret only.
        PartnerHmacKey resolved = partnerKeys.findByKeyIdAndActiveTrue("key-both").orElseThrow();
        assertThat(resolved.getSecretKey()).isEqualTo(VALID_SECRET);
        assertThat(resolved.getCompanyId()).isEqualTo(company.getId());

        // The outbound webhook secret is untouched and still distinct.
        WebhookSubscription reloaded = webhookSubscriptions.findById(outbound.getId()).orElseThrow();
        assertThat(reloaded.getSecretKey()).isEqualTo("OUTBOUND-WEBHOOK-SECRET");
    }

    @Test
    @DisplayName("Replaying (key_id, nonce) under the same key-id is rejected by the composite UNIQUE")
    void replaySameKeyIdRejected() {
        Company company = persistCompany("Carrier Replay");
        persistKey("key-replay", company.getId(), VALID_SECRET, true);

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
        persistKey("key-a", company.getId(), VALID_SECRET, true);
        persistKey("key-b", company.getId(), VALID_SECRET, true);

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
        persistKey("key-purge", company.getId(), VALID_SECRET, true);

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

    @Test
    @DisplayName("A secret shorter than 32 bytes is rejected at admission by the entity guard")
    void weakSecretRejected() {
        Company company = persistCompany("Carrier Weak");

        PartnerHmacKey weak = new PartnerHmacKey();
        weak.setKeyId("key-weak");
        weak.setCompanyId(company.getId());
        weak.setSecretKey("tooshort");
        weak.setActive(true);

        // Le plancher est désormais DOUBLE : @PrePersist sur l'entité (seul point qui
        // voit le clair côté application) ET le CHECK V7 tolérant l'enveloppe (seul
        // point qui couvre le provisioning en SQL direct). C'est l'entité qui rejette
        // ici, donc en IllegalArgumentException — le type est épinglé pour que le jour
        // où l'admission de clés passe par une API (Epic 7), le changement de statut
        // HTTP qui en découlerait ne passe pas inaperçu.
        assertThatThrownBy(() -> partnerKeys.saveAndFlush(weak))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("partner_hmac_keys.secret_key")
                .hasMessageContaining("minimum 32");
    }

    @Test
    @DisplayName("The length floor is exactly >= 32 UTF-8 BYTES (32 accepted, 31 rejected, accents comptés en octets)")
    void secretLengthBoundaryEnforced() {
        Company company = persistCompany("Carrier Boundary");

        // 32 bytes -> exactly at the floor -> accepted. (Done first: the reject below
        // aborts the surrounding @DataJpaTest transaction, so it must be the last statement.)
        PartnerHmacKey atFloorKey = new PartnerHmacKey();
        atFloorKey.setKeyId("key-32");
        atFloorKey.setCompanyId(company.getId());
        atFloorKey.setSecretKey("B".repeat(32));
        atFloorKey.setActive(true);
        partnerKeys.saveAndFlush(atFloorKey);
        assertThat(partnerKeys.findByKeyIdAndActiveTrue("key-32")).isPresent();

        // 31 CARACTÈRES mais 32 OCTETS (un « é » en pèse deux) -> accepté : la garde
        // compte des octets, pas des caractères (régression prouvée en Story 1.6).
        PartnerHmacKey accentedKey = new PartnerHmacKey();
        accentedKey.setKeyId("key-accented");
        accentedKey.setCompanyId(company.getId());
        accentedKey.setSecretKey("é" + "C".repeat(30));
        accentedKey.setActive(true);
        partnerKeys.saveAndFlush(accentedKey);
        assertThat(partnerKeys.findByKeyIdAndActiveTrue("key-accented")).isPresent();

        // 31 bytes -> just under the floor -> rejected by the entity guard.
        PartnerHmacKey underKey = new PartnerHmacKey();
        underKey.setKeyId("key-31");
        underKey.setCompanyId(company.getId());
        underKey.setSecretKey("A".repeat(31));
        underKey.setActive(true);
        assertThatThrownBy(() -> partnerKeys.saveAndFlush(underKey))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("partner_hmac_keys.secret_key")
                .hasMessageContaining("minimum 32");
    }

    @Test
    @DisplayName("Serializing the key never leaks the secret (field name nor value)")
    void secretNotSerialized() throws Exception {
        Company company = persistCompany("Carrier Serialize");
        PartnerHmacKey key = persistKey("key-serialize", company.getId(), VALID_SECRET, true);

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(key);

        assertThat(json).doesNotContain("secretKey");
        assertThat(json).doesNotContain(VALID_SECRET);
    }

    @Test
    @DisplayName("Serializing the outbound subscription never leaks its secret either (AD-29 symmetry)")
    void webhookSecretNotSerialized() throws Exception {
        Company company = persistCompany("Carrier Webhook Serialize");
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setCompanyId(company.getId());
        subscription.setTargetUrl("https://partner.example/hook");
        subscription.setSecretKey("OUTBOUND-WEBHOOK-SECRET");
        subscription.setEventType("ALL");
        subscription.setActive(true);

        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()
                .writeValueAsString(webhookSubscriptions.save(subscription));

        assertThat(json).doesNotContain("secretKey");
        assertThat(json).doesNotContain("OUTBOUND-WEBHOOK-SECRET");
    }

    @Test
    @DisplayName("Hard-deleting a key that has nonce history is blocked by the FK RESTRICT")
    void keyDeleteRestrictedByNonceHistory() {
        Company company = persistCompany("Carrier Restrict");
        PartnerHmacKey key = persistKey("key-restrict", company.getId(), VALID_SECRET, true);
        nonces.saveAndFlush(newNonce("key-restrict", "nonce-kept", Instant.now()));

        assertThatThrownBy(() -> {
            partnerKeys.delete(key);
            partnerKeys.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("A key with no nonce history is still hard-deletable (RESTRICT blocks only history loss)")
    void nonceFreeKeyIsDeletable() {
        Company company = persistCompany("Carrier Deletable");
        PartnerHmacKey key = persistKey("key-nofree", company.getId(), VALID_SECRET, true);

        partnerKeys.delete(key);
        partnerKeys.flush();

        assertThat(partnerKeys.findByKeyIdAndActiveTrue("key-nofree")).isEmpty();
    }
}
