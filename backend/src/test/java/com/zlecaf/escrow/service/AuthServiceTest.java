package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.Company;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.CompanyRepository;
import com.zlecaf.escrow.repository.EmailVerificationCodeRepository;
import com.zlecaf.escrow.repository.LegalConsentRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.service.notification.EmailVerificationSender;
import com.zlecaf.escrow.security.JwtService;
import com.zlecaf.escrow.security.PasswordPolicy;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.dto.AuthDtos.ChangePasswordRequest;
import com.zlecaf.escrow.web.dto.AuthDtos.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.ZoneOffset;
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
    private CompanyRepository companies;
    private EmailVerificationCodeRepository codes;
    private LegalConsentRepository consents;
    private EmailVerificationSender sender;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private AuditService audit;
    private AuthService service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        companies = mock(CompanyRepository.class);
        codes = mock(EmailVerificationCodeRepository.class);
        consents = mock(LegalConsentRepository.class);
        sender = mock(EmailVerificationSender.class);
        encoder = mock(PasswordEncoder.class);
        jwt = mock(JwtService.class);
        audit = mock(AuditService.class);
        // PasswordPolicy est un composant pur (sans état) : on l'instancie directement.
        service = new AuthService(users, companies, codes, consents, sender, encoder, jwt,
                new PasswordPolicy(12, 72, 3), audit, Clock.systemUTC());
        when(users.existsByEmail(any())).thenReturn(false);
        when(encoder.encode(any())).thenReturn("hashed");
        when(jwt.generateToken(any())).thenReturn("jwt-token");
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(companies.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));
        when(codes.findByUserId(any())).thenReturn(Optional.empty());
        when(users.incrementTokenVersion(any())).thenReturn(1);
    }

    /**
     * Fabrique d'inscription VALIDE — raison sociale et consentement compris.
     *
     * <p>Elle existe pour que les tests de cette classe portent sur ce qu'ils annoncent :
     * le rôle et la politique de mot de passe. Sans elle, chaque appel répéterait les
     * champs de la Story 2.4, et le jour où l'inscription en exigera un de plus, ces tests
     * échoueraient tous pour une raison qui n'est pas leur sujet.
     */
    private static RegisterRequest registration(String email, String password, Role role) {
        return new RegisterRequest(email, password, null, null, role, "Acme SARL", Boolean.TRUE, null);
    }

    // Mot de passe conforme à la politique (≥12, 4 catégories) — Story 1.6.
    private static final String STRONG = "Str0ng!Passw0rd";

    @Test
    void registerRejectsSelfAssignedAdmin() {
        RegisterRequest req = registration("admin@escrow.co", STRONG, Role.ADMIN);
        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BadRequestException.class);
        verify(users, never()).save(any());
    }

    @Test
    void registerAcceptsSellerRole() {
        RegisterRequest req = registration("seller@escrow.co", STRONG, Role.SELLER);
        service.register(req);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.SELLER);
        // AUCUNE session rendue : depuis la Story 2.4 le compte naît non vérifié, et c'est
        // la saisie du code qui ouvre la session. Un jeton ici serait le défaut.
        assertThat(captor.getValue().isEmailVerified()).isFalse();
        verify(jwt, never()).generateToken(any());
    }

    @Test
    void registerDefaultsNullRoleToBuyer() {
        RegisterRequest req = registration("nobody@escrow.co", STRONG, null);
        service.register(req);
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.BUYER);
    }

    // --- Story 2.4 : l'inscription ne divulgue rien, y compris au chronomètre -----

    @Test
    void registerOnKnownEmailCreatesNothing() {
        when(users.existsByEmail(any())).thenReturn(true);
        service.register(registration("deja@escrow.co", STRONG, Role.BUYER));
        verify(users, never()).save(any());
        verify(companies, never()).save(any());
        verify(consents, never()).save(any());
        verify(sender, never()).sendVerificationCode(any(), any());
    }

    @Test
    void registerHashesThePasswordEvenWhenTheEmailIsAlreadyTaken() {
        // La garde anti-oracle porte sur le TEMPS de réponse : un bcrypt coûte ~100 ms, et
        // ne l'exécuter que dans la branche « création » rendrait les deux cas
        // distinguables au chronomètre — le code d'erreur a beau être identique.
        //
        // Chronométrer serait instable en CI ; on asserte donc la CAUSE plutôt que l'effet :
        // le hachage a lieu dans les deux branches, exactement une fois. Déplacer
        // `encode(...)` après le `existsByEmail` fait tomber ce test, ce qu'aucune
        // comparaison de corps de réponse ne pouvait faire.
        when(users.existsByEmail(any())).thenReturn(true);
        service.register(registration("deja@escrow.co", STRONG, Role.BUYER));
        verify(encoder).encode(STRONG);
    }

    @Test
    void registerRefusesAMissingConsent() {
        // Un consentement absent est un REFUS, jamais un défaut permissif (FR-P27).
        RegisterRequest req = new RegisterRequest("x@escrow.co", STRONG, null, null, Role.BUYER,
                "Acme SARL", null, null);
        assertThatThrownBy(() -> service.register(req)).isInstanceOf(BadRequestException.class);
        verify(users, never()).save(any());
    }

    // --- Story 1.6 : politique de mot de passe + révocation ---------------------

    @Test
    void registerRejectsWeakPassword() {
        // 'password' : 8 caractères, une seule catégorie -> rejeté avant tout save.
        RegisterRequest req = registration("weak@escrow.co", "password", Role.BUYER);
        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bytes");
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
                .hasMessageContaining("bytes");
        verify(users, never()).save(any());
    }

    @Test
    void changePasswordSuccessRehashesAndRevokes() {
        User user = existingUser();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(encoder.matches("good-old", "existing-hash")).thenReturn(true);
        when(encoder.matches(STRONG, "existing-hash")).thenReturn(false);
        when(encoder.encode(STRONG)).thenReturn("new-hash");
        service.changePassword(1L, new ChangePasswordRequest("good-old", STRONG));
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(users).save(user);
        // Révocation déléguée à la primitive unique (revue 1.6) et non dupliquée :
        // c'est CET appel que 2.6 et 7.2 réutiliseront, l'effet de bord ne suffit pas.
        verify(users).incrementTokenVersion(1L);
        verify(audit).recordAccountSecurityEvent("PASSWORD_CHANGED", 1L, "change-password");
        verify(audit).recordAccountSecurityEvent("SESSIONS_REVOKED", 1L, "password-changed");
    }

    @Test
    void changePasswordRejectsReuseOfCurrentPassword() {
        // Revue 1.6 : répondre 204 en révoquant tout sans rien changer laissait
        // l'utilisateur convaincu d'avoir tourné un secret compromis, toujours vivant.
        User user = existingUser();
        when(users.findById(1L)).thenReturn(Optional.of(user));
        when(encoder.matches(STRONG, "existing-hash")).thenReturn(true);
        assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest(STRONG, STRONG)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("differ");
        verify(users, never()).save(any());
        verify(users, never()).incrementTokenVersion(any());
    }

    @Test
    void revokeSessionsIncrementsTokenVersionAtomically() {
        // L'incrément passe par un UPDATE atomique (revue 1.6) : le lire-modifier-écrire
        // d'origine réécrivait toute la ligne depuis un instantané périmé et pouvait
        // écraser le password_hash d'un changement concurrent.
        service.revokeSessions(1L, "logout");
        verify(users).incrementTokenVersion(1L);
        verify(users, never()).save(any());
        verify(audit).recordAccountSecurityEvent("SESSIONS_REVOKED", 1L, "logout");
    }

    @Test
    void revokeSessionsOnMissingAccountIsANoOp() {
        // Un logout doit rester idempotent : un compte absent ne peut pas faire
        // échouer une déconnexion en 404, ni produire d'événement d'audit fantôme.
        when(users.incrementTokenVersion(42L)).thenReturn(0);
        service.revokeSessions(42L, "logout");
        verify(audit, never()).recordAccountSecurityEvent(any(), any(), any());
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
