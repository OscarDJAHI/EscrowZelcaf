package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    /** True si au moins un compte porte ce rôle — sert à l'idempotence de l'amorçage ADMIN. */
    boolean existsByRole(Role role);

    /**
     * Incrémente {@code token_version} ATOMIQUEMENT en base (revue 1.6).
     *
     * <p>Le lire-modifier-écrire d'origine ({@code setTokenVersion(get + 1)} puis
     * {@code save}) émettait un UPDATE de TOUTES les colonnes depuis un instantané
     * potentiellement périmé, et {@code User} ne porte pas de {@code @Version}.
     * Deux conséquences prouvées : un logout concurrent réécrivait le
     * {@code password_hash} périmé par-dessus un changement de mot de passe qui
     * venait d'aboutir — l'utilisateur se croyait protégé alors que l'ancien mot
     * de passe, possiblement compromis, authentifiait toujours — et deux
     * révocations simultanées se réduisaient à un seul incrément.
     *
     * <p>Cet UPDATE ciblé ne touche que la colonne concernée, lit la valeur
     * courante en base et ne peut donc rien écraser. Il retourne le nombre de
     * lignes touchées : 0 signifie « compte absent », ce qui rend la révocation
     * naturellement idempotente (un logout ne doit jamais échouer en 404).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.id = :userId")
    int incrementTokenVersion(@Param("userId") Long userId);
}
