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

    /**
     * Inscription (Story 2.4). <b>202 sans corps</b>, et c'est le point important.
     *
     * <p>Un 201 « Created » affirmerait qu'un compte vient d'être créé — donc, par
     * contraposée, que l'adresse était libre. La réponse ne peut pas dépendre de ce qu'on
     * refuse de divulguer (NFR-P9, AC4). 202 « Accepted » est exactement ce qui s'est
     * passé du point de vue de l'appelant : sa demande est prise en compte, et s'il est
     * bien le propriétaire de l'adresse il recevra un code.
     *
     * <p>Aucun corps non plus : un corps, même neutre, finit par accueillir un champ utile
     * qui redeviendra un oracle. Rien à comparer, rien à faire fuir.
     */
    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.accepted().build();
    }

    /** Saisie du code (AC2) : c'est ICI que la session est émise, pas à l'inscription. */
    @PostMapping("/verify-email")
    public AuthResponse verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return authService.verifyEmail(request);
    }

    /** Renvoi du code (AC4). 202 sans corps, pour la même raison que l'inscription. */
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerificationCode(request);
        return ResponseEntity.accepted().build();
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
