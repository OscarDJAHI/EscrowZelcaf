---
baseline_commit: dc3647620d1c1e2e6a5639cc5e9d71b67567d195
---
# Story 1.4: TLS de bout en bout et en-têtes de sécurité

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a utilisateur,
I want que tout échange avec la plateforme soit chiffré et durci,
So that mes données financières ne puissent être interceptées ou détournées.

## Acceptance Criteria

1. **Given** une requête HTTP en clair (port 80) atteignant le reverse-proxy, **When** elle est reçue, **Then** elle est redirigée en HTTPS (301/308) et l'en-tête `Strict-Transport-Security` (HSTS) est servi sur les réponses HTTPS (NFR-P3).
2. **Given** n'importe quelle réponse HTML servie par la plateforme, **When** le client la reçoit, **Then** `Content-Security-Policy` et `X-Frame-Options` sont présents (NFR-P3), aux côtés de `X-Content-Type-Options: nosniff`, `Referrer-Policy` et `Permissions-Policy`.
3. **Given** le reverse-proxy TLS qui termine le SSL devant le backend, **When** une requête arrive au backend, **Then** Spring reconnaît le schéma d'origine (`X-Forwarded-Proto=https`) et l'**IP réelle du client** (`X-Forwarded-For`), **And** le backend n'est joignable **que** via le proxy (non publié en clair sur l'hôte) — de sorte que le rate-limiter de la Story 1.3 clé sur l'IP client réelle et non sur celle du proxy (résolution du report DEF2).

## Tasks / Subtasks

