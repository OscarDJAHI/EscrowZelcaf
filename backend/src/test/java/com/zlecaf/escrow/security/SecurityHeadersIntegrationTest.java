package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 1.4 (NFR-P3) — en-têtes de sécurité posés côté Spring (défense en
 * profondeur) et reconnaissance des en-têtes du reverse-proxy TLS.
 *
 * <p>Même harnais que {@code AuthRateLimitIntegrationTest} : MockMvc + Postgres
 * réel (Testcontainers) + horloge figée. MockMvc applique la vraie chaîne de
 * filtres du contexte, dont le {@code ForwardedHeaderFilter} activé par
 * {@code server.forward-headers-strategy: framework} — on peut donc éprouver la
 * dérivation du schéma (X-Forwarded-Proto) et de l'IP client (X-Forwarded-For)
 * sans le coût/instabilité d'un vrai serveur HTTP.
 *
 * <ul>
 *   <li><b>AC #2</b> : CSP, X-Frame-Options, nosniff, Referrer-Policy sur les réponses.</li>
 *   <li><b>AC #1</b> : HSTS servi quand la requête d'origine est HTTPS (X-Forwarded-Proto).</li>
 *   <li><b>AC #3 (report DEF2)</b> : derrière le proxy, le rate-limiter (Story 1.3)
 *       clé sur l'IP client réelle (X-Forwarded-For), pas sur l'IP du proxy.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SecurityHeadersIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Seuil bas pour prouver le verrou de façon déterministe (AC #3).
        registry.add("escrow.auth.ratelimit.max-attempts", () -> "3");
    }

    static final class SteppingClock extends Clock {
        volatile Instant now = Instant.parse("2026-07-25T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @TestConfiguration
    static class ClockConfig {
        static final SteppingClock CLOCK = new SteppingClock();
        @Bean @Primary Clock testClock() { return CLOCK; }
    }

    @Autowired MockMvc mvc;
    @Autowired AuthRateLimiter rateLimiter;

    @BeforeEach
    void isolate() {
        // Singleton à état : on repart propre entre méthodes ; horloge à l'origine.
        rateLimiter.reset();
        ClockConfig.CLOCK.now = Instant.parse("2026-07-25T00:00:00Z");
    }

    @Test
    @DisplayName("AC#2 : CSP, X-Frame-Options, nosniff et Referrer-Policy présents sur les réponses")
    void staticSecurityHeadersPresent() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'self'")));
    }

    @Test
    @DisplayName("AC#1 : HSTS servi quand la requête d'origine est HTTPS (X-Forwarded-Proto)")
    void hstsEmittedBehindHttpsProxy() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Forwarded-Proto", "https"))
                .andExpect(header().string("Strict-Transport-Security",
                        org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("max-age=31536000"),
                                org.hamcrest.Matchers.containsString("includeSubDomains"))));
    }

    @Test
    @DisplayName("AC#1 : pas de HSTS sur une requête HTTP en clair (non forwardée en https)")
    void hstsAbsentOnPlainHttp() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }

    // NB : emails DISTINCTS à chaque essai (comme AuthRateLimitIntegrationTest) pour
    // isoler la dimension ORIGINE — sinon la clé COMPTE (email) verrouille au seuil
    // indépendamment de l'IP et masquerait ce qu'on veut prouver ici (le keying XFF).

    @Test
    @DisplayName("AC#3/DEF2 : la clé n'est PAS l'IP du proxy — clients XFF distincts non verrouillés")
    void distinctForwardedClients_notCollectivelyLocked() throws Exception {
        // MÊME IP de proxy (remoteAddr) pour 5 requêtes, mais 5 IP client XFF
        // distinctes (+ emails distincts). Si le limiteur clait sur l'IP du proxy,
        // la 4e serait 429. Elles restent toutes 401 => la clé = l'IP client XFF.
        for (int i = 1; i <= 5; i++) {
            failingLogin("10.200.0.1", "203.0.113." + i, "user" + i + "@example.com")
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("AC#3/DEF2 : la clé EST l'IP client XFF — même client verrouillé malgré des proxys distincts")
    void sameForwardedClient_getsLocked() throws Exception {
        String client = "203.0.113.200";
        // IP de proxy DIFFÉRENTE à chaque essai (+ emails distincts) : seule l'IP
        // client XFF est constante. Le verrou au 4e prouve donc le keying sur XFF,
        // pas sur la socket du proxy ni sur le compte.
        for (int i = 0; i < 3; i++) {
            failingLogin("10.200.0." + (10 + i), client, "acct" + i + "@example.com")
                    .andExpect(status().isUnauthorized());
        }
        failingLogin("10.200.0.99", client, "acct-final@example.com")
                .andExpect(status().isTooManyRequests());
    }

    private ResultActions failingLogin(String proxyRemoteAddr, String forwardedForClientIp, String email)
            throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .with(r -> { r.setRemoteAddr(proxyRemoteAddr); return r; })
                .header("X-Forwarded-For", forwardedForClientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password-123\"}"));
    }
}
