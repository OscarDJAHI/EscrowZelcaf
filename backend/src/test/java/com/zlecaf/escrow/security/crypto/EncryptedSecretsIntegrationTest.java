package com.zlecaf.escrow.security.crypto;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.WebhookSubscription;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.WebhookSubscriptionRepository;
import com.zlecaf.escrow.service.HmacSigner;
import com.zlecaf.escrow.service.PartnerSignatureVerifier;
import com.zlecaf.escrow.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Preuve exécutable du chiffrement des colonnes de secrets (Story 1.7, AD-29/NFR-P6)
 * contre un PostgreSQL réel avec Flyway V1→V7 appliqué — donc contre le schéma qui
 * tourne en production, pas contre un DDL Hibernate.
 *
 * <p>Prouve les quatre points qui comptent : (1) ce qui atteint le disque est une
 * enveloppe et jamais le clair, (2) l'entité, elle, retrouve le clair, (3) une ligne
 * legacy écrite avant la story reste lisible, (4) le convertisseur est bien un bean
 * Spring recevant son {@link SecretCipher} — instancié hors conteneur il aurait un
 * cipher {@code null} et échouerait au premier flush.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SecretCipher.class, EncryptedStringConverter.class, PartnerSignatureVerifier.class})
class EncryptedSecretsIntegrationTest {

