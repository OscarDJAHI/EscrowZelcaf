package com.zlecaf.escrow.config;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Amorçage d'un compte ADMIN (arbitre) piloté par configuration. Depuis la
 * Story 1.1, le rôle ADMIN ne peut plus être auto-attribué à l'inscription
 * (contrôle des fonds via {@code RESOLVE_RELEASE}/{@code RESOLVE_REFUND}) : ce
 * runner est donc le seul chemin d'octroi côté plateforme.
 *
 * <p>Il crée UN compte ADMIN uniquement si {@code escrow.bootstrap.admin.email}
 * ET {@code escrow.bootstrap.admin.password} sont fournis, ET qu'aucun ADMIN
 * n'existe déjà (idempotent — un redémarrage ne recrée rien). Sans configuration,
 * il ne fait rien : jamais d'ADMIN par défaut ni de secret en clair (cohérent
 * avec le durcissement des secrets de la Story 1.2). Pont minimal jusqu'à
 * l'outillage d'admin complet de l'Epic 7.
 */
@Configuration
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Longueur minimale du mot de passe ADMIN, alignée sur la politique des comptes ordinaires. */
    private static final int MIN_ADMIN_PASSWORD_LENGTH = 6;

    /** Même convention de profil que {@code SecurityConfig} et {@code ProductionApiSurfaceGuard}. */
    private final boolean prodProfile;

    public AdminBootstrap(Environment environment) {
        this.prodProfile = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Bean
    CommandLineRunner seedBootstrapAdmin(UserRepository users, PasswordEncoder passwordEncoder,
            @Value("${escrow.bootstrap.admin.email:}") String email,
            @Value("${escrow.bootstrap.admin.password:}") String password) {
        return args -> {
            if (email == null || email.isBlank() || password == null || password.isBlank()) {
                return; // pas de configuration → aucun ADMIN créé (jamais d'ADMIN par défaut)
            }
            // Config PRÉSENTE mais invalide → échec au démarrage (fail-fast) plutôt qu'un
            // compte privilégié faible ou un no-op silencieux : le compte le plus sensible
            // de la plateforme doit au moins respecter la politique de mot de passe des
            // comptes ordinaires (≥ 6, cf. AuthDtos.RegisterRequest).
            if (password.length() < MIN_ADMIN_PASSWORD_LENGTH) {
                throw new IllegalStateException(
                        "escrow.bootstrap.admin.password est trop court (< " + MIN_ADMIN_PASSWORD_LENGTH + ")");
            }
            String normalized = email.trim().toLowerCase();

            // GARDE D2 (décision du 2026-07-28) — le silence de l'idempotence ci-dessous est
            // un angle mort : si un ADMIN existe DÉJÀ, ce runner sort sans rien dire. Or dans
            // une base antérieure à la Story 1.1 le rôle était AUTO-ATTRIBUABLE à l'inscription.
            // Promouvoir une telle base en production ferait donc deux choses à la fois :
            // conserver l'ADMIN illégitime, ET supprimer l'amorçage légitime — l'amorçage
            // croyant son travail déjà fait. Le compte le plus privilégié de la plateforme
            // serait celui de quelqu'un qui se l'est attribué lui-même.
            //
            // En production, un ADMIN dont l'email n'est pas celui de la configuration ne peut
            // donc pas être expliqué : on refuse de démarrer plutôt que d'hériter en silence.
            //
            // Pourquoi conditionner à la PRÉSENCE de la configuration : un exploitant qui retire
            // les identifiants d'amorçage de l'environnement après le premier démarrage (hygiène
            // souhaitable) ne doit pas provoquer un échec au redémarrage suivant. Ce chemin-là
            // est couvert par le critère d'entrée de la Story 11-3 : la production part d'une
            // base VIDE.
            //
            // DURÉE DE VIE : à revoir en Story 7-2, qui rendra l'octroi d'ADMIN possible depuis
            // le back-office — un second ADMIN deviendra alors légitime et cette garde, fausse.
            if (prodProfile && users.existsByRoleAndEmailNot(Role.ADMIN, normalized)) {
                throw new IllegalStateException(
                        "Un compte ADMIN autre que " + normalized + " existe déjà en base : "
                        + "provenance inexplicable en production (base antérieure à la Story 1.1, "
                        + "où le rôle était auto-attribuable ?). Démarrage refusé — vérifier la base "
                        + "avant de la servir.");
            }

            if (users.existsByRole(Role.ADMIN)) {
                return; // idempotent : un ADMIN existe déjà
            }
            if (users.existsByEmail(normalized)) {
                log.warn("Amorçage ADMIN ignoré : l'email {} existe déjà avec un autre rôle", normalized);
                return;
            }
            User admin = new User();
            admin.setEmail(normalized);
            admin.setPasswordHash(passwordEncoder.encode(password));
            admin.setRole(Role.ADMIN);
            try {
                users.save(admin);
                log.info("Compte ADMIN d'amorçage créé pour {}", normalized);
            } catch (DataIntegrityViolationException e) {
                // Course au démarrage (déploiement multi-instances) ou email pris entre le
                // check et le save : un autre process a déjà créé le compte. Idempotent —
                // on ne fait jamais échouer le démarrage sur ce conflit attendu.
                log.warn("Amorçage ADMIN : compte déjà présent (course concurrente), aucune action");
            }
        };
    }
}
