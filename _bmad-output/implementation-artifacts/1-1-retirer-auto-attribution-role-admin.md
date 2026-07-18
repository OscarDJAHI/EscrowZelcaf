---
baseline_commit: 8874d09c8620e27f0d03573789c57b0792c4b4aa
---

# Story 1.1: Retirer l'auto-attribution du rôle ADMIN

Status: done

<!-- Portée PRODUCTION. Source : _bmad-output/planning-artifacts/epics-production.md (Epic 1, Story 1.1). Suivi : sprint-status-production.yaml. -->

## Story

As a plateforme,
I want que le rôle d'un compte soit décidé côté serveur et jamais choisi par le client à l'inscription,
so that personne ne puisse s'octroyer le rôle ADMIN qui arbitre les litiges et libère/rembourse les fonds.

## Contexte & criticité

**Faille vivante, exploitable aujourd'hui.** `AuthService.register` recopie le rôle fourni par le client (`user.setRole(request.role())`), et `TransactionAccess.resolveRole` traite `ADMIN` comme accès total (peut déclencher `RESOLVE_RELEASE`/`RESOLVE_REFUND`). Un simple `POST /api/v1/auth/register` avec `"role":"ADMIN"` donne un compte arbitre. C'est l'item **stop-ship** le plus court du backlog production (Epic 1). Sévérité : Critique. Effort : S.

## Acceptance Criteria

