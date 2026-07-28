package com.zlecaf.escrow.config;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Prouve le comportement du seul chemin d'octroi ADMIN (Story 1.1) : amorçage
 * piloté par configuration, idempotent, sans ADMIN par défaut.
 */
class AdminBootstrapTest {

    /** Instance par défaut : hors profil `prod`, comportement historique inchangé. */
    private final AdminBootstrap bootstrap = new AdminBootstrap(new MockEnvironment());

    /** Instance en profil `prod`, seule à armer la garde D2. */
    private static AdminBootstrap inProduction() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return new AdminBootstrap(env);
    }

    @Test
    void seedsAdminWhenConfiguredAndNoneExists() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByRole(Role.ADMIN)).thenReturn(false);
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hash");

        CommandLineRunner runner = bootstrap.seedBootstrapAdmin(users, encoder, "Admin@Escrow.CO", "secret-password");
        runner.run();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.ADMIN);
        assertThat(captor.getValue().getEmail()).isEqualTo("admin@escrow.co"); // normalisé
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hash"); // mot de passe encodé, jamais brut
    }

    @Test
    void failsFastWhenConfiguredPasswordTooWeak() {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        CommandLineRunner runner = bootstrap.seedBootstrapAdmin(users, encoder, "admin@escrow.co", "short");
        assertThatThrownBy(runner::run).isInstanceOf(IllegalStateException.class);
        verify(users, never()).save(any());
    }

    @Test
    void noopWhenPartiallyConfigured() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        // email fourni mais password vide → aucun ADMIN, aucune interaction DB
        CommandLineRunner runner = bootstrap.seedBootstrapAdmin(users, encoder, "admin@escrow.co", "");
        runner.run();

        verifyNoInteractions(users);
    }

    @Test
    void idempotentWhenSaveRacesOnUniqueConstraint() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByRole(Role.ADMIN)).thenReturn(false);
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hash");
        doThrow(new DataIntegrityViolationException("duplicate email")).when(users).save(any());

        // Course concurrente : un autre process a créé le compte entre le check et le save.
        // Le conflit d'intégrité ne doit PAS remonter (démarrage non cassé).
        CommandLineRunner runner = bootstrap.seedBootstrapAdmin(users, encoder, "admin@escrow.co", "secret-password");
        runner.run();
    }

    @Test
    void idempotentWhenAdminAlreadyExists() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByRole(Role.ADMIN)).thenReturn(true);

        CommandLineRunner runner = bootstrap.seedBootstrapAdmin(users, encoder, "admin@escrow.co", "secret-password");
        runner.run();

        verify(users, never()).save(any());
    }

    @Test
    void noopWhenNotConfigured() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);

        CommandLineRunner runner = bootstrap.seedBootstrapAdmin(users, encoder, "", "");
        runner.run();

        verifyNoInteractions(users);
    }

    // --- Garde D2 : héritage silencieux d'un ADMIN d'origine inconnue -----------------

    @Test
    void refusesToStartInProductionWhenAForeignAdminAlreadyExists() {
        // Le scénario nommé par D2 : une base antérieure à la Story 1.1 — où le rôle était
        // AUTO-ATTRIBUABLE — promue en production. Sans cette garde, `existsByRole` ferait
        // sortir le runner en silence : l'ADMIN auto-attribué survit ET le légitime n'est
        // jamais créé.
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByRoleAndEmailNot(Role.ADMIN, "admin@escrow.co")).thenReturn(true);

        CommandLineRunner runner =
                inProduction().seedBootstrapAdmin(users, encoder, "admin@escrow.co", "secret-password");

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("admin@escrow.co");
        verify(users, never()).save(any());
    }

    @Test
    void productionRestartIsFineWhenTheOnlyAdminIsTheConfiguredOne() throws Exception {
        // Contre-épreuve indispensable : une garde qui ferait échouer TOUT redémarrage de
        // production serait pire que le trou qu'elle bouche. Après le premier démarrage,
        // l'unique ADMIN est celui de la configuration — le démarrage doit rester silencieux.
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByRoleAndEmailNot(Role.ADMIN, "admin@escrow.co")).thenReturn(false);
        when(users.existsByRole(Role.ADMIN)).thenReturn(true);

        CommandLineRunner runner =
                inProduction().seedBootstrapAdmin(users, encoder, "admin@escrow.co", "secret-password");
        runner.run();

        verify(users, never()).save(any());
    }

    @Test
    void outsideProductionAForeignAdminStaysTolerated() throws Exception {
        // La garde est délibérément bornée au profil `prod` : en développement, un ADMIN
        // hérité d'une base de travail est banal et ne doit pas empêcher de démarrer.
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(users.existsByRole(Role.ADMIN)).thenReturn(true);

        CommandLineRunner runner =
                bootstrap.seedBootstrapAdmin(users, encoder, "admin@escrow.co", "secret-password");
        runner.run(); // aucune exception

        verify(users, never()).existsByRoleAndEmailNot(any(), any());
        verify(users, never()).save(any());
    }
}
