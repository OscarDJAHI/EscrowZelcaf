package com.zlecaf.escrow.security;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.service.AuditService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Garde anti-bruteforce des endpoints d'authentification (Story 1.3, NFR-P2).
 *
 * <p>Couvre {@code POST /api/v1/auth/login} et {@code /register}. Le chemin est
 * comparé sur sa forme <b>décodée et normalisée</b> ({@link UrlPathHelper}) —
 * une variante encodée ({@code %6cogin}) ou à paramètres de matrice
 * ({@code login;x=y}) route vers le contrôleur mais ne peut plus esquiver le
 * filtre.
 *
 * <p><b>Ordre</b> : après la chaîne Spring Security (déclaré explicitement) — le
 * comptage se fait sur la réponse applicative réelle, pas sur un rejet de
 * sécurité.
 *
 * <p>Clés : une clé <b>origine</b> par IP (IPv6 agrégée en /64), partagée entre
 * login et register (budget commun), et pour le login une clé <b>compte</b>
 * (email). Bloqué → 429 + {@code Retry-After} + enveloppe d'erreur standard, et
 * l'événement est audité <b>une seule fois, au franchissement du seuil</b>.
 *
 * <p>Signal d'échec : seule une vraie issue d'authentification compte (401
 * identifiants invalides, 409 conflit/énumération au register). Les 400 de
 * validation et les 5xx d'incident serveur ne verrouillent personne.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REGISTER_PATH = "/api/v1/auth/register";
    private static final int MAX_BODY_BYTES = 8192;

    private final AuthRateLimiter rateLimiter;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final UrlPathHelper pathHelper = new UrlPathHelper();

    public AuthRateLimitFilter(AuthRateLimiter rateLimiter, AuditService auditService, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.pathHelper.setUrlDecode(true);
        this.pathHelper.setRemoveSemicolonContent(true);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = pathHelper.getPathWithinApplication(request);
        return !LOGIN_PATH.equals(path) && !REGISTER_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = pathHelper.getPathWithinApplication(request);
        boolean isLogin = LOGIN_PATH.equals(path);

        String originKey = "origin|" + normalizeIp(request.getRemoteAddr());

        // Corps mis en cache pour lire l'email (login) puis le rejouer au contrôleur.
        HttpServletRequest effectiveRequest = request;
        String accountKey = null;
        if (isLogin) {
            CachedBodyRequestWrapper cached = new CachedBodyRequestWrapper(request, MAX_BODY_BYTES);
            effectiveRequest = cached;
            String email = extractEmail(cached.body());
            if (email != null) {
                accountKey = "account|" + email.trim().toLowerCase();
            }
        }

        String[] keys = accountKey == null ? new String[]{originKey} : new String[]{originKey, accountKey};

        long retryAfter = rateLimiter.maxRetryAfter(keys);
        if (retryAfter > 0) {
            writeTooManyRequests(response, retryAfter);
            return;
        }

        chain.doFilter(effectiveRequest, response);

        int status = response.getStatus();
        if (isLogin && status >= 200 && status < 300) {
            // Succès prouvé : on efface la salve du COMPTE (jamais celle de l'origine).
            if (accountKey != null) {
                rateLimiter.recordAccountSuccess(accountKey);
            }
        } else if (isAuthFailure(status)) {
            boolean originLocked = rateLimiter.recordFailure(originKey);
            boolean accountLocked = accountKey != null && rateLimiter.recordFailure(accountKey);
            if (originLocked || accountLocked) {
                auditLockOnce(path, request.getRemoteAddr());
            }
        }
    }

    /**
     * Vrai échec d'authentification. On compte les identifiants invalides (401)
     * et les conflits/énumération au register (409). Volontairement PAS les 400
     * (validation d'entrée : ne verrouille pas un onboarding honnête sur une
     * typo) ni les 5xx (incident serveur : ne punit pas des clients innocents).
     */
    private boolean isAuthFailure(int status) {
        return status == HttpStatus.UNAUTHORIZED.value()
                || status == HttpStatus.CONFLICT.value();
    }

    private String extractEmail(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode email = node.get("email");
            return email != null && email.isTextual() ? email.asText() : null;
        } catch (IOException malformedJson) {
            return null; // corps illisible : on limite par origine seulement
        }
    }

    /**
     * Normalise l'adresse pour la clé : une IPv6 est ramenée à son préfixe /64
     * (sinon un attaquant disposant d'un /64 changerait d'adresse à chaque essai) ;
     * une IPv4 est gardée telle quelle.
     */
    private String normalizeIp(String remoteAddr) {
        if (remoteAddr == null) {
            return "unknown";
        }
        try {
            InetAddress addr = InetAddress.getByName(remoteAddr);
            byte[] bytes = addr.getAddress();
            if (bytes.length == 16) {
                StringBuilder prefix = new StringBuilder("v6/64:");
                for (int i = 0; i < 8; i++) {
                    prefix.append(String.format("%02x", bytes[i]));
                }
                return prefix.toString();
            }
            return remoteAddr;
        } catch (UnknownHostException e) {
            return remoteAddr;
        }
    }

    private void auditLockOnce(String path, String clientIp) {
        try {
            auditService.recordAuthRateLimited(path, clientIp);
        } catch (RuntimeException auditFailure) {
            // L'audit ne doit jamais empêcher la protection : un incident DB ne
            // transforme pas le 429 en 500. Le blocage reste en mémoire.
            logger.warn("Échec de journalisation d'un verrou anti-bruteforce", auditFailure);
        }
    }

    private void writeTooManyRequests(HttpServletResponse response, long retryAfter) throws IOException {
        long seconds = Math.max(retryAfter, 1);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> envelope = Map.of(
                "timestamp", Instant.now().toString(),
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "error", HttpStatus.TOO_MANY_REQUESTS.getReasonPhrase(),
                "code", ErrorCode.RATE_LIMITED.name(),
                "message", "Too many attempts. Retry after " + seconds + " seconds.",
                "retryAfterSeconds", seconds);
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }
}
