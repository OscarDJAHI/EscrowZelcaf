package com.zlecaf.escrow.web;

import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.AuthService;
import com.zlecaf.escrow.web.dto.AuthDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /**
     * Déconnexion serveur (Story 1.6, AC #2) : révoque toutes les sessions de
     * l'utilisateur — le jeton présenté n'est plus accepté côté serveur, pas
     * seulement oublié côté client. Exige un JWT valide (route authentifiée).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal AuthPrincipal actor) {
        authService.revokeSessions(actor.userId(), "logout");
        return ResponseEntity.noContent().build();
    }

    /**
     * Changement de mot de passe authentifié (Story 1.6, AC #3) : vérifie l'ancien,
     * applique la politique au nouveau, puis révoque les sessions existantes.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthPrincipal actor,
                                               @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(actor.userId(), request);
        return ResponseEntity.noContent().build();
    }
}
