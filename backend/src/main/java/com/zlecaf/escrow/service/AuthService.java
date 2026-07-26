package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.JwtService;
import com.zlecaf.escrow.security.PasswordPolicy;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.AuthDtos.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final PasswordPolicy passwordPolicy;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       PasswordPolicy passwordPolicy) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.passwordPolicy = passwordPolicy;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new ConflictException("Email already registered");
        }
        // Politique de robustesse (Story 1.6, NFR-P5) : autorité unique, avant le hachage.
        passwordPolicy.validate(request.password());
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
        // Identifiants invalides = 401 (AUTH_FAILED), pas 400 : c'est une vraie
        // issue d'authentification (le rate-limiter la distingue d'un corps
        // malformé) et le message reste opaque (email inconnu vs mauvais mot de
        // passe indistinguables — anti-énumération).
        User user = users.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid credentials");
        }
        return new AuthResponse(jwtService.generateToken(user), UserDto.from(user));
    }

    /**
     * Change le mot de passe d'un utilisateur authentifié (Story 1.6, AC #3).
     * Vérifie l'ancien mot de passe, applique la politique au nouveau, puis
     * <b>révoque toutes les sessions</b> — le jeton courant (ancienne version)
     * devient invalide, l'utilisateur doit se reconnecter.
     */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            // L'utilisateur est déjà authentifié (on sait qui il est) : pas de risque
            // d'énumération, un message clair est légitime. Distinct de WEAK_PASSWORD.
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "L'ancien mot de passe est incorrect");
        }
        passwordPolicy.validate(request.newPassword());
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
    }

    /**
     * Révoque toutes les sessions d'un compte en incrémentant sa version de jeton
     * (Story 1.6). Primitive unique réutilisée par le logout, le changement de mot
     * de passe (ci-dessus), et plus tard le changement de rôle (7.2, AD-21) / la
     * désactivation. Idempotente au sens où chaque appel invalide les jetons émis
     * jusque-là.
     */
    @Transactional
    public void revokeSessions(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        users.save(user);
    }
}
