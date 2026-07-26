package com.zlecaf.escrow.security;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;

import jakarta.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Politique de robustesse des mots de passe (Story 1.6, NFR-P5).
 *
 * <p>Autorité UNIQUE de la règle, appelée à l'inscription ET au changement de mot
 * de passe (et plus tard au reset, Story 2.6) — jamais dupliquée par endpoint. Un
 * mot de passe non conforme lève une {@link BadRequestException} portant
 * {@link ErrorCode#WEAK_PASSWORD} et un message qui <b>énumère les règles</b> :
 * c'est un endpoint public (pas d'anti-énumération ici, contrairement au login).
 *
 * <p><b>Longueur mesurée en OCTETS UTF-8, pas en caractères</b> (revue 1.6).
 * BCrypt tronque silencieusement au-delà de 72 <i>octets</i>. Compter les
 * caractères laissait grande ouverte la faille que cette borne existe pour
 * fermer : un mot de passe accentué de 56 caractères pèse 96 octets, bcrypt n'en
 * hache que les 72 premiers, et <b>n'importe quel suffixe authentifie ensuite</b>
 * — vérifié expérimentalement pendant la revue. {@code String.length()} compte
 * des unités UTF-16 ; seul {@code getBytes(UTF_8).length} correspond à ce que
 * bcrypt consomme réellement.
 *
 * <p>Le message reste en anglais comme tous ses voisins ({@code "Email already
 * registered"}, {@code "User not found"}) : le backend n'émet pas de texte
 * localisé, le libellé utilisateur est porté par le frontend (AD-23).
 *
 * <p>Complexité : au moins {@code min-categories} des 4 catégories {minuscule,
 * majuscule, chiffre, symbole}. Paramétrable par {@code escrow.auth.password.*}.
 */
@Component
public class PasswordPolicy {

    /** Plafond dur : au-delà, bcrypt ignore silencieusement le suffixe. Non configurable. */
    private static final int BCRYPT_MAX_BYTES = 72;

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
     * Échoue au DÉMARRAGE sur une configuration incohérente (revue 1.6). Sans ce
     * garde-fou, {@code MIN_CATEGORIES=5} rejetait tout mot de passe existant —
     * inscription morte, aucune erreur au boot, message absurde « au moins 5 des 4
     * catégories » — et {@code MIN_LENGTH=1} désactivait silencieusement toute la
     * story. Depuis que {@code @Size(min=6)} a quitté le DTO, ces trois valeurs
     * sont le SEUL plancher de robustesse : la faute de frappe d'un exploitant ne
     * doit pouvoir devenir ni une panne muette ni un affaiblissement muet.
     */
    @PostConstruct
    void validateConfiguration() {
        if (minCategories < 1 || minCategories > 4) {
            throw new IllegalStateException(
                    "escrow.auth.password.min-categories must be within [1,4], got " + minCategories);
        }
        if (minLength < 1) {
            throw new IllegalStateException(
                    "escrow.auth.password.min-length must be >= 1, got " + minLength);
        }
        if (maxLength > BCRYPT_MAX_BYTES) {
            throw new IllegalStateException("escrow.auth.password.max-length must be <= " + BCRYPT_MAX_BYTES
                    + " (bcrypt truncates silently beyond that), got " + maxLength);
        }
        if (minLength > maxLength) {
            throw new IllegalStateException("escrow.auth.password.min-length (" + minLength
                    + ") must not exceed max-length (" + maxLength + ")");
        }
    }

    /**
     * Valide {@code raw} contre la politique. Ne fait rien si conforme ; lève une
     * {@link BadRequestException} {@code WEAK_PASSWORD} au message actionnable sinon.
     */
    public void validate(String raw) {
        if (raw == null) {
            throw new BadRequestException(ErrorCode.WEAK_PASSWORD, describeRules());
        }
        int bytes = raw.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < minLength || bytes > maxLength || categories(raw) < minCategories) {
            throw new BadRequestException(ErrorCode.WEAK_PASSWORD, describeRules());
        }
    }

    /**
     * Nombre de catégories distinctes présentes parmi {minuscule, majuscule, chiffre,
     * symbole}. Espaces et caractères de contrôle ne comptent pour AUCUNE catégorie
     * (revue 1.6) : le fourre-tout {@code else} d'origine les classait « symbole »,
     * si bien qu'ajouter une espace à un mot en minuscules suffisait à franchir le
     * seuil « 3 des 4 ». Ils restent autorisés dans le mot de passe — ils ne
     * comptent simplement pas comme de la complexité.
     */
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
            } else if (!Character.isWhitespace(c) && !Character.isISOControl(c)) {
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
        return "Password must be between " + minLength + " and " + maxLength
                + " bytes (UTF-8) and contain at least " + minCategories
                + " of the 4 categories: lowercase, uppercase, digit, symbol.";
    }
}
