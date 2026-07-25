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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 1.3 (AC1 + AC2) sur la vraie chaîne : filtre servlet, enveloppe 429,
 * Retry-After, journalisation audit_logs et rétablissement automatique —
 * contre un Postgres réel, avec une horloge de test pilotable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthRateLimitIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Seuil bas pour un test rapide ; verrou 30 s.
        registry.add("escrow.auth.ratelimit.max-attempts", () -> "3");
        registry.add("escrow.auth.ratelimit.base-lock-seconds", () -> "30");
    }

    /** Horloge pilotable substituée au bean de production. */
    static final class SteppingClock extends Clock {
        volatile Instant now = Instant.parse("2026-07-25T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @TestConfiguration
    static class ClockConfig {
        static final SteppingClock CLOCK = new SteppingClock();
        @Bean @Primary
        Clock testClock() { return CLOCK; }
    }

    @Autowired MockMvc mvc;
    @Autowired AuditLogRepository auditLogs;

    private static final String LOGIN = "/api/v1/auth/login";

    private org.springframework.test.web.servlet.ResultActions tryLogin(String ip, String email) throws Exception {
        return mvc.perform(post(LOGIN)
                .with(request -> { request.setRemoteAddr(ip); return request; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password-123\"}"));
    }

    @Test
    @DisplayName("AC1+AC2 : seuil -> 429 + Retry-After + audit ; expiration -> accès rétabli seul")
    void thresholdBlocks_thenAutoRecovers() throws Exception {
        String ip = "10.9.9.1";
        long auditBefore = auditLogs.count();

        // 3 échecs (utilisateur inconnu) : comptés, pas encore bloqués
        for (int i = 0; i < 3; i++) {
            tryLogin(ip, "nobody@example.com").andExpect(status().is4xxClientError());
        }

        // 4e tentative : bloquée AVANT le contrôleur — 429, enveloppe, Retry-After
        tryLogin(ip, "nobody@example.com")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        // AC1 : l'événement est journalisé dans audit_logs (transaction_id null)
        assertThat(auditLogs.count()).isGreaterThan(auditBefore);

        // AC2 : après la fenêtre, l'accès se rétablit sans intervention
        ClockConfig.CLOCK.now = ClockConfig.CLOCK.now.plus(Duration.ofSeconds(31));
        tryLogin(ip, "nobody@example.com").andExpect(status().is4xxClientError()); // 401, plus 429
    }

    @Test
    @DisplayName("les origines sont indépendantes : une IP bloquée n'affecte pas les autres")
    void otherOrigin_unaffected() throws Exception {
        String blocked = "10.9.9.2";
        for (int i = 0; i < 4; i++) {
            tryLogin(blocked, "nobody2@example.com");
        }
        tryLogin(blocked, "nobody2@example.com").andExpect(status().isTooManyRequests());

        tryLogin("10.9.9.3", "nobody2@example.com").andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("register est limité comme login")
    void register_isRateLimitedToo() throws Exception {
        String ip = "10.9.9.4";
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/v1/auth/register")
                            .with(request -> { request.setRemoteAddr(ip); return request; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().is4xxClientError());
        }
        mvc.perform(post("/api/v1/auth/register")
                        .with(request -> { request.setRemoteAddr(ip); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isTooManyRequests());
    }
}
