package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

/**
 * Story 1.6 (NFR-P5) — politique de robustesse des mots de passe. Test unitaire
 * pur (défauts : min 12, max 72, ≥ 3 catégories sur 4).
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy(12, 72, 3);

    @ParameterizedTest(name = "accepté : {0}")
    @ValueSource(strings = {
            "Str0ng!Passw0rd",            // 4 catégories
            "Abcdefgh1234",               // 3 catégories (maj, min, chiffre), 12 pile
            "Another Good 9!",            // min+maj+chiffre+symbole (l'espace ne compte pas)
    })
    @DisplayName("un mot de passe conforme ne lève rien")
    void strongPasswordsAccepted(String raw) {
        assertThatCode(() -> policy.validate(raw)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "rejeté : {0}")
    @ValueSource(strings = {
            "Short1!",                    // trop court (< 12)
            "password",                   // 1 catégorie
            "passwordlong",               // 1 catégorie (que des minuscules)
            "password1234",               // 2 catégories (min + chiffre)
            "PASSWORD1234",               // 2 catégories (maj + chiffre)
    })
    @DisplayName("un mot de passe non conforme lève WEAK_PASSWORD avec les règles")
    void weakPasswordsRejected(String raw) {
        assertThatThrownBy(() -> policy.validate(raw))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bytes")
                .hasMessageContaining("categories");
    }

    @Test
    @DisplayName("null est rejeté (pas de NPE)")
    void nullRejected() {
        assertThatThrownBy(() -> policy.validate(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("au-delà de la longueur max (troncature bcrypt) -> rejeté")
    void overMaxLengthRejected() {
        String tooLong = "Aa1!".repeat(20); // 80 caractères > 72
        assertThatThrownBy(() -> policy.validate(tooLong)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("revue 1.6 : la borne est en OCTETS UTF-8, pas en caractères — bcrypt tronque en octets")
    void overMaxBytesRejectedEvenWhenUnder72Chars() {
        // 8 x "Ééàèù1!" = 56 caractères mais 96 octets UTF-8. La borne en caractères
        // l'acceptait ; bcrypt n'en hachait alors que les 72 premiers OCTETS et tout
        // suffixe authentifiait ensuite — la faille exacte que cette borne existe
        // pour fermer, reproduite pendant la revue.
        String accented = "Ééàèù1!".repeat(8);
        assertThat(accented.length()).isLessThanOrEqualTo(72);
        assertThat(accented.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

        assertThatThrownBy(() -> policy.validate(accented)).isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("revue 1.6 : un mot de passe accentué qui TIENT dans 72 octets reste accepté")
    void accentedPasswordWithinByteBudgetAccepted() {
        String accented = "Éàü1!Motdepasse"; // 15 car., conforme en octets
        assertThat(accented.getBytes(StandardCharsets.UTF_8).length).isBetween(12, 72);
        assertThatCode(() -> policy.validate(accented)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("revue 1.6 : une espace ne compte pour aucune catégorie de complexité")
    void whitespaceIsNotAComplexityCategory() {
        // Avant : le fourre-tout `else` classait l'espace en « symbole », si bien
        // qu'ajouter une espace à un mot en minuscules franchissait le seuil « 3 des
        // 4 ». minuscules + chiffre + espace = 2 catégories réelles -> rejeté.
        assertThatThrownBy(() -> policy.validate("motdepasse 12"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @DisplayName("revue 1.6 : une configuration incohérente échoue au DÉMARRAGE, pas en silence")
    void invalidConfigurationFailsFast() {
        // min-categories = 5 : categories() plafonne à 4, donc TOUT mot de passe
        // était rejeté — inscription morte, aucune erreur au boot, et le message
        // absurde « au moins 5 des 4 catégories » comme seul indice.
        assertThatThrownBy(() -> new PasswordPolicy(12, 72, 5).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-categories");

        // min > max : rejette également tout mot de passe, en silence.
        assertThatThrownBy(() -> new PasswordPolicy(80, 72, 3).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("min-length");

        // max au-delà de la limite bcrypt : ré-ouvrirait la troncature silencieuse.
        assertThatThrownBy(() -> new PasswordPolicy(12, 100, 3).validateConfiguration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-length");

        assertThatCode(() -> new PasswordPolicy(12, 72, 3).validateConfiguration())
                .doesNotThrowAnyException();
    }
}