    /** >= 32 octets UTF-8 : plancher porté par l'entité ET par le CHECK V7 tolérant l'enveloppe. */
    private static final String PARTNER_SECRET = "INBOUND-HMAC-SECRET-0123456789ABCDEF";
    private static final String WEBHOOK_SECRET = "OUTBOUND-WEBHOOK-SECRET-0123456789";

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, EncryptedSecretsIntegrationTest.class);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    private TestEntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CompanyRepository companies;
    @Autowired
    private PartnerHmacKeyRepository partnerKeys;
    @Autowired
    private WebhookSubscriptionRepository webhookSubscriptions;
    @Autowired
    private SecretCipher cipher;
    @Autowired
    private PartnerSignatureVerifier signatureVerifier;

    private Long persistCompany() {
        Company company = new Company();
        company.setName("Transitaire Abidjan");
        return companies.save(company).getId();
    }

    private PartnerHmacKey persistPartnerKey(String keyId, String secret) {
        PartnerHmacKey key = new PartnerHmacKey();
        key.setKeyId(keyId);
        key.setCompanyId(persistCompany());
        key.setSecretKey(secret);
        key.setActive(true);
        PartnerHmacKey saved = partnerKeys.save(key);
        // flush + clear : sans cela on relirait le cache de premier niveau, et la
        // colonne réelle ne serait jamais observée.
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private String rawColumn(String table, Long id) {
        return jdbc.queryForObject("SELECT secret_key FROM " + table + " WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("Le secret HMAC partenaire atteint le disque en enveloppe, jamais en clair")
    void partnerSecretIsSealedOnDisk() {
        Long id = persistPartnerKey("key-sealed", PARTNER_SECRET).getId();

        String stored = rawColumn("partner_hmac_keys", id);

        assertThat(stored).startsWith("esc:1:v1:").doesNotContain(PARTNER_SECRET);
        assertThat(partnerKeys.findById(id)).get()
                .extracting(PartnerHmacKey::getSecretKey)
                .isEqualTo(PARTNER_SECRET);
    }

    @Test
    @DisplayName("Le secret de webhook sortant est scellé de la même façon")
    void webhookSecretIsSealedOnDisk() {
        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setCompanyId(persistCompany());
        subscription.setTargetUrl("https://partenaire.example/callback");
        subscription.setSecretKey(WEBHOOK_SECRET);
        subscription.setEventType("ALL");
        Long id = webhookSubscriptions.save(subscription).getId();
        entityManager.flush();
        entityManager.clear();

        assertThat(rawColumn("webhook_subscriptions", id))
                .startsWith("esc:1:v1:")
                .doesNotContain(WEBHOOK_SECRET);
        assertThat(webhookSubscriptions.findById(id)).get()
                .extracting(WebhookSubscription::getSecretKey)
                .isEqualTo(WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("Le convertisseur est un bean Spring : l'enveloppe écrite s'ouvre avec le SecretCipher du contexte")
    void converterIsSpringManagedAndUsesTheContextCipher() {
        Long id = persistPartnerKey("key-injected", PARTNER_SECRET).getId();

        // Si Hibernate avait instancié le convertisseur hors conteneur, son cipher
        // serait null et le flush ci-dessus aurait déjà explosé en NPE. On va plus
        // loin : l'enveloppe doit s'ouvrir avec CE cipher et CET AAD.
        assertThat(cipher.decryptFromText(rawColumn("partner_hmac_keys", id), EncryptedStringConverter.AAD))
                .isEqualTo(PARTNER_SECRET);
        assertThat(cipher.keyIdOf(rawColumn("partner_hmac_keys", id))).isEqualTo(cipher.activeKeyId());
    }

    @Test
    @DisplayName("Une ligne legacy en clair (écrite avant la story) reste lisible, sans erreur")
    void legacyPlaintextRowStaysReadable() {
        Long companyId = persistCompany();
        // Écriture EN CLAIR par SQL brut : exactement ce qu'un déploiement trouve en
        // base le jour de la mise en service. La rétention WORM interdit que cette
        // lecture échoue.
        jdbc.update("INSERT INTO partner_hmac_keys (key_id, company_id, secret_key, active, created_at)"
                + " VALUES (?, ?, ?, true, now())", "key-legacy", companyId, PARTNER_SECRET);

        Optional<PartnerHmacKey> reloaded = partnerKeys.findByKeyIdAndActiveTrue("key-legacy");

        assertThat(reloaded).get().extracting(PartnerHmacKey::getSecretKey).isEqualTo(PARTNER_SECRET);
    }

    @Test
    @DisplayName("Le CHECK V7 couvre le provisioning en SQL direct : clair court refusé, enveloppe acceptée")
    void directSqlInsertOfWeakPlaintextIsRejectedByTheDatabase() {
        Long companyId = persistCompany();

        // Il n'existe AUCUN chemin applicatif d'admission de clé partenaire jusqu'à
        // l'outillage d'admin de l'Epic 7 : le provisioning se fait en SQL direct, qui
        // ne traverse ni l'entité ni son @PrePersist. Si le CHECK n'était pas conservé,
        // un secret HMAC de 5 octets authentifierait les dépôts partenaires.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO partner_hmac_keys (key_id, company_id, secret_key, active, created_at)"
                        + " VALUES (?, ?, ?, true, now())", "key-sql-weak", companyId, "court"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_partner_hmac_keys_secret_len");
    }

    @Test
    @DisplayName("Le CHECK V7 laisse passer une enveloppe : c'est ce qui rend le chiffrement compatible avec lui")
    void sealedEnvelopeSatisfiesTheCheckConstraint() {
        Long companyId = persistCompany();
        // L'enveloppe ne dit rien de la longueur du clair : la contrainte l'accepte
        // telle quelle, et c'est l'entité (plus le runner) qui garantit le plancher.
        String envelope = cipher.encryptToText(PARTNER_SECRET, EncryptedStringConverter.AAD);

        assertThatCode(() -> jdbc.update(
                "INSERT INTO partner_hmac_keys (key_id, company_id, secret_key, active, created_at)"
                        + " VALUES (?, ?, ?, true, now())", "key-sql-sealed", companyId, envelope))
                .doesNotThrowAnyException();
        assertThat(partnerKeys.findByKeyIdAndActiveTrue("key-sql-sealed"))
                .get().extracting(PartnerHmacKey::getSecretKey).isEqualTo(PARTNER_SECRET);
    }

    @Test
    @DisplayName("Le CHECK V8 refuse un clair court DÉGUISÉ en enveloppe (« esc:… » n'est pas un laissez-passer)")
    void directSqlInsertOfShortValueLookingLikeAnEnvelopeIsRejected() {
        Long companyId = persistCompany();

        // V7 tolérait l'enveloppe par un simple LIKE 'esc:%' — plus large que ce que
        // l'application reconnaît. Une valeur de 25 octets passait donc la contrainte
        // tout en étant lue comme un secret legacy EN CLAIR : un secret HMAC faible
        // authentifiait pour de bon jusqu'au redémarrage suivant, qui échouait.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO partner_hmac_keys (key_id, company_id, secret_key, active, created_at)"
                        + " VALUES (?, ?, ?, true, now())", "key-sql-fake-envelope", companyId,
                "esc:secret-du-transitaire"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_partner_hmac_keys_secret_len");
    }

    @Test
    @DisplayName("La vérification HMAC partenaire fonctionne toujours de bout en bout sur un secret chiffré")
    void partnerSignatureStillVerifiesAfterEncryption() {
        persistPartnerKey("key-signing", PARTNER_SECRET);

        // Le secret est relu depuis la base (donc déchiffré par le convertisseur) et
        // doit encore signer : c'est pour cela qu'il est CHIFFRÉ et non haché.
        PartnerHmacKey reloaded = partnerKeys.findByKeyIdAndActiveTrue("key-signing").orElseThrow();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String canonical = PartnerSignatureVerifier.canonicalString(
                "key-signing", 42L, timestamp, "nonce-1", List.of(), "commentaire", null);
        String signature = HmacSigner.sign(canonical, PARTNER_SECRET);

        assertThatCode(() -> signatureVerifier.verify(reloaded, signature, timestamp, "nonce-1", 42L,
                List.of(), "commentaire", null))
                .doesNotThrowAnyException();
    }
}
