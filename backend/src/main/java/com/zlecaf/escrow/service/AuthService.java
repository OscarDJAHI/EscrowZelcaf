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
    private final AuditService audit;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       PasswordPolicy passwordPolicy, AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
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
            // Message en anglais comme ses voisins : le backend n'émet pas de texte
            // localisé, le libellé utilisateur est porté par le frontend (AD-23).
            throw new BadRequestException(ErrorCode.INVALID_REQUEST, "Current password is incorrect");
        }
        passwordPolicy.validate(request.newPassword());
        // Refuser la rotation vers le MÊME secret (revue 1.6). Sans cette garde, le
        // changement répondait 204 et révoquait toutes les sessions sans rien changer :
        // l'utilisateur qui se croit compromis est déconnecté partout, croit avoir
        // tourné son mot de passe, et le secret fuité reste vivant.
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException(ErrorCode.INVALID_REQUEST,
                    "New password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        users.save(user);
        audit.recordAccountSecurityEvent("PASSWORD_CHANGED", userId, "change-password");
        // Passe par la primitive unique (Task 4/5) au lieu de dupliquer l'incrément :
        // c'est ce qui garantit que 2.6 (reset) et 7.2 (changement de rôle) révoqueront
        // exactement de la même façon.
        revokeSessions(userId, "password-changed");
    }

    /**
     * Révoque toutes les sessions d'un compte en incrémentant sa version de jeton
     * (Story 1.6). Primitive unique réutilisée par le logout, le changement de mot
     * de passe (ci-dessus), et plus tard le changement de rôle (7.2, AD-21) / la
     * désactivation.
     *
     * <p>L'incrément est fait par un UPDATE atomique et non par un
     * lire-modifier-écrire : voir {@link UserRepository#incrementTokenVersion} pour
     * la perte de mise à jour que cela ferme. Un compte absent est un no-op — un
     * logout doit rester idempotent et ne jamais échouer en 404.
     */
    @Transactional
    public void revokeSessions(Long userId, String reason) {
        if (users.incrementTokenVersion(userId) > 0) {
            audit.recordAccountSecurityEvent("SESSIONS_REVOKED", userId, reason);
        }
    }
}
