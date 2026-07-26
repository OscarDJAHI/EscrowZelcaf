package com.zlecaf.escrow.config;

import com.zlecaf.escrow.security.crypto.EncryptedStringConverter;
import com.zlecaf.escrow.security.crypto.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * Scellement et rotation des secrets déjà persistés (Story 1.7, AD-29).
 *
 * <p>Deux besoins, un seul balayage idempotent au démarrage (gabarit
 * {@code AdminBootstrap}) :
 * <ul>
 *   <li><b>Scellement</b> : les lignes écrites avant cette story sont en clair.
 *       Les laisser ainsi viderait la story de son sens — elles sont chiffrées
 *       sous la clé active dès le premier démarrage.</li>
 *   <li><b>Rotation</b> : une enveloppe produite par une clé qui n'est plus active
 *       est déchiffrée puis re-scellée sous la nouvelle. C'est ce qui rend la
 *       bascule v1 -> v2 réelle et non déclarative.</li>
 * </ul>
 *
 * <p><b>Pourquoi du JDBC brut et non JPA.</b> Le dirty-checking d'Hibernate compare
 * l'attribut EN CLAIR : après une rotation, {@code entity.setSecretKey(sameValue)}
 * suivi d'un {@code save()} n'émettrait aucun UPDATE — le pivot serait un no-op
 * silencieux. Il faut donc écrire la valeur BRUTE de la colonne, ce que seul un
 * accès hors session JPA permet.
 *
 * <p>Idempotent par construction : une ligne déjà scellée sous la clé active n'est
 * pas réécrite, donc une seconde exécution ne produit aucune écriture.
 */
@Configuration
public class SecretsEncryptionBootstrap {

    private static final Logger log = LoggerFactory.getLogger(SecretsEncryptionBootstrap.class);

    /**
     * Tables portant une colonne {@code secret_key} convertie par
     * {@link EncryptedStringConverter}, avec le plancher de robustesse applicable au
     * CLAIR de chacune. Noms en dur (jamais d'entrée externe) : ils sont interpolés
     * dans le SQL, ce qui serait une injection avec autre chose.
     *
     * <p>Le plancher est revérifié ICI parce que le scellement est la dernière
     * occasion de voir le clair : sceller un secret faible le rendrait invisible à
     * jamais, y compris au CHECK de la base qui ne peut plus mesurer que l'enveloppe.
     * {@code webhook_subscriptions} n'a jamais eu de plancher (0) — l'en poser un
     * serait un durcissement hors périmètre de cette story.
     */
    private static final List<SealedTable> SEALED_TABLES = List.of(
            new SealedTable("partner_hmac_keys", 32),
            new SealedTable("webhook_subscriptions", 0));

    @Bean
    CommandLineRunner sealSecretsAtRest(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            SecretCipher cipher) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        return args -> {
            for (SealedTable table : SEALED_TABLES) {
                // Une transaction PAR TABLE : un échec de déchiffrement (clé retirée
                // du trousseau) doit laisser la table intacte, jamais à moitié pivotée.
                Sweep sweep;
                try {
                    sweep = transaction.execute(status -> sweepTable(jdbc, cipher, table));
                } catch (RuntimeException e) {
                    // Sans ce ré-habillage, l'opérateur ne reçoit qu'une pile sans
                    // savoir QUELLE table a échoué ni quoi faire — le décompte de
                    // synthèse, lui, n'est jamais émis pour la table fautive.
                    throw new IllegalStateException("Chiffrement au repos [" + table.name()
                            + "] : balayage interrompu, table laissée INTACTE (transaction annulée). "
                            + "Cause probable : une clé référencée par une enveloppe a été retirée de "
                            + "ESCROW_CRYPTO_KEYS — la remettre au trousseau et redémarrer "
                            + "(Docs/runbook-rotation-cles-chiffrement.md).", e);
                }
                log.info("Chiffrement au repos [{}] : {} scellée(s), {} pivotée(s), {} inchangée(s)",
                        table.name(), sweep.sealed(), sweep.rotated(), sweep.untouched());
            }
        };
    }

    private Sweep sweepTable(JdbcTemplate jdbc, SecretCipher cipher, SealedTable sealedTable) {
        String table = sealedTable.name();
        int sealed = 0;
        int rotated = 0;
        int untouched = 0;
        String update = "UPDATE " + table + " SET secret_key = ? WHERE id = ?";
        for (Map<String, Object> row : jdbc.queryForList("SELECT id, secret_key FROM " + table + " ORDER BY id")) {
            Long id = ((Number) row.get("id")).longValue();
            String stored = (String) row.get("secret_key");
            if (stored == null || stored.isEmpty()) {
                untouched++;
            } else if (!cipher.isEnvelope(stored)) {
                requireStrongEnough(sealedTable, id, stored);
                jdbc.update(update, cipher.encryptToText(stored, EncryptedStringConverter.AAD), id);
                sealed++;
            } else if (!cipher.activeKeyId().equals(cipher.keyIdOf(stored))) {
                String plaintext = cipher.decryptFromText(stored, EncryptedStringConverter.AAD);
                jdbc.update(update, cipher.encryptToText(plaintext, EncryptedStringConverter.AAD), id);
                rotated++;
            } else {
                untouched++;
            }
        }
        return new Sweep(sealed, rotated, untouched);
    }

    /**
     * Refuse de sceller un clair sous le plancher : une fois chiffré, plus aucune
     * couche ne peut constater sa faiblesse. Inatteignable en pratique sur
     * {@code partner_hmac_keys} (le CHECK de V5 puis celui de V7 l'interdisent) —
     * c'est précisément pour cela qu'échouer ici est sûr : si le cas se produit,
     * c'est qu'une garde a sauté, et le silence serait pire que l'arrêt.
     */
    private static void requireStrongEnough(SealedTable table, Long id, String plaintext) {
        if (table.minPlaintextBytes() == 0) {
            return;
        }
        int bytes = plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < table.minPlaintextBytes()) {
            // Ni le secret ni sa valeur ne sont journalisés : seulement de quoi
            // retrouver la ligne et la corriger.
            throw new IllegalStateException("Scellement refusé : " + table.name() + " id=" + id
                    + " porte un secret EN CLAIR de " + bytes + " octets, sous le plancher de "
                    + table.minPlaintextBytes() + ". Le chiffrer le rendrait faible ET invisible : "
                    + "remplacer ce secret avant de redémarrer.");
        }
    }

    /** Table balayée et plancher de robustesse de son clair (0 = aucun). */
    private record SealedTable(String name, int minPlaintextBytes) {}

    /** Décompte de synthèse d'un balayage — la seule trace laissée, jamais de valeur. */
    private record Sweep(int sealed, int rotated, int untouched) {}
}
