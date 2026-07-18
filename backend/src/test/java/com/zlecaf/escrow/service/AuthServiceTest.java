package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.JwtService;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.dto.AuthDtos.AuthResponse;
import com.zlecaf.escrow.web.dto.AuthDtos.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

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
        service = new AuthService(users, encoder, jwt);
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");
        when(jwt.generateToken(any())).thenReturn("jwt-token");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerRejectsSelfAssignedAdmin() {
        RegisterRequest req = new RegisterRequest("admin@escrow.co", "password123", "A", "B", Role.ADMIN);
        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BadRequestException.class);
        verify(users, never()).save(any());
    }

    @Test
    void registerAcceptsSellerRole() {
        RegisterRequest req = new RegisterRequest("seller@escrow.co", "password123", null, null, Role.SELLER);
        AuthResponse resp = service.register(req);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.SELLER);
        assertThat(resp.token()).isEqualTo("jwt-token");
    }

    @Test
    void registerDefaultsNullRoleToBuyer() {
        RegisterRequest req = new RegisterRequest("nobody@escrow.co", "password123", null, null, null);
        service.register(req);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.BUYER);
    }
}
