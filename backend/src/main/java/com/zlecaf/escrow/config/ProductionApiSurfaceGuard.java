package com.zlecaf.escrow.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Garde de la surface d'API en production (Story 1.5, NFR-P4).
 *
 * <p>{@code application-prod.yml} ferme déjà les deux commutateurs, mais ces
 * valeurs restent surchargeables : {@code ESCROW_API_DOCS_EXPOSED} et
 * {@code ESCROW_CORS_ALLOWED_ORIGINS} sont lues depuis l'environnement système,
 * qui prime sur les fichiers de configuration. Sans ce garde, une variable posée
 * par erreur rouvrirait la documentation ou rendrait l'allowlist inopérante —
 * silencieusement, en production. On transforme donc toute configuration
 * douteuse en échec de démarrage explicite (pattern fail-fast de la Story 1.2 :
 * l'erreur NOMME la variable), plutôt qu'en API ouverte.
 *
 * <p>La validation vit dans le constructeur : l'échec survient à la création du
 * bean, donc au démarrage du contexte, et la classe reste instanciable
 * directement dans un test unitaire (pas de boot complet à payer).
 */
@Configuration
@Profile("prod")
public class ProductionApiSurfaceGuard {

    public ProductionApiSurfaceGuard(
            @Value("${escrow.api.cors-allowed-origins:}") String corsAllowedOrigins,
            @Value("${escrow.api.docs-exposed:true}") boolean docsExposed,
            @Value("${springdoc.api-docs.enabled:true}") boolean springdocApiDocsEnabled,
            @Value("${springdoc.swagger-ui.enabled:true}") boolean springdocSwaggerUiEnabled) {
        CorsOriginPolicy.requireValidProductionOrigins(CorsOriginPolicy.parse(corsAllowedOrigins));
        if (docsExposed) {
            throw new IllegalStateException(
                    "Démarrage refusé (profil prod) : escrow.api.docs-exposed=true — la documentation d'API "
                            + "(OpenAPI + Swagger UI) ne doit jamais être servie en production (NFR-P4). "
                            + "Retirer la surcharge ESCROW_API_DOCS_EXPOSED ; application-prod.yml la fixe à false.");
        }
        // Seconde fermeture, indépendante : docs-exposed=false retire les matchers
        // permitAll mais NE désactive PAS les handlers springdoc. Si une variable
        // d'environnement les réactive, un utilisateur JWT authentifié pourrait lire
        // /v3/api-docs. On refuse donc aussi ce cas — « aucune combinaison ne rouvre ».
        if (springdocApiDocsEnabled || springdocSwaggerUiEnabled) {
            throw new IllegalStateException(
                    "Démarrage refusé (profil prod) : springdoc.api-docs.enabled / springdoc.swagger-ui.enabled=true — "
                            + "les handlers de documentation (OpenAPI + Swagger UI) doivent rester désactivés en "
                            + "production (NFR-P4). Retirer la surcharge SPRINGDOC_API_DOCS_ENABLED / "
                            + "SPRINGDOC_SWAGGER_UI_ENABLED ; application-prod.yml les fixe à false.");
        }
    }
}