- [ ] **Task 1 — Reverse-proxy TLS sur la stack compose (local/staging)** (AC: #1, #3)
  - [ ] Faire du nginx frontend le point d'ingress unique de la stack compose : terminer TLS en `listen 443 ssl` + `listen 80` avec redirection `return 301 https://$host$request_uri`, servir le SPA, et **proxifier `/api` → `backend:8080`** (`proxy_pass`, `proxy_set_header Host/X-Real-IP/X-Forwarded-For/X-Forwarded-Proto`). Le proxy **écrase** (ne concatène pas) `X-Forwarded-For` avec l'IP client réelle (`$remote_addr`) pour empêcher le spoofing.
  - [ ] `infra/docker-compose.yml` : ne plus publier `backend:8080` sur l'hôte (réseau interne uniquement, joignable seulement par le proxy) ; publier le proxy sur `443` (et `80` pour la redirection). Ne PAS fermer les consoles d'admin (Adminer/pgAdmin/MinIO/RabbitMQ) — c'est un AC de la Story 11.3.
  - [ ] Certificats : self-signed/mkcert en local, montés par volume ; en staging/prod, injectés hors-repo via le pattern `.env` (Story 1.2). Documenter la génération locale. Mettre à jour `VITE_API_BASE` pour un appel **même-origine** (`/api`) plutôt que `http://localhost:8080` — supprime le besoin CORS navigateur (le durcissement CORS reste Story 1.5 pour l'accès direct/partenaire).
- [ ] **Task 2 — En-têtes de sécurité au niveau proxy (couvre HTML + API)** (AC: #1, #2)
  - [ ] Dans `frontend/nginx.conf`, poser sur toutes les réponses (bloc serveur HTTPS) : `Strict-Transport-Security: max-age=31536000; includeSubDomains` (HSTS uniquement sur HTTPS), `Content-Security-Policy` (politique restrictive adaptée au PWA : `default-src 'self'` + sources réellement nécessaires — auditer les besoins réels du build Vite, éviter `unsafe-inline` si possible ; documenter tout assouplissement), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `Permissions-Policy` minimale.
- [ ] **Task 3 — Durcissement Spring : forward-headers + en-têtes en défense en profondeur** (AC: #2, #3)
  - [ ] `application.yml` : ajouter `server.forward-headers-strategy: FRAMEWORK` pour que Spring dérive `request.isSecure()` de `X-Forwarded-Proto` et l'IP client de `X-Forwarded-For` (via `ForwardedHeaderFilter`). Conséquence directe : `AuthRateLimitFilter` (Story 1.3) reçoit alors l'IP client réelle par `getRemoteAddr()` — vérifier ce comportement par test.
  - [ ] `SecurityConfig.java` : ajouter un bloc `.headers(...)` explicite sur la chaîne (HSTS conditionné au HTTPS, `frameOptions().deny()`, `contentTypeOptions()`, `referrerPolicy(...)`, CSP alignée sur celle du proxy) — expliciter et tester ce que Spring pose aujourd'hui implicitement, en défense en profondeur pour les réponses API. Injection par constructeur, conforme au style du repo.
- [ ] **Task 4 — Tests** (AC: #2, #3)
  - [ ] Intégration MockMvc (pattern `AuthRateLimitIntegrationTest` : `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers`) : asserter la présence/valeur des en-têtes Spring (`header().string("X-Frame-Options", "DENY")`, `header().exists("Content-Security-Policy")`, `header().string("X-Content-Type-Options", "nosniff")`, Referrer-Policy).
  - [ ] Test forward-headers : requête avec `X-Forwarded-Proto: https` + `X-Forwarded-For: <ip>` → vérifier que Spring voit HTTPS / l'IP client (et que le rate-limiter clé dessus, pas sur l'IP du proxy).
  - [ ] Redirection HTTP→HTTPS et en-têtes posés par nginx : **non couvrables par MockMvc** (couche proxy). Fournir un script de vérification (`curl -I`) documenté et/ou un test léger Testcontainers sur l'image proxy ; sinon consigner la validation E2E comme dette Story 11.7. Documenter le choix.
  - [ ] Suites backend + frontend complètes vertes avant `review`.

## Dev Notes

### État actuel (à modifier — lu au préalable)

- **Aucun reverse-proxy TLS ni port 443 aujourd'hui.** `infra/docker-compose.yml` publie chaque service en clair : `backend 8080:8080`, `frontend 5173:80`, et `VITE_API_BASE: http://localhost:8080` (le PWA appelle l'API en HTTP direct). Le nginx frontend (`frontend/nginx.conf`, `frontend/Dockerfile` : `nginx:1.27-alpine`, `EXPOSE 80`) ne sert que les assets statiques + fallback SPA ; ses seuls `add_header` sont du cache, **aucun** header de sécurité, aucune redirection, aucun `listen 443`.
- **Aucun des 6 en-têtes de sécurité n'est posé dans du code exécuté** (recherche exhaustive : seules occurrences = docs de planning). `SecurityConfig.java` configure `cors`, `csrf disable`, `sessionManagement STATELESS`, `authorizeHttpRequests`, `JwtAuthFilter` — **aucun `.headers(...)`**. Seuls les défauts implicites Spring Security s'appliquent (`X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` sur l'API), non explicités, non testés, non appliqués au HTML frontend. `frontend/index.html` n'a **aucune** `<meta CSP>`.
- **`application.yml` : fichier unique, aucun profil**, aucune config `forward-headers` / `ForwardedHeaderFilter`. `server.port: ${SERVER_PORT:8080}`, actuator restreint à `health,info`.

### Frontière de périmètre (tranchée par le spine)

- **AR-P5 (ARCHITECTURE-SPINE.md:301)** : « le reverse-proxy TLS livré en **Story 1.4** sur la stack compose est l'infra **locale/staging**, ratifiée ou remplacée par AR-P5 [décidée en Story 11.3] ». → **1.4 possède le reverse-proxy TLS sur compose (local/staging)** ; 11.3 (spike AR-P5) tranche/remplace l'ingress cible de prod. **Ne pas sur-construire** (avertissement reconcile-epics m10) : viser une base réutilisable, pas l'ingress de prod définitif.
- Convention « Sécurité & session » du spine (ligne 187) : rate-limiting applicatif (fait, 1.3), **reverse-proxy en complément** ; Capability Map (ligne 292) rattache **profils + reverse-proxy** à la sécurité transverse.
- **HORS périmètre 1.4** — appartient à **1.5** : durcir le CORS `setAllowedOriginPatterns(List.of("*"))` (`SecurityConfig.java`, POC permissif) et fermer Swagger (`permitAll` sur `/v3/api-docs/**`, `/swagger-ui/**`), par **profil production**. Appartient à **11.3** : fermer les consoles d'admin (Adminer/pgAdmin/MinIO/RabbitMQ) et choisir la cible d'hébergement/ingress de prod.

### Lien dur avec la Story 1.3 (report DEF2 — à solder ici)

- Ledger `deferred-work.md` DEF2 : « Stratégie trusted-proxy / X-Forwarded-For pour le rate-limiter : BLOQUANT une fois le reverse-proxy TLS posé (Story 1.4). Sans proxy de confiance, XFF est spoofable ; avec proxy mais sans config, toutes les requêtes portent l'IP du proxy → une seule clé partagée → DoS global. À traiter DANS la Story 1.4. »
- **Modèle de confiance** : la sûreté de `X-Forwarded-For` repose sur l'**isolation réseau** — le backend ne doit être joignable **que** par le proxy (d'où le retrait de la publication `8080` sur l'hôte, Task 1). Le proxy **écrase** XFF avec `$remote_addr` (pas de concaténation d'un XFF fourni par le client). `ForwardedHeaderFilter` (activé par `forward-headers-strategy: FRAMEWORK`) fait ensuite confiance à ce XFF ; le durcissement vaut par la frontière réseau, pas par une allowlist applicative. Marquer DEF2 résolu au ledger une fois livré.

### Pattern de config à réutiliser (Story 1.2)

- `${VAR}` **sans défaut** pour tout secret (fail-fast au démarrage), `.env.example` versionné avec sentinelles `remplacez-moi`, `.env` gitignoré, `${VAR:?message}` côté compose pour échouer en nommant la variable manquante. Toute nouvelle valeur TLS (chemins de certificats, etc.) suit ce pattern.

### Standards de test

- Intégration : `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` (Postgres `postgres:16-alpine` via `@DynamicPropertySource`), overrides de propriétés par test via `DynamicPropertyRegistry`, secrets factices dans `backend/src/test/resources/application.properties`. Assertions en-têtes via `header().string(...)` / `header().exists(...)` (déjà utilisées dans `AuthRateLimitIntegrationTest`).
- Injection par constructeur pour toute nouvelle classe de config (convention du spine hérité).

### Project Structure Notes

- Fichiers pressentis : `frontend/nginx.conf` (TLS + proxy `/api` + headers), `frontend/Dockerfile` (EXPOSE 443, certs), `infra/docker-compose.yml` (proxy en ingress, backend interne, VITE_API_BASE `/api`), `infra/.env.example` (chemins certs), `backend/.../config/SecurityConfig.java` (`.headers(...)`), `backend/src/main/resources/application.yml` (`server.forward-headers-strategy`), nouveau(x) test(s) d'intégration sous `backend/src/test/java/com/zlecaf/escrow/security/`.
- Variance assumée : pas de profil Spring créé ici (délégué 1.5/11.2) ; HSTS/redirection posés au proxy (indépendants du profil), défense en profondeur Spring active par défaut.

### References

- [Source: epics.md#Story-1.4 (lignes 385-396)] · [Source: PRD NFR-P3, NFR-P15]
- [Source: ARCHITECTURE-SPINE.md#AR-P5 (301), #Sécurité-session (187), #Capability-Map (292), diagramme HTTPS (155)]
- [Source: deferred-work.md#DEF2 (216)] · [Source: 1-3-anti-bruteforce-authentification.md#DEF2]
- [Source: reviews/reconcile-epics.md#m10, #m3] · [Source: SecurityConfig.java, frontend/nginx.conf, infra/docker-compose.yml, application.yml]

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List

## Change Log

- 2026-07-25 : Story créée (context engine) — périmètre tranché via spine AR-P5 (reverse-proxy TLS compose local/staging), report DEF2 (trusted-proxy/XFF du rate-limiter 1.3) intégré comme AC #3, frontières 1.5 (CORS/Swagger) et 11.3 (consoles d'admin, ingress prod) explicitement exclues.
