package com.zlecaf.escrow.scheduler;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.PartnerKeyNonce;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Executable proof that the scheduled purge bounds {@code partner_key_nonces}
 * (Story 3.4, report #1) against a real Postgres with Flyway V4 applied. Seeds
 * one expired nonce and one recent one, invokes {@link PartnerNoncePurger#purge()}
 * directly (deterministic, no scheduler), and asserts the expired row is gone
 * while the recent one — still inside the freshness window — survives.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// partner_hmac_keys.secret_key est converti (Story 1.7) : Hibernate réclame le
// convertisseur et son cipher dès la construction du métamodèle.
@Import({SecretCipher.class, EncryptedStringConverter.class})
class PartnerNoncePurgerIntegrationTest {

    /** Inbound HMAC secret satisfying the ck_partner_hmac_keys_secret_len CHECK (>= 32 bytes). */
    private static final String VALID_SECRET = "INBOUND-HMAC-SECRET-0123456789ABCDEF";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, PartnerNoncePurgerIntegrationTest.class);
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway owns the schema; Hibernate must not try to create-drop it.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private CompanyRepository companies;
    @Autowired
    private PartnerHmacKeyRepository partnerKeys;
    @Autowired
    private PartnerKeyNonceRepository nonces;

    private PartnerKeyNonce nonce(String keyId, String value, Instant seenAt) {
        PartnerKeyNonce n = new PartnerKeyNonce();
        n.setKeyId(keyId);
        n.setNonce(value);
        n.setSeenAt(seenAt); // @PrePersist only stamps when null, so this survives
        return n;
    }

    @Test
    @DisplayName("purge() deletes an expired nonce and keeps a recent one within the window")
    void purgeDropsExpiredKeepsRecent() {
        Company company = new Company();
        company.setName("Carrier Purger");
        company = companies.save(company);

        PartnerHmacKey key = new PartnerHmacKey();
        key.setKeyId("key-purger");
        key.setCompanyId(company.getId());
        key.setSecretKey(VALID_SECRET);
        key.setActive(true);
        partnerKeys.saveAndFlush(key); // the nonce FK references partner_hmac_keys(key_id)

        Instant now = Instant.now();
        // retention=1s, tolerance=1s -> cutoff = now - 1s. One row well past it, one fresh.
        nonces.saveAndFlush(nonce("key-purger", "expired", now.minus(1, ChronoUnit.HOURS)));
        nonces.saveAndFlush(nonce("key-purger", "recent", now));

        PartnerNoncePurger purger = new PartnerNoncePurger(nonces, 1L, 1L);

        int removed = purger.purge();

        assertThat(removed).isEqualTo(1);
        assertThat(nonces.existsByKeyIdAndNonce("key-purger", "expired")).isFalse();
        assertThat(nonces.existsByKeyIdAndNonce("key-purger", "recent")).isTrue();
    }

    @Test
    @DisplayName("purge() honors max(retention, tolerance): a short retention never re-opens the replay window")
    void purgeFloorsCutoffAtTolerance() {
        Company company = new Company();
        company.setName("Carrier Floor");
        company = companies.save(company);

        PartnerHmacKey key = new PartnerHmacKey();
        key.setKeyId("key-floor");
        key.setCompanyId(company.getId());
        key.setSecretKey(VALID_SECRET);
        key.setActive(true);
        partnerKeys.saveAndFlush(key);

        Instant now = Instant.now();
        // A nonce 120 s old: within the 300 s tolerance floor, so even with a tiny
        // retention (1 s) the max(1, 300) cutoff must NOT purge it.
        nonces.saveAndFlush(nonce("key-floor", "in-tolerance", now.minus(120, ChronoUnit.SECONDS)));

        PartnerNoncePurger purger = new PartnerNoncePurger(nonces, 1L, 300L);

        int removed = purger.purge();

        assertThat(removed).isZero();
        assertThat(nonces.existsByKeyIdAndNonce("key-floor", "in-tolerance")).isTrue();
    }
}
