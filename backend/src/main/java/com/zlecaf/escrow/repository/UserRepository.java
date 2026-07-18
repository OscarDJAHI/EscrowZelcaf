package com.zlecaf.escrow.repository;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    /** True si au moins un compte porte ce rôle — sert à l'idempotence de l'amorçage ADMIN. */
    boolean existsByRole(Role role);
}
