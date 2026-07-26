---
baseline_commit: c6e86d384fdf74edd621536121ea233f5ecd6f50
---
# Story 1.6: Politique de mots de passe et révocation JWT

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a utilisateur,
I want des règles de mot de passe robustes et une déconnexion réellement effective,
So that un jeton volé ou un mot de passe faible ne donnent pas accès durable à mon compte.

## Acceptance Criteria

1. **Given** une inscription ou un changement de mot de passe, **When** le mot de passe ne respecte pas la politique (longueur mini + complexité), **Then** il est rejeté avec les règles explicitées dans le message (NFR-P5), via un code d'erreur dédié `WEAK_PASSWORD`.
2. **Given** une déconnexion serveur ou une révocation de session, **When** le JWT précédemment émis est présenté sur un endpoint protégé, **Then** l'accès est refusé **côté serveur** (403), pas seulement côté client — le token n'est plus accepté même s'il n'a pas expiré.
3. **Given** un changement de mot de passe réussi, **When** il aboutit, **Then** l'ancien mot de passe est vérifié au préalable, le nouveau respecte la politique, **And** toutes les sessions existantes de l'utilisateur sont révoquées (les jetons antérieurs deviennent invalides côté serveur).

## Décision d'architecture (tranchée)

**Mécanisme de révocation = Approche A : `token_version` par utilisateur** (décision Oscard 2026-07-25, résout l'`[ASSUMPTION]` « refresh tokens » du spine `ARCHITECTURE-SPINE.md:187` vers l'option minimale). Une colonne `token_version` sur `users`, un claim `tv` dans le JWT, et une comparaison au filtre. Révocation = incrémenter `token_version` (invalide **toutes** les sessions du compte en une écriture). Pas de refresh token, pas de changement du contrat `AuthResponse`, TTL 24 h conservée comme filet. L'approche B (refresh tokens persistés) reste la cible possible du spine mais est **hors périmètre** de 1.6.

## Tasks / Subtasks

