package com.zlecaf.escrow.security.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Preuve exécutable de la primitive de chiffrement au repos (Story 1.7, AD-29).
 * Couvre la matrice d'E/S de la spec : aller-retour texte et binaire, non-déterminisme
 * de l'IV, rejet d'une altération, rejet d'un AAD divergent, clé retirée du trousseau,
 * et les configurations invalides qui doivent refuser le démarrage.
 */
class SecretCipherTest {

    private static final String AAD = "escrow:db-secret";

    /** Matériel de clé DÉTERMINISTE et manifestement de 32 octets : un échec reste reproductible. */
    private static String keyMaterial(byte filler) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, filler);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static final String V1 = keyMaterial((byte) 0x11);
    private static final String V2 = keyMaterial((byte) 0x22);

    private static SecretCipher cipherV1() {
        return new SecretCipher("v1:" + V1, "v1");
    }

    @Nested
    @DisplayName("Aller-retour")
    class RoundTrip {

        @Test
        @DisplayName("Texte : le clair relu est identique, et la base ne voit qu'une enveloppe esc:1:v1:…")
        void textRoundTrip() {
            SecretCipher cipher = cipherV1();
            String secret = "INBOUND-HMAC-SECRET-0123456789ABCDEF";

            String envelope = cipher.encryptToText(secret, AAD);

            assertThat(envelope).startsWith("esc:1:v1:").doesNotContain(secret);
            assertThat(cipher.decryptFromText(envelope, AAD)).isEqualTo(secret);
        }

        @Test
        @DisplayName("Texte : un clair non-ASCII survit à l'aller-retour (encodage UTF-8 explicite)")
        void textRoundTripSupportsNonAscii() {
            SecretCipher cipher = cipherV1();
            String secret = "clé-partenaire-àéîöû-中文-🔐-padding-32+";

            assertThat(cipher.decryptFromText(cipher.encryptToText(secret, AAD), AAD)).isEqualTo(secret);
        }

        @Test
        @DisplayName("Binaire : les octets relus sont identiques, et l'objet porte le magic ESCX")
        void binaryRoundTrip() {
            SecretCipher cipher = cipherV1();
            byte[] content = "%PDF-1.7 contenu de preuve confidentiel".getBytes(StandardCharsets.UTF_8);

            byte[] envelope = cipher.encryptBytes(content, "42/objet");

            assertThat(Arrays.copyOf(envelope, 4)).isEqualTo("ESCX".getBytes(StandardCharsets.US_ASCII));
            assertThat(new String(envelope, StandardCharsets.ISO_8859_1)).doesNotContain("contenu de preuve");
            assertThat(cipher.decryptBytes(envelope, "42/objet")).isEqualTo(content);
        }

        @Test
        @DisplayName("Binaire : un contenu vide reste un aller-retour valide (pas de cas dégénéré)")
        void binaryRoundTripOnEmptyContent() {
            SecretCipher cipher = cipherV1();

            assertThat(cipher.decryptBytes(cipher.encryptBytes(new byte[0], "7/vide"), "7/vide")).isEmpty();
        }

        @Test
        @DisplayName("Deux chiffrements du MÊME clair donnent deux enveloppes différentes (IV tiré à chaque opération)")
        void sameCleartextYieldsDistinctEnvelopes() {
            SecretCipher cipher = cipherV1();
            String secret = "un-secret-repete-a-l-identique-32+";

            String first = cipher.encryptToText(secret, AAD);
            String second = cipher.encryptToText(secret, AAD);

            assertThat(first).isNotEqualTo(second);
            assertThat(cipher.decryptFromText(first, AAD)).isEqualTo(cipher.decryptFromText(second, AAD));
        }
    }

    @Nested
    @DisplayName("Intégrité et contexte")
    class Integrity {

        @Test
        @DisplayName("Un octet du chiffré modifié -> rejet par le tag GCM, jamais de clair partiel")
        void tamperedTextIsRejected() {
            SecretCipher cipher = cipherV1();
            String envelope = cipher.encryptToText("secret-a-alterer-en-base-32-oct!", AAD);

            String[] parts = envelope.split(":", 4);
            byte[] sealed = Base64.getDecoder().decode(parts[3]);
            sealed[sealed.length - 1] ^= 0x01; // dernier octet = tag
            String tampered = parts[0] + ":" + parts[1] + ":" + parts[2] + ":"
                    + Base64.getEncoder().encodeToString(sealed);

            assertThatThrownBy(() -> cipher.decryptFromText(tampered, AAD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tag GCM");
        }

        @Test
        @DisplayName("Un octet de l'IV modifié -> rejet également (l'IV est authentifié par le tag)")
        void tamperedIvIsRejected() {
            SecretCipher cipher = cipherV1();
            byte[] envelope = cipher.encryptBytes("preuve".getBytes(StandardCharsets.UTF_8), "42/objet");

            // En-tête = magic(4) + version(1) + longueur id(1) + "v1"(2) ; l'IV suit.
            envelope[8] ^= 0x01;

            assertThatThrownBy(() -> cipher.decryptBytes(envelope, "42/objet"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tag GCM");
        }

        @Test
        @DisplayName("Enveloppe d'objet recopiée sous une AUTRE clé de stockage -> rejet (AAD divergent)")
        void envelopeTransplantedToAnotherStorageKeyIsRejected() {
            SecretCipher cipher = cipherV1();
            byte[] envelope = cipher.encryptBytes("preuve".getBytes(StandardCharsets.UTF_8), "42/original");

            assertThatThrownBy(() -> cipher.decryptBytes(envelope, "99/vole"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AAD");
        }

        @Test
        @DisplayName("Enveloppe de colonne relue avec un autre AAD -> rejet (pas de transplantation d'usage)")
        void columnEnvelopeWithDivergentAadIsRejected() {
            SecretCipher cipher = cipherV1();
            String envelope = cipher.encryptToText("secret-de-colonne-32-octets-min!", AAD);

            assertThatThrownBy(() -> cipher.decryptFromText(envelope, "escrow:autre-usage"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("AAD");
        }

        @Test
        @DisplayName("Une valeur en clair (legacy) n'est PAS reconnue comme enveloppe")
        void plaintextIsNotDetectedAsEnvelope() {
            SecretCipher cipher = cipherV1();

            assertThat(cipher.isEnvelope("INBOUND-HMAC-SECRET-0123456789ABCDEF")).isFalse();
            assertThat(cipher.isEnvelope("esc:mais-pas-une-enveloppe")).isFalse();
            assertThat(cipher.isEnvelope((String) null)).isFalse();
            assertThat(cipher.isEnvelope("hello world".getBytes(StandardCharsets.UTF_8))).isFalse();
            assertThat(cipher.isEnvelope(cipher.encryptToText("un-secret-de-plus-de-32-octets!!", AAD))).isTrue();
        }
    }

    @Nested
    @DisplayName("Trousseau et rotation")
    class Keyring {

        @Test
        @DisplayName("Une enveloppe v1 reste lisible après bascule sur v2, et les nouvelles écritures portent v2")
        void oldEnvelopesStayReadableAfterRotation() {
            String secret = "secret-partenaire-de-32-octets!!";
            String underV1 = cipherV1().encryptToText(secret, AAD);

            SecretCipher rotated = new SecretCipher("v1:" + V1 + ",v2:" + V2, "v2");

            assertThat(rotated.activeKeyId()).isEqualTo("v2");
            assertThat(rotated.decryptFromText(underV1, AAD)).isEqualTo(secret);
            assertThat(rotated.encryptToText(secret, AAD)).startsWith("esc:1:v2:");
        }

        @Test
        @DisplayName("Réécrire l'identifiant de clé dans l'en-tête est rejeté : l'en-tête entre dans l'AAD")
        void rewrittenKeyIdInHeaderIsRejected() {
            String secret = "secret-partenaire-de-32-octets!!";
            SecretCipher rotated = new SecretCipher("v1:" + V1 + ",v2:" + V2, "v2");
            String underV1 = cipherV1().encryptToText(secret, AAD);

            // Sans en-tête authentifié, cette ligne se ferait passer pour « déjà sous
            // la clé active » : le runner ne la pivoterait jamais, la requête de
            // contrôle du runbook la compterait comme migrée, et retirer v1 la
            // rendrait définitivement illisible. Le tag GCM doit donc échouer ici.
            String forged = underV1.replaceFirst("^esc:1:v1:", "esc:1:v2:");

            assertThatThrownBy(() -> rotated.decryptFromText(forged, AAD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("tag GCM");
        }

        @Test
        @DisplayName("keyIdOf rend l'identifiant inscrit dans l'enveloppe (ce que le runner compare à la clé active)")
        void keyIdOfReadsTheEnvelopeHeader() {
            SecretCipher cipher = cipherV1();

            assertThat(cipher.keyIdOf(cipher.encryptToText("secret-de-32-octets-au-moins!!!!", AAD))).isEqualTo("v1");
        }

        @Test
        @DisplayName("Clé retirée du trousseau -> erreur NOMMANT l'identifiant introuvable")
        void missingKeyNamesTheKeyId() {
            String underV1 = cipherV1().encryptToText("secret-de-32-octets-au-moins!!!!", AAD);
            SecretCipher withoutV1 = new SecretCipher("v2:" + V2, "v2");

            assertThatThrownBy(() -> withoutV1.decryptFromText(underV1, AAD))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("v1")
                    .hasMessageContaining("ESCROW_CRYPTO_KEYS");
        }

        @Test
        @DisplayName("Trousseau à une seule clé -> l'identifiant actif est déduit (déploiement initial)")
        void singleKeyInfersTheActiveKeyId() {
            assertThat(new SecretCipher("v1:" + V1, "").activeKeyId()).isEqualTo("v1");
        }
    }

    @Nested
    @DisplayName("Configurations invalides : le démarrage est refusé en nommant la variable")
    class InvalidConfiguration {

        @Test
        @DisplayName("Trousseau absent ou vide")
        void missingKeyring() {
            assertThatThrownBy(() -> new SecretCipher(null, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CRYPTO_KEYS");
            assertThatThrownBy(() -> new SecretCipher("   ", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CRYPTO_KEYS");
        }

        @Test
        @DisplayName("Clé de longueur incorrecte (16 octets = AES-128 silencieux)")
        void keyOfWrongLength() {
            String tooShort = Base64.getEncoder().encodeToString(new byte[16]);

            assertThatThrownBy(() -> new SecretCipher("v1:" + tooShort, "v1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CRYPTO_KEYS")
                    .hasMessageContaining("v1")
                    .hasMessageContaining("32");
        }

        @Test
        @DisplayName("Entrée mal formée, base64 invalide, identifiant hors charset ou dupliqué")
        void malformedEntries() {
            assertThatThrownBy(() -> new SecretCipher("sans-separateur", ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CRYPTO_KEYS");
            assertThatThrownBy(() -> new SecretCipher("v1:pas du base64 !", "v1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64");
            assertThatThrownBy(() -> new SecretCipher("v 1:" + V1, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("v 1");
            assertThatThrownBy(() -> new SecretCipher("v1:" + V1 + ",v1:" + V2, "v1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("deux fois");
        }

        @Test
        @DisplayName("Identifiant actif hors trousseau")
        void activeKeyIdOutsideKeyring() {
            assertThatThrownBy(() -> new SecretCipher("v1:" + V1, "v9"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CRYPTO_ACTIVE_KEY_ID")
                    .hasMessageContaining("v9");
        }

        @Test
        @DisplayName("Plusieurs clés sans identifiant actif -> refus (le sens de la rotation ne se devine pas)")
        void multipleKeysWithoutActiveKeyId() {
            assertThatThrownBy(() -> new SecretCipher("v1:" + V1 + ",v2:" + V2, ""))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ESCROW_CRYPTO_ACTIVE_KEY_ID");
        }

        @Test
        @DisplayName("Une virgule finale ou des espaces de mise en forme restent tolérés")
        void formattingIsTolerated() {
            assertThatCode(() -> new SecretCipher(" v1:" + V1 + " , ", " v1 "))
                    .doesNotThrowAnyException();
        }
    }
}
