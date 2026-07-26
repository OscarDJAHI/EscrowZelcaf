package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
            "another Good 9",             // min+maj+chiffre (l'espace = symbole -> 4)
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
                .hasMessageContaining("caractères")
                .hasMessageContaining("catégories");
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
}
