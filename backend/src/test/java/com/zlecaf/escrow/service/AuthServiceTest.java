package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.JwtService;
import com.zlecaf.escrow.security.PasswordPolicy;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.dto.AuthDtos.AuthResponse;
import com.zlecaf.escrow.web.dto.AuthDtos.ChangePasswordRequest;
import com.zlecaf.escrow.web.dto.AuthDtos.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prouve l'invariant de sécurité de la Story 1.1 : le rôle d'un compte n'est jamais
 * décidé par le client à l'inscription. ADMIN (arbitre, contrôle des fonds) ne peut
 * être auto-attribué ; les rôles non-privilégiés passent ; l'absence de rôle retombe
 * sur BUYER. Test unitaire pur (dépendances mockées), sans DB ni contexte Spring.
 */
class AuthServiceTest {

    private UserRepository users;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        jwt = mock(JwtService.class);
        // PasswordPolicy est un composant pur (sans état) : on l'instancie directement.
        service = new AuthService(users, encoder, jwt, new PasswordPolicy(12, 72, 3));
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");
        when(jwt.generateToken(any())).thenReturn("jwt-token");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // Mot de passe conforme à la politique (≥12, 4 catégories) — Story 1.6.
    private static final String STRONG = "Str0ng!Passw0rd";

    @Test
    void registerRejectsSelfAssignedAdmin() {
        RegisterRequest req = new RegisterRequest("admin@escrow.co", STRONG, "A", "B", Role.ADMIN);
        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BadRequestException.class);
        verify(users, never()).save(any());
    }

    @Test
    void registerAcceptsSellerRole() {
        RegisterRequest req = new RegisterRequest("seller@escrow.co", STRONG, null, null, Role.SELLER);
        AuthResponse resp = service.register(req);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.SELLER);
        assertThat(resp.token()).isEqualTo("jwt-token");
    }

    @Test
    void registerDefaultsNullRoleToBuyer() {
        RegisterRequest req = new RegisterRequest("nobody@escrow.co", STRONG, null, null, null);
        service.register(req);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.BUYER);
    }

    // --- Story 1.6 : politique de mot de passe + révocation ---------------------

    @Test
    void registerRejectsWeakPassword() {
        // 'password' : 8 caractères, une seule catégorie -> rejeté avant tout save.
        RegisterRequest req = new RegisterRequest("weak@escrow.co", "password", null, null, Role.BUYER);
        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("caractères");
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordRejectsWrongOldPassword() {
        User user = existingUser();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(encoder.matches("wrong-old", "existing-hash")).thenReturn(false);
        assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest("wrong-old", STRONG)))
                .isInstanceOf(BadRequestException.class);
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordRejectsWeakNewPassword() {
        User user = existingUser();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(encoder.matches("good-old", "existing-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest("good-old", "short")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("caractères");
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordSuccessRehashesAndRevokes() {
        User user = existingUser();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(encoder.matches("good-old", "existing-hash")).thenReturn(true);
        when(encoder.encode(STRONG)).thenReturn("new-hash");
        service.changePassword(1L, new ChangePasswordRequest("good-old", STRONG));
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getTokenVersion()).isEqualTo(1); // révocation : version incrémentée
        verify(users).save(user);
    }

    @Test
    void revokeSessionsIncrementsTokenVersion() {
        User user = existingUser();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        service.revokeSessions(1L);
        assertThat(user.getTokenVersion()).isEqualTo(1);
        verify(users).save(user);
    }

    private static User existingUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail("user@escrow.co");
        user.setPasswordHash("existing-hash");
        user.setRole(Role.BUYER);
        user.setTokenVersion(0);
        return user;
    }
}
