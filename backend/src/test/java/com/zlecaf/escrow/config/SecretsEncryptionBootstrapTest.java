package com.zlecaf.escrow.config;

import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Preuve exécutable du scellement et de la rotation des secrets DÉJÀ persistés
 * (Story 1.7), contre un PostgreSQL réel : c'est le seul endroit où l'on peut
 * démontrer qu'un {@code UPDATE} est bien émis — un {@code save()} JPA n'en
 * émettrait aucun, le dirty-checking comparant l'attribut en clair, identique
 * avant et après rotation.
 *
 * <p>Le runner est construit à la main plutôt qu'injecté : chaque test choisit son
 * trousseau (v1 seule, puis v1+v2 active v2) et le nombre de passes, ce qu'un bean
 * exécuté au démarrage du contexte ne permettrait pas.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SecretCipher.class, EncryptedStringConverter.class})
@Testcontainers
class SecretsEncryptionBootstrapTest {

    private static final String PARTNER_SECRET = "INBOUND-HMAC-SECRET-0123456789ABCDEF";
    private static final String WEBHOOK_SECRET = "OUTBOUND-WEBHOOK-SECRET-0123456789";

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

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String keyMaterial(byte filler) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, filler);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static final String V1 = "v1:" + keyMaterial((byte) 0x31);
    private static final String V2 = "v2:" + keyMaterial((byte) 0x32);

    private void runBootstrap(SecretCipher cipher) throws Exception {
        new SecretsEncryptionBootstrap().sealSecretsAtRest(jdbc, transactionManager, cipher).run();
    }

    /** Écrit une ligne EN CLAIR par SQL brut : l'état exact d'une base d'avant la story. */
    private Long insertLegacyPartnerKey(String keyId) {
        Long companyId = jdbc.queryForObject(
                "INSERT INTO companies (name) VALUES ('Transitaire') RETURNING id", Long.class);
        return jdbc.queryForObject("INSERT INTO partner_hmac_keys (key_id, company_id, secret_key, active, created_at)"
                + " VALUES (?, ?, ?, true, now()) RETURNING id", Long.class, keyId, companyId, PARTNER_SECRET);
    }

    private Long insertLegacyWebhook() {
        return jdbc.queryForObject("INSERT INTO webhook_subscriptions (target_url, secret_key, event_type, is_active,"
                + " created_at) VALUES ('https://p.example/cb', ?, 'ALL', true, now()) RETURNING id",
                Long.class, WEBHOOK_SECRET);
    }

    private String rawColumn(String table, Long id) {
        return jdbc.queryForObject("SELECT secret_key FROM " + table + " WHERE id = ?", String.class, id);
    }

    @Test
    @DisplayName("Une ligne legacy en clair est scellée sous la clé active, dans les deux tables")
    void sealsLegacyPlaintextRows() throws Exception {
        SecretCipher v1 = new SecretCipher(V1, "v1");
        Long partnerId = insertLegacyPartnerKey("key-to-seal");
        Long webhookId = insertLegacyWebhook();

        runBootstrap(v1);

        String sealedPartner = rawColumn("partner_hmac_keys", partnerId);
        String sealedWebhook = rawColumn("webhook_subscriptions", webhookId);
        assertThat(sealedPartner).startsWith("esc:1:v1:").doesNotContain(PARTNER_SECRET);
        assertThat(sealedWebhook).startsWith("esc:1:v1:").doesNotContain(WEBHOOK_SECRET);
        assertThat(v1.decryptFromText(sealedPartner, EncryptedStringConverter.AAD)).isEqualTo(PARTNER_SECRET);
        assertThat(v1.decryptFromText(sealedWebhook, EncryptedStringConverter.AAD)).isEqualTo(WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("Idempotence : une 2ᵉ passe n'écrit rien (l'enveloppe est identique au caractère près)")
    void secondPassWritesNothing() throws Exception {
        SecretCipher v1 = new SecretCipher(V1, "v1");
        Long partnerId = insertLegacyPartnerKey("key-idempotent");
        runBootstrap(v1);
        String afterFirstPass = rawColumn("partner_hmac_keys", partnerId);

        runBootstrap(v1);

        // Un ré-chiffrement tirerait un nouvel IV : une valeur identique prouve
        // qu'aucun UPDATE n'a été émis.
        assertThat(rawColumn("partner_hmac_keys", partnerId)).isEqualTo(afterFirstPass);
    }

    @Test
    @DisplayName("Rotation v1 -> v2 : la ligne est re-chiffrée sous v2, le clair est préservé")
    void rotatesRowsToTheNewActiveKey() throws Exception {
        Long partnerId = insertLegacyPartnerKey("key-to-rotate");
        runBootstrap(new SecretCipher(V1, "v1"));
        String underV1 = rawColumn("partner_hmac_keys", partnerId);

        // Trousseau élargi, clé active basculée : exactement les gestes 2 et 3 du runbook.
        SecretCipher rotated = new SecretCipher(V1 + "," + V2, "v2");
        runBootstrap(rotated);

        String underV2 = rawColumn("partner_hmac_keys", partnerId);
        assertThat(underV2).startsWith("esc:1:v2:").isNotEqualTo(underV1);
        assertThat(rotated.decryptFromText(underV2, EncryptedStringConverter.AAD)).isEqualTo(PARTNER_SECRET);
    }

    @Test
    @DisplayName("Clé absente du trousseau : le balayage échoue en nommant la table et la laisse INTACTE")
    void unreadableRowAbortsTheSweepWithoutTouchingTheTable() throws Exception {
        Long partnerId = insertLegacyPartnerKey("key-orphan");
        runBootstrap(new SecretCipher(V1, "v1"));
        String underV1 = rawColumn("partner_hmac_keys", partnerId);

        // v1 RETIRÉE du trousseau alors qu'une enveloppe la référence encore : c'est
        // l'erreur d'exploitation contre laquelle le runbook met en garde.
        SecretCipher withoutV1 = new SecretCipher(V2, "v2");

        assertThatThrownBy(() -> runBootstrap(withoutV1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partner_hmac_keys")
                .hasMessageContaining("ESCROW_CRYPTO_KEYS");
        // La transaction de la table a été annulée : rien de pivoté à moitié.
        assertThat(rawColumn("partner_hmac_keys", partnerId)).isEqualTo(underV1);
    }

    @Test
    @DisplayName("Un clair sous le plancher n'est jamais scellé : le chiffrer le rendrait faible ET invisible")
    void weakLegacyPlaintextIsNeverSealed() {
        Long companyId = jdbc.queryForObject(
                "INSERT INTO companies (name) VALUES ('Transitaire faible') RETURNING id", Long.class);
        // Le CHECK V7 tolère l'enveloppe, donc on force la ligne faible en la faisant
        // passer pour une enveloppe : c'est le seul moyen d'atteindre le garde-fou du
        // runner, et cela prouve qu'il tient même si une garde amont a sauté.
        jdbc.update("ALTER TABLE partner_hmac_keys DROP CONSTRAINT ck_partner_hmac_keys_secret_len");
        jdbc.update("INSERT INTO partner_hmac_keys (key_id, company_id, secret_key, active, created_at)"
                + " VALUES ('key-weak-legacy', ?, 'court', true, now())", companyId);

        assertThatThrownBy(() -> runBootstrap(new SecretCipher(V1, "v1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("partner_hmac_keys");
        assertThat(rawColumn("partner_hmac_keys",
                jdbc.queryForObject("SELECT id FROM partner_hmac_keys WHERE key_id = 'key-weak-legacy'", Long.class)))
                .isEqualTo("court");
    }

    @Test
    @DisplayName("Une ligne déjà scellée sous la clé active est ignorée, même trousseau élargi")
    void rowAlreadyUnderActiveKeyIsUntouched() throws Exception {
        Long partnerId = insertLegacyPartnerKey("key-already-active");
        runBootstrap(new SecretCipher(V1, "v1"));
        String sealed = rawColumn("partner_hmac_keys", partnerId);

        // v2 ajoutée au trousseau mais PAS active : rien ne doit bouger.
        runBootstrap(new SecretCipher(V1 + "," + V2, "v1"));

        assertThat(rawColumn("partner_hmac_keys", partnerId)).isEqualTo(sealed);
    }
}
