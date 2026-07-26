package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Authentication request/response payloads. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            // La robustesse (longueur mini + complexité) est portée par PasswordPolicy
            // (Story 1.6), autorité unique réutilisée au changement/reset. Ici on ne garde
            // qu'un @NotBlank + un plafond garde-fou (bcrypt tronque à 72 octets, DoS).
            @NotBlank @Size(max = 72) String password,
            String firstName,
            String lastName,
            // Rôle optionnel et non-privilégié : le serveur retombe sur BUYER si absent et
            // rejette toute demande d'ADMIN (voir AuthService.register). Jamais bindé en ADMIN.
            Role role) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {}

    /** Changement de mot de passe authentifié (Story 1.6). */
    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank @Size(max = 72) String newPassword) {}

    public record UserDto(Long id, String email, String firstName, String lastName, Role role) {
        public static UserDto from(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole());
        }
    }

    public record AuthResponse(String token, UserDto user) {}
}
