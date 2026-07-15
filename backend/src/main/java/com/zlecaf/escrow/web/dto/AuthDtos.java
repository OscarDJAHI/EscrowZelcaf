package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Authentication request/response payloads. */
public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 6, max = 100) String password,
            String firstName,
            String lastName,
            @NotNull Role role) {}

    public record LoginRequest(
            @Email @NotBlank String email,
            @NotBlank String password) {}

    public record UserDto(Long id, String email, String firstName, String lastName, Role role) {
        public static UserDto from(User u) {
            return new UserDto(u.getId(), u.getEmail(), u.getFirstName(), u.getLastName(), u.getRole());
        }
    }

    public record AuthResponse(String token, UserDto user) {}
}
