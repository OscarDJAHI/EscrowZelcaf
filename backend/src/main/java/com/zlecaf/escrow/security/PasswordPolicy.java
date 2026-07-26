package com.zlecaf.escrow.security;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Politique de robustesse des mots de passe (Story 1.6, NFR-P5).
 *
 * <p>Autorité UNIQUE de la règle, appelée à l'inscription ET au changement de mot
 * de passe (et plus tard au reset, Story 2.6) — jamais dupliquée par endpoint. Un
 * mot de passe non conforme lève une {@link BadRequestException} portant
 * {@link ErrorCode#WEAK_PASSWORD} et un message qui <b>énumère les règles</b> :
 * c'est un endpoint public (pas d'anti-énumération ici, contrairement au login).
 *
 * <p><b>Longueur maximale = limite bcrypt.</b> BCrypt tronque silencieusement à 72
 * octets ; borner la longueur évite qu'un suffixe soit ignoré (fausse robustesse).
 *
 * <p>Complexité : au moins {@code min-categories} des 4 catégories {minuscule,
 * majuscule, chiffre, symbole}. Paramétrable par {@code escrow.auth.password.*}.
 */
@Component
public class PasswordPolicy {

    private final int minLength;
    private final int maxLength;
    private final int minCategories;

    public PasswordPolicy(
            @Value("${escrow.auth.password.min-length:12}") int minLength,
            @Value("${escrow.auth.password.max-length:72}") int maxLength,
            @Value("${escrow.auth.password.min-categories:3}") int minCategories) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.minCategories = minCategories;
    }

    /**
     * Valide {@code raw} contre la politique. Ne fait rien si conforme ; lève une
     * {@link BadRequestException} {@code WEAK_PASSWORD} au message actionnable sinon.
     */
    public void validate(String raw) {
        if (raw == null || raw.length() < minLength || raw.length() > maxLength || categories(raw) < minCategories) {
            throw new BadRequestException(ErrorCode.WEAK_PASSWORD, describeRules());
        }
    }

    /** Nombre de catégories distinctes présentes parmi {minuscule, majuscule, chiffre, symbole}. */
    private int categories(String raw) {
        boolean lower = false, upper = false, digit = false, symbol = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLowerCase(c)) {
                lower = true;
            } else if (Character.isUpperCase(c)) {
                upper = true;
            } else if (Character.isDigit(c)) {
                digit = true;
            } else {
                symbol = true;
            }
        }
        int count = 0;
        if (lower) count++;
        if (upper) count++;
        if (digit) count++;
        if (symbol) count++;
        return count;
    }

    private String describeRules() {
        return "Le mot de passe doit contenir entre " + minLength + " et " + maxLength
                + " caractères et au moins " + minCategories
                + " des 4 catégories suivantes : minuscule, majuscule, chiffre, symbole.";
    }
}
