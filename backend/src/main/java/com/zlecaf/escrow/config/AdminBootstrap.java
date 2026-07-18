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
            if (users.existsByRole(Role.ADMIN)) {
                return; // idempotent : un ADMIN existe déjà
            }
            String normalized = email.trim().toLowerCase();
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