1. **ADMIN non auto-attribuable.**
   **Given** un appel `POST /api/v1/auth/register` avec `"role":"ADMIN"`
   **When** l'inscription est traitée
   **Then** la requête est rejetée en `400` (enveloppe d'erreur JSON de la plateforme), aucun compte n'est créé.

2. **Inscription non-privilégiée fonctionnelle.**
   **Given** un appel `register` avec `"role":"BUYER"` ou `"role":"SELLER"` (ou rôle absent → défaut `BUYER`)
   **When** l'inscription est traitée
   **Then** le compte est créé avec ce rôle non-privilégié et un token est renvoyé (comportement actuel préservé pour buyer/seller).

3. **Aucune montée en privilège via un rôle inconnu.**
   **Given** une valeur de rôle absente de {BUYER, SELLER} (ex. inconnue, ou ADMIN)
   **When** l'inscription est traitée
   **Then** elle n'aboutit jamais à un compte ADMIN (rejet, jamais de repli silencieux vers ADMIN).

4. **Octroi ADMIN côté serveur uniquement, audité.**
   **Given** le besoin d'un compte arbitre
   **When** il est créé via le chemin d'amorçage serveur (seed piloté par configuration, idempotent)
   **Then** l'action est journalisée (log applicatif) et le seed ne s'exécute que s'il n'existe aucun ADMIN.

5. **Écran d'inscription sans option Admin.**
   **Given** l'écran d'inscription public (`AuthView.vue`)
   **When** l'utilisateur choisit son rôle
   **Then** seules les options `Buyer` et `Seller` sont proposées ; l'option « Admin (arbitrator) » a disparu.

## Tasks / Subtasks

- [x] **Backend — bloquer l'auto-ADMIN** (AC: 1, 2, 3)
  - [x] Dans `AuthService.register`, rejeter tout rôle privilégié : si `request.role() == Role.ADMIN` → `BadRequestException` (mappée en 400 par `GlobalExceptionHandler`). Traiter un rôle nul comme `BUYER` par défaut.
  - [x] Décider et documenter la posture : le client ne peut sélectionner que {BUYER, SELLER} ; l'octroi ADMIN passe exclusivement par le seed serveur (§ tâche seed). NB : l'autorisation transactionnelle réelle est dérivée de l'identité acheteur/vendeur, pas du rôle de compte (voir `Role.java` javadoc et `TransactionAccess.resolveRole`), donc restreindre le rôle à l'inscription ne casse pas les parcours buyer/seller.
  - [x] Optionnel (défense en profondeur) : garder `@NotNull Role role` OU relâcher en optionnel avec défaut ; ne PAS ajouter ADMIN à une quelconque allowlist de binding client.
- [x] **Backend — amorçage ADMIN idempotent** (AC: 4)
  - [x] Ajouter un `CommandLineRunner` (ou `ApplicationRunner`) piloté par configuration (`escrow.bootstrap.admin.email` + hash/mot de passe via env, ex. `ESCROW_BOOTSTRAP_ADMIN_*`) qui crée un compte ADMIN **uniquement si aucun ADMIN n'existe** (`users.existsByRole(ADMIN)` ou équivalent) et si la config est fournie.
  - [x] Journaliser la création (log INFO nommant l'email, sans le secret). Ne rien faire si la config est absente (pas d'ADMIN par défaut en clair — cohérent avec Story 1.2 « échec/absence de secret »).
  - [x] Ajouter la méthode repo nécessaire (`UserRepository.existsByRole(Role)`).
- [x] **Frontend — retirer l'option Admin** (AC: 5)
  - [x] Supprimer `<option value="ADMIN">Admin (arbitrator)</option>` dans `AuthView.vue:100`.
  - [x] Vérifier que `form.role` par défaut reste `'BUYER'` (`AuthView.vue:16`) et que le `<select>` ne propose que Buyer/Seller.
- [x] **Tests** (AC: 1-4)
  - [x] Backend : test asservissant `register(role=ADMIN)` → 400 (niveau web `MockMvc` et/ou service) ; `register(role=BUYER|SELLER)` → 201 + token ; rôle absent → défaut BUYER.
  - [x] Backend : test du seed — crée l'ADMIN quand aucun n'existe, idempotent (ne recrée pas au 2e démarrage), no-op sans configuration.
  - [x] Mettre à jour tout test existant qui inscrivait un ADMIN via l'API (rechercher les usages `role.*ADMIN` dans `backend/src/test`).
  - [x] `mvn -q test` vert.

## Dev Notes

### Fichiers à modifier (état actuel → changement → à préserver)

- **`backend/src/main/java/com/zlecaf/escrow/service/AuthService.java`** (UPDATE)
  - *Actuel* : `register()` fait `user.setRole(request.role())` (ligne ~37) — recopie aveugle du rôle client.
  - *Change* : refuser `ADMIN` (400) ; défaut `BUYER` si nul.
  - *Préserver* : la logique existante (normalisation email `.trim().toLowerCase()`, `existsByEmail` → 409 `ConflictException`, hash BCrypt, génération de token). Ne pas toucher `login()`.

- **`backend/src/main/java/com/zlecaf/escrow/web/dto/AuthDtos.java`** (UPDATE éventuel)
  - *Actuel* : `RegisterRequest(... @NotNull Role role)`. `Role` est l'enum {BUYER, SELLER, ADMIN}.
  - *Change* : rien d'obligatoire ; la garde est côté service. Si tu veux durcir au binding, documente que ADMIN reste refusé côté service (source de vérité unique).

- **`backend/src/main/java/com/zlecaf/escrow/domain/Role.java`** (lecture)
  - Javadoc clé : *« le rôle stocké ici est la capacité première du compte, tandis que l'autorisation au niveau transaction est dérivée de l'identité acheteur/vendeur »*. Donc restreindre le rôle d'inscription n'affecte pas les gardes transactionnelles (`EscrowStateMachine` + identité). Seul `ADMIN` confère un pouvoir réel via `TransactionAccess.resolveRole` (ligne ~38 : `ParticipantRole.ADMIN`).

- **`backend/src/main/java/com/zlecaf/escrow/repository/UserRepository.java`** (UPDATE)
  - Ajouter `boolean existsByRole(Role role)` pour l'idempotence du seed.

- **Nouveau : `backend/.../config/AdminBootstrap.java`** (NEW — `CommandLineRunner`/`@Configuration`)
  - Crée l'ADMIN d'amorçage si config présente ET aucun ADMIN existant. Idempotent. S'aligne sur le pattern des autres `config/*.java` (`SecurityConfig`, `StorageConfig`…). Réutilise `PasswordEncoder` et `UserRepository`.

- **`frontend/src/views/AuthView.vue`** (UPDATE)
  - *Actuel* : `<select v-model="form.role">` avec options Buyer/Seller/Admin (lignes 98-100), défaut `role: 'BUYER'` (ligne 16).
  - *Change* : retirer la ligne 100 (option ADMIN). Préserver le binding `form.role` et le POST d'inscription.

### Décision de conception (à figer par le dev)

Le client ne peut s'inscrire qu'en **rôle non-privilégié** ({BUYER, SELLER}). Toute demande ADMIN est **rejetée en 400** (pas ignorée silencieusement) pour un signal clair. L'ADMIN est octroyé **exclusivement** par le seed serveur idempotent — pont minimal jusqu'à l'outillage d'admin complet de l'**Epic 7 (Story 7.4 / gestion des membres)**. Cette formulation raffine l'exemple de l'epic (« défaut BUYER ») en « restreint aux rôles non-privilégiés », plus fidèle à la sémantique buyer/seller existante.

### Standards de test

- Framework backend : JUnit 5 + Spring Boot Test ; les tests web existants utilisent `MockMvc` (`standaloneSetup` + `GlobalExceptionHandler`), les tests d'intégration Postgres/MinIO via **Testcontainers**. 229 tests backend actuellement verts — ne pas régresser.
- Frontend : Vitest + jsdom (harness monté en Story 4.1). Un test simple sur l'absence de l'option ADMIN dans `AuthView` est un plus, non bloquant.
- Le mapping `BadRequestException → 400` est déjà centralisé dans `GlobalExceptionHandler` ; s'appuyer dessus plutôt que de renvoyer un code à la main.

### Project Structure Notes

- Backend : `com.zlecaf.escrow` en couches `web` (contrôleurs/DTO) → `service` → `repository`/`domain`, config sous `config/`. Le nouveau bootstrap va dans `config/`.
- Frontend : Vue 3 + Pinia, vues sous `src/views`, stores sous `src/stores`. Seul `AuthView.vue` est touché.
- Aucune migration Flyway nécessaire (pas de changement de schéma ; `users.role` existe déjà).

### References

- Épic & AC : [Source: _bmad-output/planning-artifacts/epics-production.md#Epic 1 : Sécurité & protection des données]
- Faille & preuve : [Source: _bmad-output/implementation-artifacts/production-backlog.html — Epic SEC, item « Auto-enregistrement en rôle ADMIN »]
- Code : `backend/src/main/java/com/zlecaf/escrow/service/AuthService.java:37`, `web/dto/AuthDtos.java:15-20`, `domain/Role.java`, `service/TransactionAccess.java:37-48`, `frontend/src/views/AuthView.vue:16,98-100`

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (bmad-dev-story)

### Debug Log References

- `mvn test` (backend) : **235/235 verts** (229 baseline + 6 nouveaux), aucune régression. Testcontainers via Docker local.
- `npm run test` (frontend) : **170/170 verts**.

### Completion Notes List

- **Garde anti-ADMIN** (`AuthService.register`) : une demande explicite `role=ADMIN` lève `BadRequestException` (→ 400 via `GlobalExceptionHandler`, mapping déjà prouvé par `GlobalExceptionHandlerTest`). Rôle absent → défaut `BUYER`. `SELLER`/`BUYER` acceptés. Le parcours buyer/seller reste intact (l'autorisation transactionnelle est par identité, pas par rôle de compte).
- **Amorçage ADMIN** (`AdminBootstrap`, nouveau `CommandLineRunner`) : seul chemin d'octroi. Crée un ADMIN si `escrow.bootstrap.admin.email`+`.password` fournis ET aucun ADMIN existant (idempotent). No-op sans configuration → jamais d'ADMIN par défaut, cohérent avec le futur durcissement des secrets (Story 1.2). `UserRepository.existsByRole` ajouté pour l'idempotence.
- **Frontend** : option « Admin (arbitrator) » retirée de `AuthView.vue`.
- **Contrat DTO** : `RegisterRequest.role` passé de `@NotNull` à optionnel (défaut serveur BUYER) ; import `@NotNull` retiré (devenu inutilisé).
- **Tests** : `AuthServiceTest` (3) — rejet ADMIN sans save, SELLER accepté + token, null→BUYER ; `AdminBootstrapTest` (3) — seed si configuré & aucun ADMIN (email normalisé), idempotent si ADMIN existe, no-op sans config.
- **Décision figée** : inscription restreinte à {BUYER, SELLER} avec rejet 400 de l'ADMIN (pas un repli silencieux) ; ADMIN via seed serveur uniquement (pont jusqu'à l'Epic 7).
- **Note de vérification** : la stack Docker en cours d'exécution tourne encore l'image backend construite AVANT ce correctif — un `docker compose ... up --build backend` est nécessaire pour refléter le changement en live. Le correctif est prouvé au niveau unitaire.

### Change Log

- 2026-07-18 — Story 1.1 implémentée (Epic 1 Sécurité) : rôle ADMIN non auto-attribuable à l'inscription (400), défaut BUYER, amorçage ADMIN serveur idempotent, option Admin retirée du front. 235 tests backend + 170 frontend verts.
- 2026-07-18 — Revue de code (3 couches adversariales) : 3 patchs appliqués — (P1) garde `register` en liste blanche {BUYER, SELLER} au lieu d'une liste noire ADMIN ; (P2) mot de passe d'amorçage ADMIN ≥ 6 (fail-fast si config invalide) ; (P3) `save` du seed catché sur `DataIntegrityViolationException` (idempotent sous course multi-instances, plus de crash au démarrage). +3 tests. **238 tests backend verts**. 3 findings différés au ledger (remédiation ADMIN historiques, rotation/collision email → Epic 7, course register→500 pré-existante).

### File List

- `backend/src/main/java/com/zlecaf/escrow/service/AuthService.java` (M) — garde anti-ADMIN + défaut BUYER
- `backend/src/main/java/com/zlecaf/escrow/web/dto/AuthDtos.java` (M) — `role` optionnel (retrait `@NotNull`)
- `backend/src/main/java/com/zlecaf/escrow/repository/UserRepository.java` (M) — `existsByRole`
- `backend/src/main/java/com/zlecaf/escrow/config/AdminBootstrap.java` (NEW) — amorçage ADMIN idempotent
- `backend/src/test/java/com/zlecaf/escrow/service/AuthServiceTest.java` (NEW) — 3 tests
- `backend/src/test/java/com/zlecaf/escrow/config/AdminBootstrapTest.java` (NEW) — 3 tests
- `frontend/src/views/AuthView.vue` (M) — retrait de l'option Admin
