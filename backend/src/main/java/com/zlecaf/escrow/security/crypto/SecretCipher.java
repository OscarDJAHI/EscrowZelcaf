package com.zlecaf.escrow.security.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Chiffrement applicatif au repos (Story 1.7, AD-29 / NFR-P6) : primitive UNIQUE
 * de la plateforme, réutilisée par tout ce qui doit persister un secret ou un
 * binaire sensible (colonnes via {@link EncryptedStringConverter}, objets via
 * l'adaptateur de stockage ; les catégories futures — TOTP 2.6, AML 3.5,
 * coordonnées bancaires 4.9 — brancheront ici plutôt que de refaire de la crypto).
 *
 * <p><b>Pourquoi applicatif et non « du disque ».</b> L'enveloppe doit protéger un
 * {@code pg_dump}, une copie de volume Docker ou une sauvegarde — pas seulement le
 * vol d'un disque nu. Elle est donc produite dans la JVM, avant que la donnée
 * n'atteigne Postgres ou le stockage objet, et reste valable quel que soit le
 * fournisseur de stockage retenu demain (le backend S3 définitif n'est pas tranché).
 *
 * <p><b>Trousseau versionné, pas clé unique.</b> Sans identifiant de clé dans
 * l'enveloppe, la rotation devient une devinette : on ne saurait pas quelle clé a
 * produit quel chiffré. Le trousseau accepte donc N clés (déchiffrement) dont une
 * seule est active (chiffrement) — c'est ce qui rend la bascule v1 -> v2 possible
 * sans interruption, cf. {@code Docs/runbook-rotation-cles-chiffrement.md}.
 *
 * <p><b>Formats d'enveloppe.</b> Texte : {@code esc:1:<keyId>:<base64(iv‖chiffré‖tag)>}.
 * Binaire : {@code ESCX ‖ version(1) ‖ longueur id(1) ‖ id ‖ iv(12) ‖ chiffré‖tag}.
 * Le préfixe sert AUSSI de détecteur de legacy : son absence signifie « écrit avant
 * la Story 1.7 », et la lecture doit alors rendre la valeur telle quelle (WORM,
 * AD-25 : jamais de preuve rendue illisible par un déploiement).
 *
 * <p><b>Clés hors du code</b> (NFR-P1, AD-29) : elles proviennent exclusivement de
 * {@code ESCROW_CRYPTO_KEYS} ; aucune valeur par défaut ici ni ailleurs, et toute
 * configuration invalide refuse le démarrage en nommant la variable fautive.
 */
@Component
public class SecretCipher {

    /** AES-256 : 32 octets, refusés autrement (une clé de 16 donnerait de l'AES-128 en silence). */
    private static final int KEY_BYTES = 32;
    /** 12 octets = taille d'IV nominale de GCM (toute autre valeur impose un dérivé interne plus lent). */
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** Version du FORMAT d'enveloppe (pas de la clé) : un format 2 futur resterait lisible. */
    static final int FORMAT_VERSION = 1;
    static final String TEXT_PREFIX = "esc";
    static final byte[] BINARY_MAGIC = {'E', 'S', 'C', 'X'};

    /** Charset volontairement étroit : l'id voyage dans l'enveloppe texte, séparateur {@code :} inclus. */
    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /**
     * Forme COMPLÈTE de l'enveloppe texte, pas un simple {@code startsWith("esc:")} :
     * un secret legacy en clair qui commencerait par « esc: » serait sinon pris pour
     * une enveloppe et rendrait la ligne illisible.
     */
    private static final Pattern TEXT_ENVELOPE_PATTERN =
            Pattern.compile("^esc:\\d{1,3}:[A-Za-z0-9_-]{1,64}:[A-Za-z0-9+/]+={0,2}$");

    private static final String KEYS_ENV = "ESCROW_CRYPTO_KEYS (escrow.crypto.keys)";
    private static final String ACTIVE_KEY_ENV = "ESCROW_CRYPTO_ACTIVE_KEY_ID (escrow.crypto.active-key-id)";

    private final Map<String, SecretKeySpec> keyring;
    private final String activeKeyId;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param rawKeyring          {@code id:base64,id:base64…} — chaque clé exactement 32 octets décodés
     * @param configuredActiveKey identifiant de la clé de CHIFFREMENT ; vide = déduit
     *                            quand le trousseau ne contient qu'une clé (cas du
     *                            déploiement initial), obligatoire dès qu'il y en a
     *                            plusieurs — c'est-à-dire pendant une rotation, où
     *                            deviner le sens de la bascule serait dangereux.
     */
    public SecretCipher(
            @Value("${escrow.crypto.keys}") String rawKeyring,
            @Value("${escrow.crypto.active-key-id:}") String configuredActiveKey) {
        this.keyring = parseKeyring(rawKeyring);
        this.activeKeyId = resolveActiveKeyId(configuredActiveKey, this.keyring);
    }

    private static Map<String, SecretKeySpec> parseKeyring(String rawKeyring) {
        if (rawKeyring == null || rawKeyring.isBlank()) {
            throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV
                    + " est absent ou vide. Format attendu : id:cléBase64[,id:cléBase64…]"
                    + " avec des clés de " + KEY_BYTES + " octets (openssl rand -base64 32).");
        }
        Map<String, SecretKeySpec> keys = new LinkedHashMap<>();
        for (String entry : rawKeyring.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue; // tolère une virgule finale ou un espace de mise en forme
            }
            int separator = trimmed.indexOf(':');
            if (separator <= 0 || separator == trimmed.length() - 1) {
                throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV
                        + " : entrée mal formée, « id:cléBase64 » attendu (le matériel de clé n'est jamais journalisé).");
            }
            String keyId = trimmed.substring(0, separator);
            if (!KEY_ID_PATTERN.matcher(keyId).matches()) {
                throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV
                        + " : identifiant de clé « " + keyId + " » invalide (attendu [A-Za-z0-9_-], 1 à 64 caractères).");
            }
            byte[] material;
            try {
                material = Base64.getDecoder().decode(trimmed.substring(separator + 1));
            } catch (IllegalArgumentException notBase64) {
                throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV
                        + " : la clé « " + keyId + " » n'est pas du base64 valide.", notBase64);
            }
            if (material.length != KEY_BYTES) {
                throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV
                        + " : la clé « " + keyId + " » fait " + material.length + " octets décodés, "
                        + KEY_BYTES + " exigés (AES-256).");
            }
            if (keys.put(keyId, new SecretKeySpec(material, "AES")) != null) {
                // Deux clés sous le même id : les enveloppes de l'une deviendraient
                // indéchiffrables au gré de l'ordre de lecture. Refus explicite.
                throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV
                        + " : identifiant de clé « " + keyId + " » présent deux fois.");
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException("Démarrage refusé : " + KEYS_ENV + " ne contient aucune clé exploitable.");
        }
        return Map.copyOf(keys);
    }

    private static String resolveActiveKeyId(String configured, Map<String, SecretKeySpec> keyring) {
        if (configured == null || configured.isBlank()) {
            if (keyring.size() > 1) {
                throw new IllegalStateException("Démarrage refusé : " + ACTIVE_KEY_ENV
                        + " est obligatoire dès que le trousseau contient plusieurs clés (ids présents : "
                        + keyring.keySet() + ") — sinon le sens de la rotation serait deviné.");
            }
            return keyring.keySet().iterator().next();
        }
        String keyId = configured.trim();
        if (!keyring.containsKey(keyId)) {
            throw new IllegalStateException("Démarrage refusé : " + ACTIVE_KEY_ENV
                    + " désigne « " + keyId + " », absent du trousseau (ids présents : " + keyring.keySet() + ").");
        }
        return keyId;
    }

    /** Identifiant de la clé sous laquelle toute NOUVELLE écriture est scellée. */
    public String activeKeyId() {
        return activeKeyId;
    }

    /**
     * @param aad contexte lié à l'enveloppe (authentifié, non chiffré) : une
     *            enveloppe transplantée dans un autre contexte ne s'ouvrira pas
     */
    public String encryptToText(String plaintext, String aad) {
        Objects.requireNonNull(plaintext, "plaintext");
        String header = TEXT_PREFIX + ":" + FORMAT_VERSION + ":" + activeKeyId;
        byte[] sealed = seal(plaintext.getBytes(StandardCharsets.UTF_8), header + "|" + aad, activeKeyId);
        return header + ":" + Base64.getEncoder().encodeToString(sealed);
    }

    public String decryptFromText(String envelope, String aad) {
        Objects.requireNonNull(envelope, "envelope");
        String[] parts = envelope.split(":", 4);
        if (parts.length != 4 || !TEXT_PREFIX.equals(parts[0])) {
            throw new IllegalStateException("Déchiffrement impossible : enveloppe texte illisible (préfixe « "
                    + TEXT_PREFIX + ": » attendu).");
        }
        requireSupportedVersion(parts[1]);
        byte[] sealed;
        try {
            sealed = Base64.getDecoder().decode(parts[3]);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalStateException("Déchiffrement impossible : corps d'enveloppe non base64 (clé « "
                    + parts[2] + " »).", notBase64);
        }
        // L'en-tête RELU (et non celui qu'on attendrait) entre dans l'AAD : réécrire
        // « esc:1:v1: » en « esc:1:v2: » fait échouer le tag au lieu de tromper
        // silencieusement le runner de rotation, qui croirait la ligne déjà pivotée.
        String header = TEXT_PREFIX + ":" + parts[1] + ":" + parts[2];
        return new String(open(sealed, header + "|" + aad, parts[2]), StandardCharsets.UTF_8);
    }

    public byte[] encryptBytes(byte[] plaintext, String aad) {
        Objects.requireNonNull(plaintext, "plaintext");
        byte[] keyIdBytes = activeKeyId.getBytes(StandardCharsets.US_ASCII);
        byte[] sealed = seal(plaintext, binaryHeader(FORMAT_VERSION, activeKeyId) + "|" + aad, activeKeyId);
        return ByteBuffer.allocate(BINARY_MAGIC.length + 2 + keyIdBytes.length + sealed.length)
                .put(BINARY_MAGIC)
                .put((byte) FORMAT_VERSION)
                // Un octet suffit : KEY_ID_PATTERN borne l'id à 64 caractères ASCII.
                .put((byte) keyIdBytes.length)
                .put(keyIdBytes)
                .put(sealed)
                .array();
    }

    public byte[] decryptBytes(byte[] envelope, String aad) {
        Objects.requireNonNull(envelope, "envelope");
        if (!isEnvelope(envelope)) {
            throw new IllegalStateException("Déchiffrement impossible : enveloppe binaire illisible (magic « ESCX » absent).");
        }
        int version = envelope[BINARY_MAGIC.length];
        requireSupportedVersion(String.valueOf(version));
        int keyIdLength = envelope[BINARY_MAGIC.length + 1] & 0xFF;
        int bodyOffset = BINARY_MAGIC.length + 2 + keyIdLength;
        if (envelope.length < bodyOffset + IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Déchiffrement impossible : enveloppe binaire tronquée.");
        }
        String keyId = new String(envelope, BINARY_MAGIC.length + 2, keyIdLength, StandardCharsets.US_ASCII);
        // Même raison que côté texte : l'en-tête est écrit hors du chiffré, seule son
        // entrée dans l'AAD empêche qu'on le réécrive sans que rien ne s'en aperçoive.
        return open(Arrays.copyOfRange(envelope, bodyOffset, envelope.length),
                binaryHeader(version, keyId) + "|" + aad, keyId);
    }

    /** En-tête binaire sous forme textuelle, pour l'AAD uniquement. */
    private static String binaryHeader(int version, String keyId) {
        return new String(BINARY_MAGIC, StandardCharsets.US_ASCII) + ":" + version + ":" + keyId;
    }

    /** Détecteur de legacy : {@code false} = valeur écrite avant la Story 1.7, à rendre telle quelle. */
    public boolean isEnvelope(String value) {
        return value != null && TEXT_ENVELOPE_PATTERN.matcher(value).matches();
    }

    /** Idem côté binaire ; ne lit que le magic, jamais le corps. */
    public boolean isEnvelope(byte[] value) {
        if (value == null || value.length < BINARY_MAGIC.length + 2) {
            return false;
        }
        for (int i = 0; i < BINARY_MAGIC.length; i++) {
            if (value[i] != BINARY_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Identifiant de la clé ayant produit une enveloppe texte — ce que le runner de
     * re-chiffrement compare à {@link #activeKeyId()} pour décider quoi pivoter.
     */
    public String keyIdOf(String envelope) {
        if (!isEnvelope(envelope)) {
            throw new IllegalStateException("Enveloppe texte attendue, valeur non enveloppée reçue.");
        }
        return envelope.split(":", 4)[2];
    }

    private static void requireSupportedVersion(String version) {
        if (!String.valueOf(FORMAT_VERSION).equals(version)) {
            throw new IllegalStateException("Déchiffrement impossible : version d'enveloppe « " + version
                    + " » inconnue (cette version du logiciel lit le format " + FORMAT_VERSION + ").");
        }
    }

    /** @return iv(12) ‖ chiffré ‖ tag(16) */
    private byte[] seal(byte[] plaintext, String aad, String keyId) {
        // IV tiré à CHAQUE opération : réutiliser un couple (clé, IV) en GCM ne
        // dégrade pas la confidentialité, il l'annule et expose la clé d'authentification.
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(keyId), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aadBytes(aad));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException e) {
            // Jamais le clair ni la clé dans le message : ce texte finit dans les logs.
            throw new IllegalStateException("Chiffrement AES-256-GCM impossible (clé « " + keyId + " »).", e);
        }
    }

    private byte[] open(byte[] sealed, String aad, String keyId) {
        if (sealed.length < IV_BYTES + TAG_BITS / 8) {
            throw new IllegalStateException("Déchiffrement impossible : enveloppe tronquée (clé « " + keyId + " »).");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(keyId),
                    new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            cipher.updateAAD(aadBytes(aad));
            // doFinal ne rend rien tant que le tag n'est pas vérifié : pas de clair partiel.
            return cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
        } catch (AEADBadTagException tampered) {
            throw new IllegalStateException("Déchiffrement refusé : le tag GCM ne valide pas (clé « " + keyId
                    + " ») — donnée altérée, ou contexte AAD différent de celui du chiffrement.", tampered);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Déchiffrement impossible (clé « " + keyId + " »).", e);
        }
    }

    private SecretKeySpec key(String keyId) {
        SecretKeySpec key = keyring.get(keyId);
        if (key == null) {
            // Cas typique : une ancienne clé retirée du trousseau alors que des
            // enveloppes la référencent encore. Nommer l'id est TOUTE l'information
            // dont l'opérateur a besoin pour la remettre (cf. runbook de rotation).
            throw new IllegalStateException("Déchiffrement impossible : la clé « " + keyId
                    + " » n'est pas dans " + KEYS_ENV + " (ids présents : " + keyring.keySet()
                    + "). La remettre au trousseau pour relire cette donnée.");
        }
        return key;
    }

    private static byte[] aadBytes(String aad) {
        // AAD obligatoire : c'est elle qui lie l'enveloppe à son contexte (clé de
        // stockage pour un objet, usage pour une colonne). L'oublier serait accepter
        // qu'une enveloppe soit recopiée ailleurs et s'y déchiffre.
        Objects.requireNonNull(aad, "aad");
        return aad.getBytes(StandardCharsets.UTF_8);
    }
}
