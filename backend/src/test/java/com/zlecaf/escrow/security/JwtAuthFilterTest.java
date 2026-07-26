package com.zlecaf.escrow.security;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;

import io.jsonwebtoken.Claims;

import jakarta.servlet.FilterChain;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Revue 1.6 — le filtre que la Story 1.6 réécrit n'avait AUCUN test unitaire.
 *
 * <p>Sa seule couverture était le test d'intégration, qui frappe toujours des
 * jetons fraîchement émis : les branches qui décident du sort de tous les jetons
 * déjà en circulation (claim {@code tv} absent, compte supprimé, claim
 * {@code role} absent) n'étaient jamais exécutées, alors que ce sont trois
 * nouveaux points de décision de sécurité.
 */
class JwtAuthFilterTest {

    private UserRepository users;
    private JwtService jwtService;
    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        filter = new JwtAuthFilter(jwtService, users);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("jeton valide et version à jour -> authentifié")
    void currentTokenAuthenticates() throws Exception {
        stubClaims(claims(1L, 3, "BUYER"));
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, 3)));

        assertThat(runFilter()).isTrue();
    }

    @Test
    @DisplayName("version périmée (révocation) -> NON authentifié, même si le jeton n'a pas expiré")
    void staleTokenVersionIsRefused() throws Exception {
        stubClaims(claims(1L, 2, "BUYER"));
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, 3)));

        assertThat(runFilter()).isFalse();
    }

    @Test
    @DisplayName("claim `tv` absent -> refusé (Task 3 : « si absent OU différent »)")
    void tokenWithoutVersionClaimIsRefused() throws Exception {
        // Le traiter comme version 0 laissait vivre jusqu'à 24 h tout jeton émis
        // avant le déploiement contre un compte encore en version 0 : la révocation
        // n'aurait pris effet qu'à l'expiration de la dernière session antérieure.
        Map<String, Object> withoutTv = claims(1L, null, "BUYER");
        stubClaims(withoutTv);
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, 0)));

        assertThat(runFilter()).isFalse();
    }

    @Test
    @DisplayName("compte supprimé -> refusé")
    void tokenForDeletedAccountIsRefused() throws Exception {
        stubClaims(claims(1L, 0, "BUYER"));
        when(users.findById(1L)).thenReturn(Optional.empty());

        assertThat(runFilter()).isFalse();
    }

    @Test
    @DisplayName("claim `role` absent -> refusé sans NPE (le catch ne couvrait pas la NPE -> 500)")
    void tokenWithoutRoleClaimIsRefusedWithoutException() throws Exception {
        stubClaims(claims(1L, 0, null));
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, 0)));

        assertThatCode(() -> assertThat(runFilter()).isFalse()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rôle inconnu -> refusé (IllegalArgumentException rattrapée)")
    void tokenWithUnknownRoleIsRefused() throws Exception {
        stubClaims(claims(1L, 0, "SORCERER"));
        when(users.findById(1L)).thenReturn(Optional.of(user(1L, 0)));

        assertThat(runFilter()).isFalse();
    }

    /** @return true si le filtre a posé une authentification dans le contexte */
    private boolean runFilter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer any-token");
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        return SecurityContextHolder.getContext().getAuthentication() != null;
    }

    private void stubClaims(Map<String, Object> values) {
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn(String.valueOf(values.get("sub")));
        when(claims.get("tv", Integer.class)).thenReturn((Integer) values.get("tv"));
        when(claims.get("role", String.class)).thenReturn((String) values.get("role"));
        when(claims.get("email", String.class)).thenReturn("user@escrow.co");
        when(jwtService.parse(any())).thenReturn(claims);
    }

    private static Map<String, Object> claims(Long sub, Integer tv, String role) {
        Map<String, Object> map = new HashMap<>();
        map.put("sub", sub);
        map.put("tv", tv);
        map.put("role", role);
        return map;
    }

    private static User user(Long id, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEmail("user@escrow.co");
        user.setPasswordHash("hash");
        user.setRole(Role.BUYER);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
