package com.zlecaf.escrow.web;

import com.zlecaf.escrow.service.storage.EvidenceStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proof that the partner {@code permitAll} matcher is pinned to exactly
 * {@code POST /api/v1/partner/escrow/*​/evidence} (Story 3.4, report #2). Boots the
 * full application context against a real Postgres so the actual
 * {@link com.zlecaf.escrow.config.SecurityConfig} filter chain is exercised.
 *
 * <p>Two contrasting outcomes prove the pin:
 * <ul>
 *   <li>Another, unauthenticated {@code /api/v1/partner/**} route is <em>blocked by
 *       security</em> (it now falls through to {@code anyRequest().authenticated()},
 *       no longer opened by a wildcard — under the old wildcard it reached the
 *       dispatcher and returned 404).</li>
 *   <li>The real deposit endpoint still <em>traverses</em> security without a JWT
 *       (auth is the HMAC signature) and fails downstream on the missing required
 *       header — a 400, never a security 401/403.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PartnerSecurityMatcherTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    // The deposit path is never exercised here; mock the storage port so no S3/MinIO
    // client is required to boot the context.
    @MockBean
    private EvidenceStorage evidenceStorage;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("An unauthenticated non-endpoint partner route is blocked by security (403, not reachable, no longer 404)")
    void otherPartnerRouteBlockedBySecurity() throws Exception {
        // The route now falls through to anyRequest().authenticated() and is rejected
        // by the security layer BEFORE reaching the dispatcher (the old wildcard let it
        // through to a 404). The rejection is a 403: with no httpBasic/formLogin and no
        // custom AuthenticationEntryPoint configured, Spring Security's default entry
        // point is Http403ForbiddenEntryPoint. What matters for the pin is that it is
        // blocked by security (not a 404, not reachable) — 403 proves exactly that.
        mockMvc.perform(get("/api/v1/partner/does-not-exist"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("A GET on the exact evidence path is blocked by security (the pin is method-scoped to POST)")
    void wrongMethodOnEvidencePathBlockedBySecurity() throws Exception {
        // The pin is HttpMethod.POST-scoped: a GET on the very same path is NOT
        // permitted and falls through to anyRequest().authenticated() -> blocked (403).
        mockMvc.perform(get("/api/v1/partner/escrow/1/evidence"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("An unauthenticated POST to a sibling partner path is blocked by security (the pin is path-exact, not a wildcard)")
    void siblingPartnerPostRouteBlockedBySecurity() throws Exception {
        // A POST under /api/v1/partner/** that is NOT the evidence endpoint is no
        // longer opened by a wildcard: it falls through to authenticated() -> 403.
        mockMvc.perform(post("/api/v1/partner/escrow/1/other"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The real deposit endpoint traverses security without a JWT and fails on the missing header (400, not a security 401/403)")
    void realEndpointTraversesSecurity() throws Exception {
        // No X-Escrow-* headers and no JWT: security permits the POST through, and the
        // controller's required-header binding rejects it with a 400 — proving the
        // endpoint is still reachable without authentication at the security layer.
        mockMvc.perform(multipart("/api/v1/partner/escrow/1/evidence"))
                .andExpect(status().isBadRequest());
    }
}
