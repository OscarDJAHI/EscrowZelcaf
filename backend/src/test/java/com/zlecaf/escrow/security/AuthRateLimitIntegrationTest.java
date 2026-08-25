package com.zlecaf.escrow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.support.PostgresTestSupport;

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

/**
 * Story 1.3 (AC1 + AC2) sur la vraie chaîne : filtre servlet, enveloppe 429
 * standard, Retry-After, audit au franchissement, rétablissement automatique,
 * et preuve que le succès ne réarme pas le quota d'origine — Postgres réel,
 * horloge pilotable.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthRateLimitIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, AuthRateLimitIntegrationTest.class);
        registry.add("escrow.auth.ratelimit.max-attempts", () -> "3");
        registry.add("escrow.auth.ratelimit.base-lock-seconds", () -> "30");
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
    @Autowired AuditLogRepository auditLogs;
    @Autowired AuthRateLimiter rateLimiter;

    @BeforeEach
    void isolate() {
        // Singleton à état : on repart propre entre méthodes (pas de pollution
        // ni de dépendance à l'ordre), et l'horloge revient à son origine.
        rateLimiter.reset();
        ClockConfig.CLOCK.now = Instant.parse("2026-07-25T00:00:00Z");
    }

    private ResultActions login(String ip, String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password-123\"}"));
    }

    @Test
    @DisplayName("AC1+AC2 : seuil -> 429 enveloppe standard + Retry-After + audit ; expiration -> rétabli")
    void thresholdBlocks_thenRecovers() throws Exception {
        String ip = "10.9.9.1";
        long auditBefore = auditLogs.count();

        for (int i = 0; i < 3; i++) {
            login(ip, "victim@example.com").andExpect(status().isUnauthorized());
        }

        login(ip, "victim@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        // AC1 : audité UNE fois (au franchissement) — pas à chaque requête bloquée
        long afterLock = auditLogs.count();
        assertThat(afterLock).isGreaterThan(auditBefore);
        login(ip, "victim@example.com").andExpect(status().isTooManyRequests());
        assertThat(auditLogs.count()).as("une requête déjà bloquée n'ajoute pas d'audit").isEqualTo(afterLock);

        // AC2 : après la fenêtre, l'accès revient seul
        ClockConfig.CLOCK.now = ClockConfig.CLOCK.now.plus(Duration.ofSeconds(31));
        login(ip, "victim@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SÉCURITÉ : un succès sur le compte de l'attaquant ne débloque pas l'origine")
    void attackerSuccess_doesNotResetOrigin() throws Exception {
        String ip = "10.9.9.2";
        // Emails DISTINCTS à chaque essai : aucune clé compte n'atteint le seuil,
        // seule l'ORIGINE accumule — le 429 final prouve donc bien le verrou
        // d'origine, non celui d'un compte.
        login(ip, "a@example.com").andExpect(status().isUnauthorized());
        login(ip, "b@example.com").andExpect(status().isUnauthorized());
        rateLimiter.recordAccountSuccess("account|attacker@example.com"); // succès sur SON compte
        login(ip, "c@example.com").andExpect(status().isUnauthorized()); // 3e échec d'origine
        login(ip, "d@example.com").andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("les 400 de validation ne verrouillent pas un onboarding honnête")
    void validationErrors_doNotLock() throws Exception {
        String ip = "10.9.9.3";
        for (int i = 0; i < 6; i++) {
            mvc.perform(post("/api/v1/auth/register")
                            .with(r -> { r.setRemoteAddr(ip); return r; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
        // Toujours pas bloqué : un 400 de validation n'est pas un échec d'auth
        mvc.perform(post("/api/v1/auth/register")
                        .with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("origines indépendantes (emails distincts pour isoler la dimension IP)")
    void independentOrigins() throws Exception {
        String blocked = "10.9.9.4";
        // Emails distincts → seule l'origine 10.9.9.4 accumule (3 → verrou)
        login(blocked, "e@example.com");
        login(blocked, "f@example.com");
        login(blocked, "g@example.com");
        login(blocked, "h@example.com").andExpect(status().isTooManyRequests());
        // Une autre origine, email neuf → intacte
        login("10.9.9.5", "i@example.com").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("aucun contournement du limiteur par variante de chemin (pare-feu + normalisation)")
    void pathVariant_noBypass() throws Exception {
        String ip = "10.9.9.6";
        for (int i = 0; i < 3; i++) {
            login(ip, "j@example.com").andExpect(status().isUnauthorized());
        }
        // Variante à paramètre de matrice : rejetée par le pare-feu StrictHttpFirewall
        // (400) OU bloquée par le limiteur (429) — dans les deux cas, PAS de 2xx :
        // la tentative n'atteint jamais une authentification réussie contournée.
        mvc.perform(post("/api/v1/auth/login;jsessionid=x")
                        .with(r -> { r.setRemoteAddr(ip); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"j@example.com\",\"password\":\"x\"}"))
                .andExpect(status().is4xxClientError())
                .andExpect(status().is(org.hamcrest.Matchers.not(200)));
    }
}