- [x] **Task 1 — Politique de mot de passe centralisée** (AC: #1)
  - [x] Créer un composant `PasswordPolicy` (paquet `service/` ou `security/`, sans état) : `validate(String raw)` qui lève une `BadRequestException` portant `ErrorCode.WEAK_PASSWORD` et un **message listant les règles** si non conforme. Règles : longueur **≥ 12** et **≤ 72** caractères (72 = limite de troncature bcrypt — au-delà le suffixe est ignoré silencieusement, à borner explicitement), complexité = au moins 3 des 4 catégories {minuscule, majuscule, chiffre, symbole}. Paramétrable par `escrow.auth.password.*` dans `application.yml` avec surcharge env (pattern Story 1.2/1.3).
  - [x] Appeler `PasswordPolicy.validate` dans `AuthService.register` **avant** le hachage. Relâcher le `@Size(min=6,max=100)` de `RegisterRequest` (`AuthDtos.java:16`) vers `@NotBlank` + `@Size(max=72)` (garde-fou DoS) — la politique devient l'autorité unique de la robustesse, réutilisable par le changement/reset.
- [x] **Task 2 — Code d'erreur `WEAK_PASSWORD` (contrat coordonné)** (AC: #1)
  - [x] Ajouter `WEAK_PASSWORD` à `ErrorCode` (Retryability **PERMANENT** : un rejeu identique échoue). Mettre à jour `ErrorCodeContractTest` de façon **délibérée** (le test verrouille la partition ; c'est un changement de contrat assumé, cf. Story 1.3). Ne casse pas `transientPartitionIsExact` (code permanent). Vérifier l'éventuelle assertion de taille sur les codes permanents et l'ajuster.
  - [x] Miroir frontend `frontend/src/utils/replayFailure.js` : ajouter un libellé dans `FAILURE_LABELS` (i18n). Register/change ne passent pas par la file offline, donc pas d'ajout à `TRANSIENT_CODES` ; label utile à l'affichage.
- [x] **Task 3 — Révocation serveur : `token_version` (migration + JWT + filtre)** (AC: #2, #3)
  - [x] Migration **`V6__user_token_version.sql`** : `ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;`
  - [x] Entité `User` : champ `tokenVersion` (`@Column(name="token_version", nullable=false)`, défaut 0).
  - [x] `JwtService.generateToken` : ajouter le claim `tv = user.tokenVersion`. `JwtAuthFilter` : après validation signature+exp, **lookup `UserRepository.findById(userId)`** et comparer le claim `tv` à `user.tokenVersion` ; si absent/différent → laisser le contexte non authentifié (rejet 403 comme tout token invalide). Injecter `UserRepository` dans le filtre. (Coût : un lookup PK indexé par requête authentifiée — acceptable MVP, cache court déférable → ledger.)
  - [x] Méthode de révocation unique dans `AuthService` (ou un `SessionService`) : `revokeSessions(userId)` = incrémenter `token_version` (une écriture, `@Transactional`). C'est la primitive que la Story 2.6 (reset) et la 7.2 (changement de rôle, AD-21) réutiliseront.
- [x] **Task 4 — Endpoints logout + changement de mot de passe** (AC: #2, #3)
  - [x] `POST /api/v1/auth/logout` (authentifié) : appelle `revokeSessions(currentUserId)` → 204/200. Le token présenté est dès lors refusé côté serveur.
  - [x] `POST /api/v1/auth/change-password` (authentifié) : DTO `{oldPassword, newPassword}` ; vérifier l'ancien via `passwordEncoder.matches` (sinon `UnauthorizedException`/`BadRequestException` opaque), valider le nouveau via `PasswordPolicy`, encoder, persister, puis `revokeSessions(userId)` (AC #3). Ces routes tombent sous `anyRequest().authenticated()` — **ne pas** les ajouter à la liste `permitAll` de `SecurityConfig` (login/register seuls y restent).
  - [x] Frontend minimal : `frontend/src/stores/auth.js` `logout()` appelle `POST /api/v1/auth/logout` (best-effort, ignore l'échec réseau) **avant** de vider l'état local. L'hygiène approfondie (vidage file offline IndexedDB, scoping par utilisateur, réaction 403) reste **Story 1.9** — ne pas l'implémenter ici.
- [x] **Task 5 — Tests** (AC: #1, #2, #3)
  - [x] Unitaires (`AuthServiceTest` pattern Mockito) : `PasswordPolicy` (accepte fort ; rejette < 12, > 72, sans complexité) ; `register` rejette un mdp faible (`WEAK_PASSWORD`) ; `changePassword` rejette un mauvais ancien mdp, applique la politique, appelle `revokeSessions`.
  - [x] Intégration (`AuthRateLimitIntegrationTest` pattern : `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Testcontainers` Postgres 16) : register mdp faible → 400 enveloppe `{code: WEAK_PASSWORD}` + message listant les règles ; login → token ; le token accède à un endpoint protégé (`GET /api/v1/escrow`) → 200 ; après `POST /auth/logout`, le **même** token sur `/api/v1/escrow` → 403 (AC #2) ; `change-password` mauvais ancien → rejet ; `change-password` OK → l'ancien token → 403 (AC #3) et un nouveau login fonctionne.
  - [x] Contrat + suites complètes vertes (backend `./mvnw test`, frontend `npm run test`).

## Dev Notes

### État actuel (lu — à modifier)

- **JWT stateless** : `JwtService` (HS256, claims sub=userId/email/role/iat/exp, **pas de `jti` ni `tv`**, TTL `escrow.jwt.ttl-seconds`=86400) ; `JwtAuthFilter` valide **signature+exp uniquement, sans lookup DB**, reconstruit le principal depuis les claims (`JwtAuthFilter.java:44-48`). STATELESS (`SecurityConfig.java:72`), filtre ajouté en `addFilterBefore` (`:95`).
- **Aucune révocation, aucun logout serveur, aucun refresh token.** `logout()` est purement client (`frontend/src/stores/auth.js:74`). `AuthController` n'expose que `/register` et `/login`.
- **Mot de passe** : `BCryptPasswordEncoder` (`SecurityConfig.java:143`), `encode` au register (`AuthService.java:46`), `matches` au login (`:63`). Seule validation = `@Size(min=6,max=100)` sur `RegisterRequest` (`AuthDtos.java:16`) — faible, non réutilisable. Pas d'endpoint de changement ni de reset.
- **Entité `User`** : `passwordHash` (`@Column(name="password_hash", nullable=false, length=255)`) ; le hash n'est jamais sérialisé (projection via `UserDto`, pas d'annotation Jackson — convention du repo). **Aucune** colonne de révocation. Table `users` créée en `V1__init.sql:14-23`. **Prochaine migration = V6.**
- **Contrat d'erreur** : enveloppe `{timestamp,status,error,code,message}` (`GlobalExceptionHandler`), `code` obligatoire. `ErrorCodeContractTest` verrouille la partition (SCREAMING_SNAKE, retryability, partition transient exacte, un seul code d'auth partenaire). Miroir front `replayFailure.js` (`TRANSIENT_CODES`, `FAILURE_LABELS`).

### Frontières (numérotation epics.md, qui supersède epics-production.md)

- **1.6 (cette story)** = fondation : politique mdp (register + changement) + primitive de révocation serveur (`token_version`) + endpoints logout & change-password. Déclencheurs **câblés ici** : logout, changement de mdp. Déclencheurs **mécanisme-prêts mais câblés ailleurs** : changement de rôle (Story 7.2, AD-21), désactivation de compte (endpoint inexistant aujourd'hui) — `revokeSessions(userId)` est fait pour être réutilisé.
- **HORS 1.6** : reset de mot de passe oublié + jeton usage unique + 2FA TOTP + stockage chiffré des secrets TOTP (AD-29) = **Story 2.6** (`epics.md:640-661`) — 2.6 **consomme** `revokeSessions` (`epics.md:651`). Hygiène de session côté client (vidage file offline, scoping par utilisateur, réaction fine 403) = **Story 1.9** (NFR-P8). Anti-énumération générale des ressources = **Story 1.10** (NFR-P9).
- **Réponse à un token révoqué** : un 403 (contexte non authentifié) satisfait l'AC « accès refusé côté serveur ». On **ne** crée **pas** de code `TOKEN_REVOKED` distinct ni d'écriture d'enveloppe custom dans le filtre en 1.6 — la distinction révoqué/expiré pour l'UX client relève de la Story 1.9. Garder le filtre simple (rejet = non authentifié, comme un token invalide).

### Décisions/pièges à respecter

- **bcrypt tronque à 72 octets** : la politique DOIT borner la longueur max (72) sinon un suffixe est ignoré silencieusement (faux sentiment de robustesse). Documenté dans `PasswordPolicy`.
- **Message actionnable** : l'AC #1 exige que les règles soient **explicitées** dans le message d'erreur (pas un « mot de passe invalide » opaque). C'est voulu — ce n'est PAS un endpoint anti-énumération (contrairement au login), la politique est publique.
- **Lookup DB au filtre** : nécessaire pour Approche A. Un PK `findById` par requête authentifiée. Acceptable au MVP mono-instance ; un cache court (ex. Caffeine) est déférable → ledger si la charge le justifie (Story 11.8).
- **Config externalisée** (pattern 1.2/1.3) : `escrow.auth.password.min-length`/`max-length`/`min-categories` avec `${ENV:défaut}`.
- **Ne pas** ouvrir logout/change-password en `permitAll` (ils exigent un JWT valide — c'est le principal qui identifie l'utilisateur à révoquer).

### Standards de test

- Unitaire service : Mockito, mocke `UserRepository`/`PasswordEncoder`/`JwtService` (gabarit `AuthServiceTest`).
- Intégration : `@SpringBootTest`+`@AutoConfigureMockMvc`+`@Testcontainers` (Postgres 16), secrets factices dans `backend/src/test/resources/application.properties`. Endpoint protégé témoin = `GET /api/v1/escrow`. Assertions enveloppe via `jsonPath("$.code")`, statut via `status()`.
- Injection par constructeur pour tout nouveau composant. Migration `V6__desc.sql` séquentielle.

### Project Structure Notes

- Fichiers pressentis : `V6__user_token_version.sql` (nouveau), `domain/User.java`, `security/JwtService.java`, `security/JwtAuthFilter.java` (+`UserRepository`), `service/AuthService.java` (+`revokeSessions`, `changePassword`), nouveau `security/PasswordPolicy.java`, `web/AuthController.java` (+ logout, change-password), `web/dto/AuthDtos.java` (ChangePasswordRequest, relâcher @Size), `domain/ErrorCode.java` (WEAK_PASSWORD), `resources/application.yml` (escrow.auth.password.*), tests unit+intégration + `ErrorCodeContractTest`, `frontend/src/stores/auth.js` + `frontend/src/utils/replayFailure.js` (+ tests front).

### References

- [Source: epics.md#Story-1.6 (411-425)] · [Source: PRD NFR-P5] · [Source: epics.md:651 (2.6 consomme la révocation)]
- [Source: ARCHITECTURE-SPINE.md#Sécurité-session (187, refresh tokens `[ASSUMPTION]` → tranché Approche A), #AD-21 (rôle porté par JWT), #AD-10 (contrat code d'erreur)]
- [Source: project-context.md] · [Source: JwtService.java, JwtAuthFilter.java, AuthService.java, AuthDtos.java, User.java, ErrorCode.java, ErrorCodeContractTest.java, replayFailure.js]

## Dev Agent Record

### Agent Model Used

claude-opus-4-8, 2026-07-25

### Debug Log References

- Tests existants créant des utilisateurs via `register` avec `password123` (2 catégories) : désormais rejetés par la politique. `AuthServiceTest` migré vers un mot de passe conforme (`Str0ng!Passw0rd`) + constructeur `AuthService` à 4 args (ajout `PasswordPolicy`).
- Filtre JWT : les retours anticipés appelaient `doFilter` dans le `try` (risque de double appel si l'aval lève une `IllegalArgumentException`). Restructuré en un **seul** `doFilter` final ; l'authentification n'est posée que si utilisateur présent ET version concordante, sinon contexte vidé.
- Frontend : `logout()` gardé **synchrone** (des tests l'appellent puis assertent l'état vidé) ; la révocation serveur part en **microtâche** fire-and-forget (`Promise.resolve(token).then(logoutUser).catch()`), donc jamais de throw synchrone même si le client HTTP est mocké minimalement (cas des specs SyncFailureNotice/RecoveryView). Jeton passé **explicitement** à `logoutUser` (indépendant de localStorage).

### Completion Notes List

- **AC #1 (politique)** : `PasswordPolicy` (composant unique, config `escrow.auth.password.*`) — longueur 12–72 (72 = borne bcrypt documentée), ≥ 3 des 4 catégories. Appliquée à `register` ET `change-password`. Rejet = `WEAK_PASSWORD` (PERMANENT, coordonné : `ErrorCode` + assertion `ErrorCodeContractTest`) au message **énumérant les règles** (endpoint public, pas anti-énumération).
- **AC #2/#3 (révocation, Approche A)** : colonne `users.token_version` (migration V6), claim `tv` dans le JWT, comparaison au `JwtAuthFilter` (lookup PK). `revokeSessions(userId)` = incrément unique réutilisable (logout, change-password ; role/désactivation câblables plus tard — 7.2/AD-21). Endpoints `POST /auth/logout` et `POST /auth/change-password` (authentifiés). Prouvé bout-en-bout : après logout ou changement de mdp, l'ancien jeton non expiré → **403**.
- **Frontend minimal** : `logoutUser` + `auth.js logout()` déclenche la révocation serveur (fire-and-forget). Hygiène appareil partagé/file offline = Story 1.9 (non touchée).
- **Frontière respectée** : pas de refresh token (Approche A), pas de reset/TOTP (2.6), pas de `TOKEN_REVOKED` distinct (un 403 suffit à l'AC ; UX révoqué/expiré = 1.9). `revokeSessions` prêt pour 2.6.
- **Report** : cache court du lookup filtre (perf) — non nécessaire au MVP mono-instance, à réévaluer sous charge (Story 11.8). Consigné.
- Tests : backend **343/343** (PasswordPolicyTest, AuthServiceTest étendu, PasswordAndRevocationIntegrationTest 5 cas E2E, contrat) ; frontend **172/172** (auth store logout). BUILD SUCCESS.

### File List

- backend/src/main/resources/db/migration/V6__user_token_version.sql (nouveau)
- backend/src/main/java/com/zlecaf/escrow/domain/User.java (champ tokenVersion)
- backend/src/main/java/com/zlecaf/escrow/domain/ErrorCode.java (WEAK_PASSWORD)
- backend/src/main/java/com/zlecaf/escrow/security/JwtService.java (claim tv)
- backend/src/main/java/com/zlecaf/escrow/security/JwtAuthFilter.java (lookup + comparaison tv)
- backend/src/main/java/com/zlecaf/escrow/security/PasswordPolicy.java (nouveau)
- backend/src/main/java/com/zlecaf/escrow/service/AuthService.java (politique register, changePassword, revokeSessions)
- backend/src/main/java/com/zlecaf/escrow/web/AuthController.java (logout, change-password)
- backend/src/main/java/com/zlecaf/escrow/web/dto/AuthDtos.java (ChangePasswordRequest, @Size relâché)
- backend/src/main/resources/application.yml (escrow.auth.password.*)
- backend/src/test/java/com/zlecaf/escrow/security/PasswordPolicyTest.java (nouveau)
- backend/src/test/java/com/zlecaf/escrow/security/PasswordAndRevocationIntegrationTest.java (nouveau)
- backend/src/test/java/com/zlecaf/escrow/service/AuthServiceTest.java (constructeur + nouveaux cas)
- backend/src/test/java/com/zlecaf/escrow/domain/ErrorCodeContractTest.java (assertion WEAK_PASSWORD)
- frontend/src/api/auth.js (logoutUser)
- frontend/src/stores/auth.js (logout révoque côté serveur)
- frontend/src/stores/__tests__/auth.spec.js (nouveau)
- _bmad-output/implementation-artifacts/sprint-status.yaml + ce fichier

## Change Log

- 2026-07-25 : Story créée (context engine). Décision d'architecture tranchée (révocation = Approche A `token_version`, résout l'assumption refresh-token du spine vers le minimal). Frontières 2.6 (reset/TOTP consomme `revokeSessions`), 1.9 (hygiène client), 1.10 (anti-énumération) explicitées.
