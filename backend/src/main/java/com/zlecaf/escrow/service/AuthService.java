package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.JwtService;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.dto.AuthDtos.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        // Le rôle n'est JAMAIS attribué par le client à l'inscription. Liste BLANCHE :
        // seuls les rôles non-privilégiés {BUYER, SELLER} sont acceptés ; tout autre rôle
        // (ADMIN — arbitre, contrôle des fonds via RESOLVE_RELEASE/REFUND — ou un futur
        // rôle privilégié ajouté à l'enum) est rejeté, jamais ignoré silencieusement. La
        // garde ne dépend donc pas d'une liste noire à maintenir. L'absence de rôle retombe
        // sur BUYER. L'ADMIN est provisionné côté serveur (AdminBootstrap, puis Epic 7).
        Role role = (request.role() == null) ? Role.BUYER : request.role();
        if (role != Role.BUYER && role != Role.SELLER) {
            throw new BadRequestException("Ce rôle ne peut pas être auto-attribué à l'inscription");
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setRole(role);
        user = users.save(user);
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = request.email().trim().toLowerCase();
        User user = users.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadRequestException("Invalid credentials");
        }
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }
}
