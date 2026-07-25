---
baseline_commit: dc3647620d1c1e2e6a5639cc5e9d71b67567d195
---
# Story 1.4: TLS de bout en bout et en-têtes de sécurité

Status: done

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

- [x] **Task 1 — Reverse-proxy TLS sur la stack compose (local/staging)** (AC: #1, #3)
  - [x] nginx frontend = ingress unique : serveur HTTP `listen 80` → `return 301 https://$host$request_uri` ; serveur HTTPS `listen 443 ssl` + `http2 on`, sert le SPA, **proxifie `/api/` → `backend:8080`** ; `proxy_set_header X-Forwarded-For $remote_addr` **écrase** (ne concatène pas) avec l'IP client réelle.
  - [x] `infra/docker-compose.yml` : backend en `expose` seulement (plus de publication `8080` sur l'hôte) ; proxy publié `${INGRESS_HTTP_PORT:-8080}:80` + `${INGRESS_HTTPS_PORT:-8443}:443`. Consoles d'admin laissées ouvertes (fermeture = Story 11.3).
  - [x] Certificat auto-signé généré dans le `Dockerfile` (openssl) pour local/staging, surchargé par volume `/etc/nginx/certs` en staging/prod (pattern `.env`, documenté dans `.env.example`). `VITE_API_BASE=""` → appels **même-origine** `/api/v1/...` ; `client.js` traite `""` comme même-origine (garde le défaut dev). Durcissement CORS = Story 1.5.
- [x] **Task 2 — En-têtes de sécurité au niveau proxy (couvre HTML + API)** (AC: #1, #2)
  - [x] `frontend/security-headers.conf` (inclus au serveur HTTPS ET ré-inclus dans les locations sw.js/manifest/assets — contourne le piège d'héritage `add_header` de nginx) : HSTS (HTTPS only), CSP restrictive PWA (`default-src 'self'`, `unsafe-inline` sur style-src documenté, à resserrer via audit navigateur → dette 11.7), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`, `Permissions-Policy`.
- [x] **Task 3 — Durcissement Spring : forward-headers + en-têtes en défense en profondeur** (AC: #2, #3)
  - [x] `application.yml` : `server.forward-headers-strategy: framework` → Spring dérive `request.isSecure()` de `X-Forwarded-Proto` et l'IP client de `X-Forwarded-For` (`ForwardedHeaderFilter`). `AuthRateLimitFilter` (Story 1.3) reçoit dès lors l'IP client par `getRemoteAddr()` — **prouvé par test** (aucune modif du filtre nécessaire).
  - [x] `SecurityConfig.java` : bloc `.headers(...)` explicite (HSTS conditionné HTTPS `includeSubDomains`/`max-age=31536000`, `frameOptions().deny()`, `contentTypeOptions()`, `referrerPolicy(STRICT_ORIGIN_WHEN_CROSS_ORIGIN)`, CSP `default-src 'self'; frame-ancestors 'none'; base-uri 'self'; object-src 'none'`). Injection par constructeur.
- [x] **Task 4 — Tests** (AC: #2, #3)
  - [x] `SecurityHeadersIntegrationTest` (MockMvc + Testcontainers + horloge figée, pattern `AuthRateLimitIntegrationTest`) — 5 tests : en-têtes présents ; HSTS servi ssi `X-Forwarded-Proto=https` ; HSTS absent en HTTP nu ; **DEF2** clients XFF distincts non verrouillés collectivement (clé ≠ IP proxy) + même client XFF verrouillé malgré proxys distincts (clé = IP client). Emails distincts pour isoler la dimension origine.
  - [x] Redirection HTTP→HTTPS + en-têtes nginx : non couvrables par MockMvc → script `infra/verify-tls-headers.sh` (curl) documenté ; validation E2E automatisée = dette Story 11.7 (consignée).
  - [x] Suites backend + frontend complètes vertes (frontend 170/170 ; backend 272/272, BUILD SUCCESS).

### Review Findings (code review 2026-07-25, 3 relecteurs adversariaux)

- [x] [Review][Defer] Redirection HTTP→HTTPS perd le port non standard (`$host` sans port) : local `:8080` → `https://localhost/` refusé ; prod (443) OK — deferred (décision) : limite purement locale, l'ingress prod définitif appartient à la Story 11.3
- [x] [Review][Defer] Confiance en `X-Forwarded-For` (`forward-headers=framework`, sans allowlist de proxy) — deferred (décision) : non exploitable en topologie compose (backend `expose` seul) ; durcissement trusted-proxy va de pair avec l'ingress réel (Story 11.3), lignée du report DEF2
- [x] [Review][Patch] nginx sans `client_max_body_size` → 413 sur les uploads de preuves 1–15 Mo (le PWA poste `/api/v1/escrow/{id}/evidence` en multipart via le proxy ; backend accepte 15 Mo) [frontend/nginx.conf]
- [x] [Review][Patch] `X-Forwarded-Port` non posé par nginx → same-origin du PWA vu comme CORS (403) en prod sur port ≠ 443 [frontend/nginx.conf]
- [x] [Review][Patch] Certificat auto-signé sans `subjectAltName` → rejet dur des navigateurs modernes (`ERR_CERT_COMMON_NAME_INVALID`) [frontend/docker-entrypoint.d/40-generate-tls-cert.sh]
- [x] [Review][Patch] File List de cette story incomplet — n'inclut pas le script d'entrypoint ni la génération de cert au runtime [ce fichier]
- [x] [Review][Defer] Montage partiel de certificat (crt XOR key) → le script régénère/écrase ou crash — deferred, cas rare, garde XOR explicite à ajouter
- [x] [Review][Defer] `proxy_pass http://backend:8080` met le DNS en cache au démarrage → 502 si l'IP backend change — deferred, pertinent pour l'ingress prod (Story 11.3, `resolver`)

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

claude-opus-4-8, 2026-07-25

### Debug Log References

- Piège de test évité : une première version en `@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate` provoquait des blocages massifs (une méthode à 900 s, classe à 3866 s) car les requêtes HTTP réelles butent sur RabbitMQ absent des Testcontainers. Bascule sur MockMvc + horloge figée (pattern `AuthRateLimitIntegrationTest`) → 5 tests en ~28 s, déterministes.
- Piège de test #2 : les 2 tests DEF2 utilisaient d'abord le même email → la clé COMPTE du rate-limiter verrouillait au seuil indépendamment de l'IP, masquant le keying XFF. Corrigé avec des emails distincts pour isoler la dimension origine (technique d'`independentOrigins`).

### Completion Notes List

- **AC #1/#2 (en-têtes)** : posés à deux niveaux. Reverse-proxy nginx (`security-headers.conf`) = source pour les réponses HTML du PWA ; `SecurityConfig.headers()` = défense en profondeur pour les réponses API émises en direct. HSTS n'est servi que sur requête HTTPS (`request.isSecure()`), donc jamais sur du HTTP nu (prouvé).
- **AC #1 (redirection)** : serveur nginx `:80` → 301 vers HTTPS ; serveur `:443` en TLS (certificat auto-signé embarqué pour local/staging, surchargeable par volume).
- **AC #3 / DEF2** : `server.forward-headers-strategy: framework` suffit — le `ForwardedHeaderFilter` (haute précédence) réécrit `getRemoteAddr()` depuis `X-Forwarded-For` avant l'`AuthRateLimitFilter` (`LOWEST_PRECEDENCE`) ; aucune modif du filtre 1.3. La confiance en XFF repose sur l'isolation réseau (backend non publié, joignable seulement via le proxy) + l'écrasement de XFF par nginx. Prouvé par 2 tests croisés (clé = IP client, pas IP proxy).
- **Même-origine** : le PWA appelle `/api/v1/...` en relatif (`VITE_API_BASE=""`), proxifié par nginx → plus de CORS navigateur en compose (le durcissement CORS reste Story 1.5 pour l'accès direct/partenaire). `client.js` distingue `""` (même-origine) de `undefined` (défaut dev `http://localhost:8080`).
- **Couche nginx** : non couvrable par MockMvc → `infra/verify-tls-headers.sh` (curl -I) pour la validation manuelle ; E2E automatisée déférée à la Story 11.7. `docker compose config` validé.
- Frontières respectées : consoles d'admin laissées ouvertes (11.3), CORS `*` et Swagger non touchés (1.5).

### File List

- backend/src/main/java/com/zlecaf/escrow/config/SecurityConfig.java (bloc `.headers(...)`)
- backend/src/main/resources/application.yml (`server.forward-headers-strategy: framework`)
- backend/src/test/java/com/zlecaf/escrow/security/SecurityHeadersIntegrationTest.java (nouveau, 5 tests)
- frontend/nginx.conf (ingress TLS + proxy /api + redirection + en-têtes)
- frontend/security-headers.conf (nouveau, snippet en-têtes)
- frontend/Dockerfile (openssl + snippet + EXPOSE 80 443 ; PAS de cert baked)
- frontend/docker-entrypoint.d/40-generate-tls-cert.sh (nouveau — génération du cert TLS auto-signé au DÉMARRAGE du conteneur, jamais dans l'image ; corrige le finding Trivy « clé privée baked »)
- frontend/src/api/client.js (même-origine si `VITE_API_BASE=""`)
- infra/docker-compose.yml (backend en `expose`, proxy publié HTTP/HTTPS, `VITE_API_BASE=""`)
- infra/.env.example (ports d'ingress + note certificat TLS)
- infra/verify-tls-headers.sh (nouveau, vérification manuelle de la couche proxy)
- _bmad-output/implementation-artifacts/sprint-status.yaml + ce fichier

## Change Log

- 2026-07-25 : Story implémentée — reverse-proxy TLS (compose local/staging) + redirection HTTP→HTTPS + HSTS + CSP/X-Frame-Options & co (nginx + défense en profondeur Spring) + `forward-headers-strategy` soldant le report DEF2 du rate-limiter 1.3. Backend +5 tests d'intégration (MockMvc/Testcontainers), frontend 170/170 vert. Couche nginx validée par script (E2E → dette 11.7).
- 2026-07-25 : Story créée (context engine) — périmètre tranché via spine AR-P5 (reverse-proxy TLS compose local/staging), report DEF2 (trusted-proxy/XFF du rate-limiter 1.3) intégré comme AC #3, frontières 1.5 (CORS/Swagger) et 11.3 (consoles d'admin, ingress prod) explicitement exclues.
