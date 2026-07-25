package com.zlecaf.escrow.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Politique d'allowlist CORS (Story 1.5, NFR-P4).
 *
 * <p>Source UNIQUE partagée par {@link SecurityConfig} (qui construit la
 * {@code CorsConfiguration}) et {@link ProductionApiSurfaceGuard} (qui refuse le
 * démarrage sous profil {@code prod}) : sans elle, la validation et la
 * consommation de la liste divergeraient et le garde pourrait laisser passer une
 * valeur que la configuration interprète autrement.
 *
 * <p>Volontairement sans dépendance Spring : testable unitairement, sans contexte.
 */
public final class CorsOriginPolicy {

    /** Variable d'environnement nommée dans les messages d'échec (pattern Story 1.2). */
    static final String ENV_VAR = "ESCROW_CORS_ALLOWED_ORIGINS";

    private static final String HTTP = "http://";
    private static final String HTTPS = "https://";

    private CorsOriginPolicy() {
    }

    /**
     * Découpe la valeur brute sur les virgules, retire les espaces autour de
     * chaque entrée et ignore les entrées vides (une virgule finale ou un
     * {@code ", "} de confort ne doit pas produire d'origine fantôme).
     *
     * @return liste immuable, vide si {@code raw} est {@code null} ou blanc
     *         (= allowlist non fournie, donc mode dev permissif)
     */
    public static List<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> origins = new ArrayList<>();
        for (String candidate : Arrays.asList(raw.split(","))) {
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) {
                origins.add(trimmed);
            }
        }
        return List.copyOf(origins);
    }

    /**
     * Valide une allowlist destinée au profil {@code prod}, où la comparaison est
     * EXACTE ({@code setAllowedOrigins}). Toute entrée que Spring ne pourra jamais
     * faire correspondre est un piège silencieux — une origine légitime serait
     * rejetée en 403 sans aucune trace au démarrage : on échoue donc au boot.
     *
     * @return la liste validée (pour chaînage)
     * @throws IllegalStateException si la liste est vide ou si une entrée est
     *         permissive ({@code *}) ou malformée ; le message nomme toujours
     *         {@link #ENV_VAR} et l'entrée fautive
     */
    public static List<String> requireValidProductionOrigins(List<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException(refus(
                    "aucune origine fournie. Renseigner la liste des origines navigateur autorisées, "
                            + "séparées par des virgules (ex. https://app.exemple.com,https://admin.exemple.com)."));
        }
        for (String origin : origins) {
            String problem = describeProblem(origin);
            if (problem != null) {
                throw new IllegalStateException(refus(
                        "entrée invalide « " + origin + " » : " + problem + "."));
            }
        }
        return origins;
    }

    /** @return le motif de rejet, ou {@code null} si l'origine est exploitable en comparaison exacte */
    private static String describeProblem(String origin) {
        if (origin.contains("*")) {
            // setAllowedOrigins ne connaît pas les jokers (c'est setAllowedOriginPatterns
            // qui les interprète) : un « https://*.exemple.com » ne matcherait JAMAIS.
            return "le joker '*' est inopérant en comparaison exacte et n'est pas admis en production";
        }
        // Un en-tête Origin navigateur ne contient jamais d'espace, de query ni de
        // fragment : de telles entrées passeraient le boot puis ne matcheraient JAMAIS
        // (403 silencieux en prod) — exactement le piège que cette classe élimine.
        for (int i = 0; i < origin.length(); i++) {
            if (Character.isWhitespace(origin.charAt(i))) {
                return "espace interdit — une origine ne contient aucun caractère d'espacement";
            }
        }
        if (origin.indexOf('?') >= 0) {
            return "query interdite ('?') — une origine se limite au scheme, à l'hôte et au port";
        }
        if (origin.indexOf('#') >= 0) {
            return "fragment interdit ('#') — une origine se limite au scheme, à l'hôte et au port";
        }
        String host;
        if (origin.startsWith(HTTPS)) {
            host = origin.substring(HTTPS.length());
        } else if (origin.startsWith(HTTP)) {
            host = origin.substring(HTTP.length());
        } else {
            return "scheme absent — une origine s'écrit http://hôte[:port] ou https://hôte[:port]";
        }
        if (host.isEmpty()) {
            return "hôte absent après le scheme";
        }
        if (origin.endsWith("/")) {
            // Le navigateur envoie un en-tête Origin SANS '/' final : « https://a.test/ »
            // ne correspondrait à rien.
            return "'/' final interdit — l'en-tête Origin du navigateur n'en porte jamais";
        }
        if (host.indexOf('/') >= 0) {
            return "chemin interdit — une origine se limite au scheme, à l'hôte et au port";
        }
        // Validation du port. Attention à l'IPv6 littéral (« [::1] », « [::1]:8443 ») :
        // les ':' internes aux crochets ne délimitent pas le port.
        String port;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            if (close < 0) {
                return "littéral IPv6 mal formé — crochet ']' manquant";
            }
            String afterBracket = host.substring(close + 1);
            if (afterBracket.isEmpty()) {
                return null; // IPv6 sans port
            }
            if (afterBracket.charAt(0) != ':') {
                return "caractère inattendu après le littéral IPv6";
            }
            port = afterBracket.substring(1);
        } else {
            int colon = host.lastIndexOf(':');
            if (colon < 0) {
                return null; // pas de port
            }
            port = host.substring(colon + 1);
        }
        if (port.isEmpty()) {
            return "port vide après ':'";
        }
        for (int i = 0; i < port.length(); i++) {
            if (!Character.isDigit(port.charAt(i))) {
                return "port non numérique — la portion après ':' doit être un nombre";
            }
        }
        return null;
    }

    private static String refus(String detail) {
        return "Démarrage refusé (profil prod) : " + ENV_VAR + " (escrow.api.cors-allowed-origins) — " + detail
                + " Aucun repli permissif n'existe en production (NFR-P4).";
    }
}
