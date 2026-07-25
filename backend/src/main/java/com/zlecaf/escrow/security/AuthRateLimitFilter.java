package com.zlecaf.escrow.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.service.AuditService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Garde anti-bruteforce des endpoints d'authentification (Story 1.3, NFR-P2).
 *
 * <p>Ne couvre QUE {@code POST /api/v1/auth/login} et {@code /register} : tout
 * le reste de l'API est derrière JWT. Origine = adresse IP remote — la prise en
 * compte de X-Forwarded-For exige un proxy de confiance configuré (reverse
 * proxy TLS de la Story 1.4 / stack 11.3) ; sans lui l'en-tête est spoofable et
 * offrirait un contournement trivial.
 *
 * <p>Verrouillé : 429 + Retry-After + enveloppe d'erreur standard, et
 * l'événement est audité (AC1). Sinon : la requête passe, puis l'issue est
 * enregistrée (2xx = succès, >= 400 = échec) pour alimenter le compteur.
 */
@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/auth/login";
    private static final String REGISTER_PATH = "/api/v1/auth/register";

    private final AuthRateLimiter rateLimiter;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public AuthRateLimitFilter(AuthRateLimiter rateLimiter, AuditService auditService, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !LOGIN_PATH.equals(path) && !REGISTER_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getRequestURI() + "|" + request.getRemoteAddr();

        long retryAfter = rateLimiter.retryAfterSeconds(key);
        if (retryAfter > 0) {
            auditService.recordAuthRateLimited(request.getRequestURI(), request.getRemoteAddr());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(Math.max(retryAfter, 1)));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            ObjectNode body = objectMapper.createObjectNode();
            body.put("code", ErrorCode.RATE_LIMITED.name());
            body.put("message", "Trop de tentatives. Réessayez dans " + Math.max(retryAfter, 1) + " s.");
            body.put("retryAfterSeconds", Math.max(retryAfter, 1));
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }

        chain.doFilter(request, response);

        int status = response.getStatus();
        if (status >= 200 && status < 300) {
            rateLimiter.recordSuccess(key);
        } else if (status >= 400) {
            rateLimiter.recordFailure(key);
        }
    }
}
