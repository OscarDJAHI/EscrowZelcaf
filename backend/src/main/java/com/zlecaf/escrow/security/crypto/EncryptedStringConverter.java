package com.zlecaf.escrow.security.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Chiffrement transparent des colonnes de secrets (Story 1.7, AD-29 / NFR-P6) :
 * l'entité manipule le clair, la base ne voit qu'une enveloppe {@code esc:1:…}.
 *
 * <p><b>Bean Spring, pas simple classe</b> : {@code @Component} pour que Hibernate
 * l'obtienne via le {@code SpringBeanContainer} de Boot avec son {@link SecretCipher}
 * injecté. Instancié hors conteneur, le convertisseur aurait un cipher {@code null}
 * et échouerait au premier flush — d'où la preuve d'injection en test d'intégration.
 * Corollaire : toute tranche {@code @DataJpaTest} doit importer les deux beans, le
 * scan de composants y étant filtré.
 *
 * <p><b>Lecture tolérante au legacy</b> : une valeur écrite avant cette story n'a
 * pas d'enveloppe. On la rend telle quelle avec un WARN plutôt que d'échouer —
 * casser une lecture au déploiement serait pire que la fuite qu'on ferme, et la
 * ligne sera scellée au prochain démarrage par {@code SecretsEncryptionBootstrap}.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /**
     * Contexte authentifié (AAD) commun à toutes les colonnes de secrets : il
     * empêche de transplanter une enveloppe de colonne vers un objet de stockage
     * (dont l'AAD est la clé de stockage). Volontairement PAS lié à la ligne : le
     * convertisseur ne voit que la valeur, et un attaquant capable d'écrire en base
     * a déjà gagné. {@code public} car le runner de scellement doit employer
     * exactement la même valeur, sous peine de produire des enveloppes illisibles.
     */
    public static final String AAD = "escrow:db-secret";

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

    private final SecretCipher cipher;

    public EncryptedStringConverter(SecretCipher cipher) {
        this.cipher = cipher;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null; // un secret absent reste absent : ne jamais chiffrer un NULL en enveloppe
        }
        return cipher.encryptToText(attribute, AAD);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        if (!cipher.isEnvelope(dbData)) {
            // Ni la valeur ni sa longueur ne sont journalisées ; la colonne exacte
            // n'est pas connue ici (le convertisseur ne reçoit que la valeur), la
            // trace de démarrage du runner de scellement donne le décompte par table.
            log.warn("Secret lu EN CLAIR : ligne écrite avant le chiffrement au repos (Story 1.7). "
                    + "Le prochain démarrage la scelle — SAUF si elle est vide ou sous le plancher de "
                    + "robustesse, deux cas que SecretsEncryptionBootstrap signale nommément (table et id) "
                    + "et qu'il faut corriger à la main. C'est sa trace de démarrage, et non ce WARN, qui "
                    + "donne le décompte par table.");
            return dbData;
        }
        return cipher.decryptFromText(dbData, AAD);
    }
}
