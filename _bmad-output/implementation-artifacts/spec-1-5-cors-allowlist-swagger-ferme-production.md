---
title: 'Story 1.5 — CORS allowlist et Swagger fermé en production'
type: 'feature'
created: '2026-07-25'
status: 'in-review'
baseline_revision: 'd368b240323463d398f22dfd9c2e004dd9e214f7'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: ['oversized'] # story transverse (config backend + sécurité + ingress + compose) volontairement gardée en un seul fichier
---

<intent-contract>

## Intent

**Problem:** La surface d'API est celle du POC : le CORS accepte **toute** origine (`setAllowedOriginPatterns(List.of("*"))`, `SecurityConfig:76`) et la documentation interne est publique (`permitAll` sur `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `SecurityConfig:66`) — une cartographie complète de l'API offerte à un attaquant, et aucune borne sur les origines navigateur (NFR-P4, P0 stop-ship).

**Approach:** Introduire un **profil Spring `prod`** (aujourd'hui inexistant : aucun `application-<profil>.yml`, aucun `SPRING_PROFILES_ACTIVE` dans le dépôt) qui bascule deux commutateurs — allowlist CORS exacte fournie par l'opérateur, et fermeture de springdoc + retrait des matchers `permitAll` Swagger. Hors profil `prod`, le comportement actuel est **inchangé** (dev et CI restent pleinement utilisables). Un garde `@Profile("prod")` échoue au démarrage en nommant la variable manquante si l'allowlist est absente, vide ou permissive.

## Boundaries & Constraints

**Always:**
- Le durcissement est **conditionné au profil `prod`** uniquement. Aucun changement de comportement par défaut : les 3 tests `@SpringBootTest` existants et `./mvnw verify` en CI (aucun profil actif) doivent rester verts sans modification.
- Configuration par `@Value` en **injection par constructeur** (convention du projet : aucun `@ConfigurationProperties` n'existe dans `src/main/java`).
- Pattern secrets/config de la Story 1.2 : variable d'environnement, **échec explicite au démarrage nommant la variable** plutôt que repli silencieux sur une valeur permissive.
- `allowCredentials` reste **non positionné** (donc `false`) : l'auth passe par l'en-tête `Authorization` (localStorage), jamais par cookie. Ne jamais combiner allowlist et `allowCredentials(true)` sans décision explicite.
- En prod, l'allowlist utilise `setAllowedOrigins` (comparaison **exacte**), pas `setAllowedOriginPatterns` (comparaison à jokers).
- Enums, clés de configuration et messages techniques en anglais ; commentaires et documentation en français.

**Block If:**
- Il faudrait **coder en dur** une origine de production dans le dépôt (l'allowlist est fournie à l'exécution — si l'intention exigeait une liste versionnée, HALT).
- Le durcissement exigerait d'activer le profil `prod` sur la stack compose locale (elle reste local/staging ; l'hébergement de prod appartient à la Story 11.3 / spike AR-P5).

**Never:**
- Ne pas activer `spring.profiles.active=prod` par défaut dans `application.yml`, le `Dockerfile` backend, la CI ou le compose.
- Ne pas toucher aux consoles d'admin (Adminer/pgAdmin/MinIO/RabbitMQ) ni choisir l'ingress de prod — **Story 11.3**.
- Ne pas modifier la liste des méthodes CORS autorisées, la chaîne d'autorisation hors trio Swagger, le rate-limiter (1.3) ni les en-têtes de sécurité (1.4).
- Ne pas supprimer `OpenApiConfig` ni la dépendance springdoc : la doc reste vivante hors prod.
- Ne pas introduire de format d'erreur parallèle. Le rejet CORS est écrit par le `CorsFilter` de Spring **avant** tout contrôleur (403 « Invalid CORS request », corps texte) : c'est un comportement framework pré-contrôleur, hors périmètre de `GlobalExceptionHandler`, à documenter et non à contourner.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Préflight autorisé | Profil `prod`, allowlist `https://app.escrow.test` ; `OPTIONS /api/v1/escrow/1` + `Origin: https://app.escrow.test` + `Access-Control-Request-Method` | 200, `Access-Control-Allow-Origin: https://app.escrow.test` | Aucune erreur attendue |
| Origine inconnue (préflight) | Idem, `Origin: https://evil.test` | 403, **aucun** en-tête `Access-Control-Allow-Origin` | 403 « Invalid CORS request » émis par le `CorsFilter` |
| Origine inconnue (requête réelle) | Idem, `GET` + `Origin: https://evil.test` | 403, aucun `Access-Control-Allow-Origin` | Idem |
| **Same-origin non listé** | Profil `prod`, allowlist ne contenant PAS l'origine de la requête ; `Origin` identique au scheme/host/port de la requête | Requête traitée normalement (non-CORS) — le PWA same-origin de prod n'est pas cassé | Aucune erreur attendue |
| Swagger fermé | Profil `prod`, sans JWT ; `GET /v3/api-docs`, `/v3/api-docs.yaml`, `/swagger-ui/index.html`, `/swagger-ui.html` | Statut 403 **ou** 404 — jamais 2xx ni 3xx, jamais de corps OpenAPI | Refus silencieux (pas de fuite de version/routes) |
| Défaut dev/CI inchangé | Aucun profil actif | `/v3/api-docs` → 200 JSON, CORS permissif comme aujourd'hui | Aucune erreur attendue |
| Allowlist absente ou vide | Profil `prod`, `ESCROW_CORS_ALLOWED_ORIGINS` non défini ou `""` | **Échec au démarrage**, message nommant `ESCROW_CORS_ALLOWED_ORIGINS` | `IllegalStateException` au démarrage |
| Allowlist permissive | Profil `prod`, valeur contenant `*` (ex. `*` ou `https://*.escrow.test`) | Échec au démarrage (le joker est inopérant en comparaison exacte : piège silencieux) | `IllegalStateException` nommant l'entrée fautive |
| Origine malformée | Profil `prod`, entrée `app.escrow.test` (sans scheme) ou `https://a.test/api` (avec chemin) ou finissant par `/` | Échec au démarrage | `IllegalStateException` nommant l'entrée fautive |
| Réouverture accidentelle | Profil `prod` + `escrow.api.docs-exposed=true` forcé par variable d'environnement | Échec au démarrage (invariant non contournable) | `IllegalStateException` |
| Sonde via l'ingress | `GET /swagger-ui.html` sur le reverse-proxy | 404 (plus de repli SPA à 200) | Aucun corps applicatif |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/config/SecurityConfig.java` -- **cible principale** : CORS permissif l.72-82, trio Swagger `permitAll` l.66 ; bloc `.headers(...)` (1.4) et matcher partenaire épinglé (3.4) à ne pas perturber.
- `backend/src/main/java/com/zlecaf/escrow/config/OpenApiConfig.java` -- bean `OpenAPI` (titre/version/bearerAuth). Inchangé.
- `backend/src/main/resources/application.yml` -- fichier unique, **aucun profil** aujourd'hui ; aucune clé `springdoc.*`. Modèle `${VAR:défaut}` à suivre.
- `backend/src/main/java/com/zlecaf/escrow/config/RequiredSecretsEnvironmentPostProcessor.java` -- précédent de fail-fast au démarrage (Story 1.2) : ton et forme des messages à reprendre. Non modifié (l'allowlist n'est pas un secret).
- `backend/src/test/java/com/zlecaf/escrow/security/SecurityHeadersIntegrationTest.java` -- gabarit exact du test d'intégration (MockMvc + Testcontainers + `@DynamicPropertySource`).
- `backend/src/test/java/com/zlecaf/escrow/web/PartnerSecurityMatcherTest.java` -- gabarit de preuve d'un matcher `authorizeHttpRequests` (403 attendu pour non authentifié).
- `backend/src/test/resources/application.properties` -- secrets factices du contexte de test (nécessaires même sous profil `prod`).
- `frontend/nginx.conf` -- `location /api/` (proxy, `Origin` relayé intact) ; **aucune** location Swagger → repli SPA `index.html` à 200 aujourd'hui.
- `infra/docker-compose.yml` -- `backend.environment` (l.103-118), backend en `expose` seul (1.4).
- `infra/.env.example` / `infra/verify-tls-headers.sh` -- documentation et vérification manuelle de la couche proxy (1.4).

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/zlecaf/escrow/config/CorsOriginPolicy.java` -- créer une classe utilitaire sans dépendance Spring : `parse(String raw)` (découpe sur virgule, `trim`, ignore les entrées vides) et `requireValidProductionOrigins(List<String>)` (rejette liste vide, présence de `*`, entrée sans scheme `http(s)://`, avec chemin, ou finissant par `/`) -- source unique partagée entre la configuration CORS et le garde, et testable unitairement.
- [x] `backend/src/main/java/com/zlecaf/escrow/config/SecurityConfig.java` -- injecter par constructeur `@Value("${escrow.api.cors-allowed-origins:}") String` et `@Value("${escrow.api.docs-exposed:true}") boolean` ; si l'allowlist est non vide → `setAllowedOrigins(<liste exacte>)`, sinon conserver le `setAllowedOriginPatterns("*")` actuel avec son commentaire dev ; n'enregistrer les matchers `permitAll` Swagger **que** si `docs-exposed` ; ajouter `setExposedHeaders(List.of("Retry-After"))` -- sans quoi une origine allowlistée ne peut pas lire le `Retry-After` du 429 de la Story 1.3 et ne peut pas honorer le backoff.
- [x] `backend/src/main/java/com/zlecaf/escrow/config/ProductionApiSurfaceGuard.java` -- créer `@Configuration @Profile("prod")` validant au démarrage, via `CorsOriginPolicy`, que l'allowlist est exploitable et que `escrow.api.docs-exposed` est bien `false` -- rend l'invariant non contournable par variable d'environnement et transforme toute erreur de configuration en échec de boot explicite plutôt qu'en API ouverte.
- [x] `backend/src/main/resources/application-prod.yml` -- créer : `escrow.api.docs-exposed: false`, `escrow.api.cors-allowed-origins: ${ESCROW_CORS_ALLOWED_ORIGINS:}` (défaut vide **volontaire** pour que le garde produise le message nommant la variable), `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false` -- double fermeture : plus de handler springdoc **et** plus de `permitAll` ; toute combinaison des deux commutateurs reste fermée.
- [x] `backend/src/main/resources/application.yml` -- ajouter le bloc `escrow.api` avec les défauts de développement explicites et un commentaire renvoyant au profil `prod` -- rendre les deux commutateurs découvrables au lieu de les cacher dans des défauts d'annotation.
- [x] `frontend/nginx.conf` -- ajouter des `location` renvoyant 404 sur `/swagger-ui`, `/swagger-ui.html`, `/swagger-ui/`, `/v3/api-docs` (préfixe) -- aujourd'hui ces sondes reçoivent le shell SPA en 200 via `try_files`, ce qui contredit le critère « 404/403 » observé depuis l'ingress.
- [x] `infra/docker-compose.yml` -- ajouter dans `backend.environment` le passe-plat `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}` et `ESCROW_CORS_ALLOWED_ORIGINS: ${ESCROW_CORS_ALLOWED_ORIGINS:-}` -- permet de basculer en prod sans éditer le compose, en laissant la stack locale en mode dev par défaut.
- [x] `infra/.env.example` -- documenter les deux variables (format liste séparée par virgules, origines absolues sans chemin ni `/` final, effet de la bascule) -- même rôle documentaire que pour les secrets 1.2 et les ports d'ingress 1.4.
- [x] `infra/verify-tls-headers.sh` -- ajouter la vérification des sondes Swagger via l'ingress -- la couche nginx n'est pas couvrable par MockMvc (dette E2E automatisée déjà consignée en 11.7).
- [x] `backend/src/test/java/com/zlecaf/escrow/config/CorsOriginPolicyTest.java` -- test unitaire couvrant les cas de la matrice liés au parsing et à la validation (vide, `*`, joker de sous-domaine, sans scheme, avec chemin, `/` final, espaces autour des virgules, liste valide).
- [x] `backend/src/test/java/com/zlecaf/escrow/config/ProductionApiSurfaceGuardTest.java` -- test unitaire instanciant directement le garde : allowlist vide/`*`/malformée → exception dont le message **nomme** `ESCROW_CORS_ALLOWED_ORIGINS` ; `docs-exposed=true` → exception ; allowlist valide + `docs-exposed=false` → construction silencieuse -- couvre les lignes « échec au démarrage » de la matrice sans payer un boot complet.
- [x] `backend/src/test/java/com/zlecaf/escrow/security/ProductionApiSurfaceIntegrationTest.java` -- `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` + `@ActiveProfiles("prod")`, allowlist injectée par `@DynamicPropertySource` : préflight autorisé, origine inconnue rejetée (préflight et requête réelle), **same-origin non listé accepté**, et les 4 chemins Swagger fermés.
- [x] `backend/src/test/java/com/zlecaf/escrow/security/DevApiSurfaceIntegrationTest.java` -- même gabarit sans profil : `/v3/api-docs` répond 200 et le CORS reste permissif -- garde-fou contre un durcissement qui déborderait sur dev/CI.

**Acceptance Criteria:**
- Given le profil `prod` actif avec une allowlist valide, when l'application démarre, then elle boote sans erreur et sert l'API normalement aux origines listées.
- Given le profil `prod` actif, when un client interroge l'un des quatre chemins de documentation sans JWT, then il obtient 403 ou 404 et aucun contenu OpenAPI.
- Given aucun profil actif (dev, CI), when la suite de tests complète s'exécute, then elle est verte sans modification des tests préexistants et la documentation reste accessible.
- Given le profil `prod` actif et une allowlist absente, vide, contenant `*` ou malformée, when l'application démarre, then le démarrage échoue en nommant `ESCROW_CORS_ALLOWED_ORIGINS` et l'application ne sert aucune requête.
- Given le reverse-proxy de la stack compose, when une sonde interroge `/swagger-ui.html` ou `/v3/api-docs`, then l'ingress répond 404 au lieu du shell SPA.

## Spec Change Log

## Review Triage Log

Code review 2026-07-25 (3 relecteurs adversariaux) :

- [x] [Review][Patch] CORS profil-aware (décision : ceinture + bretelles) — sous profil `prod`, `corsConfigurationSource` doit refuser le repli permissif `*` lui-même (pas seulement via `ProductionApiSurfaceGuard`), pour supprimer le piège si le garde était un jour retiré [SecurityConfig.java]
- [x] [Review][Patch] `CorsOriginPolicy.describeProblem` accepte des entrées jamais matchables (espace interne, `?`, `#`, port vide/non numérique) → 403 silencieux en prod, exactement ce que la classe existe pour éliminer au boot [CorsOriginPolicy.java]
- [x] [Review][Patch] `ProductionApiSurfaceGuard` ne couvre pas les surcharges `springdoc.api-docs.enabled` / `swagger-ui.enabled` par variable d'env → handlers réactivés en prod, lisibles par un utilisateur JWT authentifié (le claim « aucune combinaison ne rouvre » vaut pour l'anonyme seulement) [ProductionApiSurfaceGuard.java]
- [x] [Review][Patch] `.env.example` affirme à tort « Swagger ouvert sur :8443/swagger-ui.html » en dev — l'ingress nginx renvoie 404 inconditionnellement et le backend n'est plus publié : Swagger est injoignable via la stack conteneurisée [infra/.env.example]
- [x] [Review][Defer] CSP backend `default-src 'self'` casse Swagger UI servi en direct par le backend en dev hôte (`mvn spring-boot:run`) — deferred, hors topologie livrée (ingress 404 + backend non publié), tuning CSP si le Swagger dev-hôte devient nécessaire

## Design Notes

**Pourquoi une allowlist exacte ne casse pas le PWA de production (vérifié dans les sources Spring 6.2.7).** `CorsUtils.isCorsRequest` compare le `Origin` au scheme/host/port **de la requête** et renvoie `false` s'ils coïncident : une requête same-origin n'entre jamais dans le traitement CORS et n'a donc pas besoin d'être allowlistée. C'est essentiel ici car la prod est same-origin (`VITE_API_BASE: ""`, appels `/api/v1/...` proxifiés par nginx). Ce mécanisme repose sur les valeurs **côté client** de `getScheme()/getServerName()/getServerPort()`, que `server.forward-headers-strategy: framework` (Story 1.4) dérive de `X-Forwarded-Proto`/`X-Forwarded-Host`. Si ces en-têtes étaient mal posés, le same-origin serait vu comme cross-origin et l'API répondrait 403 à tout le PWA : d'où le scénario de test dédié.

**Les deux commutateurs sont indépendants et convergent vers « fermé ».** `springdoc.*.enabled=false` supprime les handlers (→ 404) ; le retrait des `permitAll` fait retomber les chemins sur `anyRequest().authenticated()` (→ 403, comportement déjà asservi par `PartnerSecurityMatcherTest`). Aucune combinaison des deux n'ouvre la documentation ; les tests asservissent donc « 403 ou 404 », pas un code figé, pour ne pas devenir fragiles à un changement de springdoc.

**Portée du profil.** Le profil `prod` créé ici ne porte **que** la surface d'API. Il est le point d'accroche naturel des durcissements ultérieurs (11.2 environnements/secrets, 11.3 stack de prod), mais ne préempte aucune de ces décisions.

## Verification

**Commands:**
- `cd backend && ./mvnw -B test` -- attendu : BUILD SUCCESS, 272 tests préexistants toujours verts + les nouveaux ; **Docker requis** (Testcontainers).
- `docker compose -f infra/docker-compose.yml config` -- attendu : rendu valide, `SPRING_PROFILES_ACTIVE` vide par défaut (stack locale en mode dev).
- `cd frontend && npm run test` -- attendu : 170/170 verts (aucune régression ; la story ne touche pas le code frontend, seulement `nginx.conf`).

**Manual checks (if no CLI):**
- `infra/verify-tls-headers.sh` sur la stack lancée : `/swagger-ui.html` et `/v3/api-docs` répondent 404 via l'ingress.
- Relecture de `SecurityConfig` : `allowCredentials` toujours non positionné, méthodes CORS inchangées, matcher partenaire (3.4) et bloc `.headers(...)` (1.4) intacts.
