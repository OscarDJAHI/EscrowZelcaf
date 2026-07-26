package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Authentication request/response payloads. */
public final class AuthDtos {

    private AuthDtos() {}

    /**
     * Plafond de garde-fou DoS, volontairement TRÈS au-dessus de la politique
     * (revue 1.6). Il ne borne que le coût de traitement d'un corps absurde ; la
     * robustesse — y compris la limite bcrypt de 72 octets — appartient à
     * {@code PasswordPolicy}, autorité unique.
     *
     * <p>Il était auparavant calé sur 72 : bean-validation rejetait donc à 73
     * caractères AVANT que la politique ne s'exécute, en {@code VALIDATION_ERROR}
     * au lieu du {@code WEAK_PASSWORD} contractuel de l'AC #1 — et le plafond
     * {@code max-length} annoncé configurable dans {@code application.yml} était en
     * réalité écrasé par cette annotation en dur. Le desserrer rend la politique
     * effectivement souveraine ; elle compte en octets, ce que ce {@code @Size}
     * (unités UTF-16) ne saurait faire.
     */
    private static final int PASSWORD_DOS_GUARD = 512;

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(max = PASSWORD_DOS_GUARD) String password,
            String firstName,
            String lastName,
            // Rôle optionnel et non-privilégié : le serveur retombe sur BUYER si absent et
            // rejette toute demande d'ADMIN (voir AuthService.register). Jamais bindé en ADMIN.
            Role role) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            // Même garde-fou qu'à l'inscription (revue 1.6) : les deux alimentent le
            // même encodeur bcrypt, le laisser non borné d'un seul côté n'avait aucune
            // justification.
            @NotBlank @Size(max = PASSWORD_DOS_GUARD) String password) {}

    /** Changement de mot de passe authentifié (Story 1.6). */
    public record ChangePasswordRequest(
            @NotBlank @Size(max = PASSWORD_DOS_GUARD) String oldPassword,
            @NotBlank @Size(max = PASSWORD_DOS_GUARD) String newPassword) {}

    public record UserDto(Long id, String email, String firstName, String lastName, Role role) {
        public static UserDto from(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole());
        }
    }

    public record AuthResponse(String token, UserDto user) {}
}
