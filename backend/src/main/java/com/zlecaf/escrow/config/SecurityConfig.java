package com.zlecaf.escrow.config;

import com.zlecaf.escrow.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /** Allowlist CORS (Story 1.5) : vide = mode dev permissif, non vide = comparaison exacte. */
    private final List<String> corsAllowedOrigins;

    /** Documentation d'API exposée (Story 1.5) : false en profil prod, true partout ailleurs. */
    private final boolean docsExposed;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter,
                          @Value("${escrow.api.cors-allowed-origins:}") String corsAllowedOrigins,
                          @Value("${escrow.api.docs-exposed:true}") boolean docsExposed) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.corsAllowedOrigins = CorsOriginPolicy.parse(corsAllowedOrigins);
        this.docsExposed = docsExposed;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            // En-têtes de sécurité en défense en profondeur (Story 1.4, NFR-P3).
            // Le reverse-proxy TLS pose les mêmes en-têtes sur les réponses HTML ;
            // ici on garantit qu'une réponse API émise en direct par le backend est
            // durcie de la même façon. HSTS n'est servi que sur une requête HTTPS
            // (request.isSecure()) — derrière le proxy, forward-headers-strategy
            // dérive ce booléen de X-Forwarded-Proto (cf. application.yml).
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .contentTypeOptions(Customizer.withDefaults())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000L))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'")))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> {
                auth
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    // Simulated partner webhook callbacks (HMAC-signed, not JWT-auth'd).
                    .requestMatchers("/api/v1/webhooks/incoming/**").permitAll()
                    // Machine partner deposit (auth carried entirely by the HMAC signature,
                    // not JWT). Pinned to exactly POST /api/v1/partner/escrow/*/evidence so
                    // no other /api/v1/partner/** route is ever opened by default — anything
                    // else falls through to anyRequest().authenticated().
                    .requestMatchers(HttpMethod.POST, "/api/v1/partner/escrow/*/evidence").permitAll()
                    .requestMatchers("/actuator/health").permitAll();
                if (docsExposed) {
                    // OpenAPI spec + Swagger UI, ouverts hors production pour l'exploration
                    // de l'API (dev, CI). Sous profil prod (Story 1.5, NFR-P4) ces matchers
                    // ne sont PAS enregistrés : les chemins retombent alors sur
                    // anyRequest().authenticated() -> 403. C'est la seconde des deux
                    // fermetures ; application-prod.yml coupe en plus les handlers springdoc
                    // (-> 404). Aucune combinaison des deux ne rouvre la documentation.
                    auth.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll();
                }
                auth.anyRequest().authenticated();
            })
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        if (corsAllowedOrigins.isEmpty()) {
            // POC: permissive origins so the PWA (any dev host) can call the API.
            config.setAllowedOriginPatterns(List.of("*"));
        } else {
            // Allowlist fournie par l'opérateur (Story 1.5, NFR-P4) : setAllowedOrigins
            // = comparaison EXACTE, jamais setAllowedOriginPatterns (jokers). Une origine
            // absente de la liste reçoit un 403 « Invalid CORS request » émis par le
            // CorsFilter de Spring AVANT tout contrôleur — hors périmètre de
            // GlobalExceptionHandler, donc sans enveloppe d'erreur : c'est voulu.
            // Le PWA de production n'a pas besoin d'y figurer : il est same-origin
            // (VITE_API_BASE="", /api proxifié) et CorsUtils.isCorsRequest écarte
            // les requêtes dont l'Origin coïncide avec scheme/hôte/port de la requête.
            config.setAllowedOrigins(corsAllowedOrigins);
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Sans exposition explicite, le navigateur masque Retry-After à une origine
        // allowlistée : le client ne pourrait pas honorer le backoff du 429 émis par
        // l'anti-bruteforce (Story 1.3). Seuls les en-têtes « simples » sont lisibles
        // par défaut en CORS.
        config.setExposedHeaders(List.of("Retry-After"));
        // allowCredentials volontairement NON positionné (donc false) : l'authentification
        // passe par l'en-tête Authorization, jamais par cookie. Ne jamais l'activer sans
        // décision explicite — combiné à une allowlist, il ouvrirait le vol de session.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
