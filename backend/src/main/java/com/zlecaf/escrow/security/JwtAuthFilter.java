package com.zlecaf.escrow.security;

import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.domain.User;
import com.zlecaf.escrow.repository.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Extracts and validates the Bearer token on each request, populating the
 * security context with an {@link AuthPrincipal}. Invalid tokens are ignored
 * (the request proceeds unauthenticated and is rejected downstream).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;

    public JwtAuthFilter(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                Long userId = Long.valueOf(claims.getSubject());

                // Révocation côté serveur (Story 1.6, NFR-P5) : la signature + exp ne
                // suffisent plus. On confronte le claim `tv` à la version courante en
                // base ; un token dont la version est périmée (logout, changement de mot
                // de passe/rôle, désactivation) est refusé, même non expiré. Un compte
                // absent est refusé aussi. On n'authentifie QUE si l'utilisateur existe
                // ET la version correspond ; sinon on laisse le contexte non authentifié
                // (rejet 403 en aval).
                //
                // Un token SANS claim `tv` est refusé (revue 1.6, Task 3 « si absent ou
                // différent »). Le traiter comme version 0 laissait vivre jusqu'à 24 h
                // tout jeton émis avant le déploiement, contre des comptes encore en
                // version 0 : la révocation n'aurait pris effet qu'à l'expiration de la
                // dernière session pré-déploiement. Conséquence assumée : le déploiement
                // déconnecte tout le monde une fois.
                User user = users.findById(userId).orElse(null);
                Integer presentedVersion = claims.get("tv", Integer.class);
                String roleClaim = claims.get("role", String.class);

                if (user != null && presentedVersion != null && roleClaim != null
                        && presentedVersion == user.getTokenVersion()) {
                    // valueOf lève IllegalArgumentException sur un rôle inconnu (rattrapé
                    // plus bas) ; le claim absent est écarté ci-dessus, car valueOf(null)
                    // lèverait une NPE que le catch ne couvre pas -> 500 au lieu du rejet.
                    Role role = Role.valueOf(roleClaim);
                    String email = claims.get("email", String.class);
                    AuthPrincipal principal = new AuthPrincipal(userId, email, role);

                    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                } else {
                    SecurityContextHolder.clearContext();
                }
            } catch (JwtException | IllegalArgumentException ex) {
                // Malformed / expired token: leave the context unauthenticated.
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
