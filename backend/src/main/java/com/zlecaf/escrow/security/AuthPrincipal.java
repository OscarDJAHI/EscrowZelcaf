package com.zlecaf.escrow.security;

import com.zlecaf.escrow.domain.Role;

/**
 * Lightweight authenticated principal reconstructed from JWT claims and placed
 * in the security context. Exposed to controllers via {@code @AuthenticationPrincipal}.
 */
public record AuthPrincipal(Long userId, String email, Role role) {
}
