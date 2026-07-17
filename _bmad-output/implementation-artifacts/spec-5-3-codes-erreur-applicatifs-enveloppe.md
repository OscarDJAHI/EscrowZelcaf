---
title: 'Contrat d''erreur codé (prérequis backend de la réconciliation)'
type: 'feature'
created: '2026-07-17'
baseline_revision: '9e0e6f5c9c35b149cffb25f1f8ed3d894bd820b4'
status: 'in-progress'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** L'enveloppe d'erreur (`GlobalExceptionHandler.body`, `Map.of` de `timestamp/status/error/message`) ne porte **aucun code applicatif** : `error` n'est que la reason-phrase HTTP. AD-10 exige que le front classe chaque rejet de rejeu en transitoire vs permanent **par code stable**, jamais par la classe HTTP ni par le texte du `message` (interpolé, non verrouillé par test). Sans ce contrat, les Stories 4.3/4.4/4.5 sont inimplémentables — c'est l'escalation CRITICAL de la 4.3.

**Approach:** Introduire une énumération faisant foi `ErrorCode` (SCREAMING_SNAKE_CASE, portant sa classe PERMANENT/TRANSIENT), la faire porter par chaque exception métier — en généralisant le précédent `TransitionException.Reason`, seule donnée machine déjà présente mais jamais sérialisée — et l'ajouter en champ `code` de l'enveloppe. Deux trous de granularité sont comblés : l'état terminal reçoit un code distinct de l'illégalité ordinaire, et la collision de verrou optimiste est mappée sur un `409` codé au lieu du `500` par défaut de Spring.

## Boundaries & Constraints

**Always:**
- `code` est présent et **non nul** dans **toute** enveloppe construite par `GlobalExceptionHandler` (`Map.of` rejette `null` → NPE : garde obligatoire). Sa valeur est le `name()` de l'enum, jamais du texte libre.
- **Additif uniquement** : `timestamp`, `status`, `error`, `message` gardent nom et sémantique. Le front lit `message` pour l'affichage (`err.response?.data?.message`, ~10 sites) et le chemin download le parse depuis un Blob — rien ne doit casser.
- Le code vient du **site de lancement**, pas du type d'exception : plusieurs sens partagent aujourd'hui un même type + texte libre (c'est le défaut à corriger). Les types conservent un constructeur `(String)` avec un code **par défaut explicite** par type, pour que les sites non énumérés restent compilables et honnêtes.
- **Opacité partenaire préservée** : les 6 sites d'échec d'auth HMAC (clé inconnue, signature invalide, horodatage périmé/illisible, rejeu de nonce) partagent **un seul** code opaque `AUTH_FAILED`. Les distinguer permettrait d'énumérer les key-ids existants — l'anti-énumération est intentionnelle et documentée (`PartnerEvidenceService:80-83`).
- Aucun code ne divulgue de détail interne (clé de stockage, SQL, classe d'exception).
- Règle de dépendance descendante respectée : `ErrorCode` vit dans `domain/`, importable par `service/` comme par `web/`.

**Block If:**
- Satisfaire un code exigerait de **changer un statut HTTP déjà verrouillé par un test existant** (seule exception autorisée et voulue : la collision optimiste `500` → `409`).
- L'énumération faisant foi entrerait en contradiction avec un comportement prouvé par un test existant.

**Never:**
- **Aucune modification frontend** : la 4.3 consommera ces codes et les mirroitera (convention `utils/`). Ne pas créer de fichier de codes côté front ici.
- Ne pas sérialiser la classe PERMANENT/TRANSIENT : l'enveloppe porte `code` (ce que l'AC demande), la **politique** de retry appartient au front. L'enum reste la source de vérité, verrouillée par test.
- Ne pas ajouter de `@ExceptionHandler(Exception.class)` fourre-tout, ni d'`AuthenticationEntryPoint`/`AccessDeniedHandler`/`ErrorController`. Les 401/403 de Spring Security et les 404 de route restent hors enveloppe (corps par défaut `{timestamp,status,error,path}`, sans `code`) — **le front doit tolérer l'absence de `code`**, ce n'est pas un trou à combler ici.
- Ne pas propager le code dans la charge d'audit (`recordFailure` stocke le `message`) — hors périmètre.
- Ne pas réordonner ni fusionner les gardes existantes ; ne pas toucher aux textes des `message`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Litige déjà résolu | `OPEN_DISPUTE` sur tx `REFUNDED` (ou `RELEASED` **avec** une ligne d'audit `OPEN_DISPUTE→DISPUTED` réussie) | `409`, `code: DISPUTE_ALREADY_RESOLVED` | Permanent |
| Transaction terminale | `OPEN_DISPUTE` sur tx `RELEASED` **sans** litige antérieur (chemin `DELIVERY_CONFIRMED`) | `409`, `code: TRANSACTION_TERMINAL` | Permanent |
| Illégalité ordinaire | Événement hors whitelist depuis un état **non** terminal | `409`, `code: ILLEGAL_TRANSITION` — distinct des deux ci-dessus | Permanent |
| Rôle non autorisé | Transition existante, mauvais rôle (`Reason.UNAUTHORIZED` actuel) | `403`, `code: UNAUTHORIZED_TRANSITION` | Statut inchangé |
| Fenêtre fermée | Dépôt/retrait sur état `!allowsEvidenceMutation()` **non** terminal (`INITIATED`) | `409`, `code: WINDOW_CLOSED` | Permanent |
| Fenêtre fermée par terminalité | Dépôt/retrait sur `RELEASED`/`REFUNDED` | `409`, `code: TRANSACTION_TERMINAL` (premier appelant de `isTerminal()`) | Permanent |
| Collision optimiste | `ObjectOptimisticLockingFailureException` (aujourd'hui : `500` hors enveloppe) | `409`, `code: CONCURRENT_MODIFICATION`, dans l'enveloppe | **Transitoire** |
| Fichier illisible | `IOException` sur `read(MultipartFile)` | `400` (statut inchangé), `code: FILE_READ_ERROR` | **Transitoire** — prouve que code ≠ statut |
| Preuve invalide | 6 textes distincts (vide, type interdit, extension/Content-Type incohérents, taille) + `MaxUploadSizeExceededException` | `400`, `code: EVIDENCE_INVALID` pour les 7 | Permanent |
| Auth partenaire | N'importe lequel des 6 échecs HMAC | `401`, `code: AUTH_FAILED` — **indistinguables entre eux** | Permanent, opaque |
| Stockage objet KO | `EvidenceStorageException` | `502`, `code: STORAGE_UNAVAILABLE`, message inchangé | **Transitoire** |
| Code jamais nul | Branche de handler sans code explicite | Défaut par type, jamais `null` | Sinon `Map.of` NPE |

</intent-contract>

## Code Map

- `backend/.../domain/ErrorCode.java` -- **nouveau**. Enum faisant foi : constante + `Retryability {PERMANENT, TRANSIENT}` par constante. Dans `domain/` pour rester importable par `service/` et `web/` sans inverser la dépendance.
- `backend/.../service/TransitionException.java` -- `Reason {ILLEGAL_TRANSITION, UNAUTHORIZED}` (:9-14) → remplacé par un `ErrorCode`. Seul précédent de donnée machine-readable, jamais sérialisé : c'est le point d'appui à généraliser.
- `backend/.../web/ApiExceptions.java` -- 5 types (`NotFound/BadRequest/Conflict/Forbidden/Unauthorized`), ctor `(String)` unique, zéro donnée structurée (:8-26). Ajouter un champ `ErrorCode` + ctor `(ErrorCode, String)` ; garder `(String)` avec défaut par type.
- `backend/.../web/GlobalExceptionHandler.java` -- `body(HttpStatus, String)` (:22-28) est le **constructeur unique** des 10 branches → y ajouter `code`. Fork de statut de `TransitionException` (:32-34) à rebrancher sur le code. Nouveau handler `ObjectOptimisticLockingFailureException` → `409` (aucun n'existe : `grep OptimisticLock` = 0 hit hors `@Version`).
- `backend/.../service/EscrowStateMachine.java` -- `determineNextState` (:61-73) : l'unique branche `ILLEGAL_TRANSITION` (:64-66) absorbe tout, y compris `RELEASED`/`REFUNDED` (aucune entrée MATRIX). Y forker sur `current.isTerminal()`. Composant **pur, sans dépendance** — il doit le rester (le fork est une fonction de `current`, aucun repo n'y entre).
- `backend/.../domain/EscrowState.java` -- `isTerminal()` (:26) = `RELEASED|REFUNDED`, **zéro appelant en main** ; `allowsEvidenceMutation()` (:31) = source de vérité de la fenêtre.
- `backend/.../service/EscrowService.java` -- `openDispute` : le catch existant `recordFailure` + rethrow (:167-173) est le point d'affinage `TRANSACTION_TERMINAL` → `DISPUTE_ALREADY_RESOLVED`. Sites à coder : `:151` COMMENT_TOO_SHORT, `:157` TOO_MANY_FILES, `:108/:162/:208` TRANSACTION_NOT_FOUND.
- `backend/.../repository/AuditLogRepository.java` -- ajouter un `exists…` dérivé prouvant un `OPEN_DISPUTE→DISPUTED` réussi (index `idx_audit_transaction` présent). Vérifier au code les noms exacts des champs de `AuditLog` (`nextState`, `payload` JSONB `outcome`) — `recordSuccess(..., previous, DISPUTED)` à `EscrowService:178` est la ligne à retrouver.
- `backend/.../service/EvidenceService.java` -- `requireUploadWindow` (:418) / `requireWithdrawWindow` (:425) : forker terminal vs fenêtre. Sites : `:505` FILE_READ_ERROR, `:403` EVIDENCE_FLOOR_VIOLATION, `:241` TOO_MANY_FILES, `:168/:171` EVIDENCE_INVALID, `:103/:135/:259/:299/:375` TRANSACTION_NOT_FOUND.
- `backend/.../service/EvidenceContentValidator.java` -- `:35/:40/:46/:52` → EVIDENCE_INVALID (4 des 6 textes).
- `backend/.../service/TransactionAccess.java` -- `:46/:62` → NOT_A_PARTY (les deux, y compris la variante partenaire).
- `backend/.../service/PartnerEvidenceService.java` + `PartnerSignatureVerifier.java` -- les 6 sites d'`UnauthorizedException` (`PES:90,106` ; `PSV:77,83,86,113`) → **une seule** constante `AUTH_FAILED`.
- `backend/src/test/.../web/EvidenceStorageErrorMappingTest.java` -- **unique** test verrouillant l'enveloppe (:57-60, 4 champs). Exemplaire du `standaloneSetup(...).setControllerAdvice(new GlobalExceptionHandler())` (:43-47) — patron des nouveaux tests de handler, sans contexte Spring.

## Tasks & Acceptance

**Execution:**
- [x] `backend/.../domain/ErrorCode.java` -- créer l'enum faisant foi : les 9 PERMANENT d'AD-10 (`DISPUTE_ALREADY_RESOLVED`, `TRANSACTION_TERMINAL`, `WINDOW_CLOSED`, `EVIDENCE_INVALID`, `NOT_A_PARTY`, `TRANSACTION_NOT_FOUND`, `EVIDENCE_FLOOR_VIOLATION`, `COMMENT_TOO_SHORT`, `TOO_MANY_FILES`), les TRANSIENT (`CONCURRENT_MODIFICATION`, `FILE_READ_ERROR`, `STORAGE_UNAVAILABLE`), et les codes de complétude nécessaires pour que **toute** exception en porte un (`ILLEGAL_TRANSITION`, `UNAUTHORIZED_TRANSITION`, `AUTH_FAILED`, `VALIDATION_ERROR`, `MISSING_REQUEST_PART`, + défauts par type : `INVALID_REQUEST`, `RESOURCE_NOT_FOUND`, `CONFLICT`, `FORBIDDEN`) -- chaque constante déclare sa `Retryability`, aucun défaut implicite.
- [x] `backend/.../service/TransitionException.java` -- remplacer `Reason` par `ErrorCode` (généralisation, cf. AC epics « plus jeté ») ; `Objects.requireNonNull` sur le code.
- [x] `backend/.../web/ApiExceptions.java` -- ajouter champ `ErrorCode` + getter + ctor `(ErrorCode, String)` sur les 5 types ; conserver `(String)` déléguant au défaut du type -- ~30 sites non énumérés restent compilables et codés.
- [x] `backend/.../web/GlobalExceptionHandler.java` -- `body(HttpStatus, ErrorCode, String)` ajoute `"code", code.name()` ; rebrancher le fork de statut de `TransitionException` sur le code (`UNAUTHORIZED_TRANSITION` → 403, sinon 409) ; coder les branches framework (`MaxUploadSizeExceeded` → EVIDENCE_INVALID, parts/params/headers manquants → MISSING_REQUEST_PART, `MethodArgumentNotValid` → VALIDATION_ERROR, `EvidenceStorageException` → STORAGE_UNAVAILABLE) ; **nouveau** handler `ObjectOptimisticLockingFailureException` → `409` / `CONCURRENT_MODIFICATION`.
- [x] `backend/.../service/EscrowStateMachine.java` -- au miss de whitelist, forker : `current.isTerminal()` → `TRANSACTION_TERMINAL`, sinon `ILLEGAL_TRANSITION` ; le site de rôle → `UNAUTHORIZED_TRANSITION`. Ne pas introduire de dépendance dans ce composant pur ; messages inchangés.
- [x] `backend/.../repository/AuditLogRepository.java` -- ajouter la requête dérivée `exists…` (transaction + `nextState = DISPUTED` + succès) -- discriminant durable de « litige antérieur ».
- [x] `backend/.../service/EscrowService.java` -- dans le catch `TransitionException` existant d'`openDispute` (:167-173, qui **écrit déjà** dans `audit_logs` via `recordFailure`), affiner `TRANSACTION_TERMINAL` → `DISPUTE_ALREADY_RESOLVED` si l'audit prouve un litige antérieur ; coder les sites `:151`, `:157`, `:108/:162/:208`.
- [x] `backend/.../service/EvidenceService.java` -- gardes de fenêtre : `state.isTerminal()` → `TRANSACTION_TERMINAL`, sinon `WINDOW_CLOSED` ; coder `:505` FILE_READ_ERROR, `:403`, `:241`, `:168/:171`, et les 5 sites TRANSACTION_NOT_FOUND.
- [x] `backend/.../service/EvidenceContentValidator.java`, `.../service/TransactionAccess.java` -- coder EVIDENCE_INVALID (4 sites) et NOT_A_PARTY (2 sites).
- [x] `backend/.../service/PartnerEvidenceService.java`, `.../service/PartnerSignatureVerifier.java` -- les 6 sites d'auth partagent `AUTH_FAILED` (constante unique) -- opacité anti-énumération préservée.
- [x] Tests backend -- nouveau `GlobalExceptionHandlerTest` (patron standalone de `EvidenceStorageErrorMappingTest:43-47`) : présence + valeur de `code` par branche, `409 CONCURRENT_MODIFICATION`, fork 403/409 de `TransitionException` ; nouveau `ErrorCodeContractTest` verrouillant la partition faisant foi ; tests de code par exception (terminal vs ordinaire vs fenêtre, DISPUTE_ALREADY_RESOLVED avec et sans litige antérieur — dont `RELEASED` par `DELIVERY_CONFIRMED` → TRANSACTION_TERMINAL) ; étendre `EvidenceStorageErrorMappingTest` avec `$.code`.

**Acceptance Criteria:**
- Given n'importe quelle exception rendue par `GlobalExceptionHandler`, when la réponse est sérialisée, then l'enveloppe porte un `code` non nul en SCREAMING_SNAKE_CASE, distinct de `error` (reason-phrase HTTP), et `timestamp/status/error/message` sont inchangés ; un test verrouille le contrat.
- Given l'énumération faisant foi, when on la lit, then les 9 codes d'AD-10 sont `PERMANENT` et `CONCURRENT_MODIFICATION`/`FILE_READ_ERROR` sont `TRANSIENT` ; un test échoue si un renommage ou une reclassification survient — la réconciliation de la 4.3 ne peut pas casser en silence.
- Given une transaction `RELEASED` sans litige antérieur **et** une `REFUNDED`, when un `OPEN_DISPUTE` est rejoué sur chacune, then les codes sont respectivement `TRANSACTION_TERMINAL` et `DISPUTE_ALREADY_RESOLVED`, tous deux distincts de l'`ILLEGAL_TRANSITION` d'un miss ordinaire depuis un état non terminal.
- Given la suite de tests, when on exécute `cd backend && mvn test`, then tout passe — les 171 tests existants inclus, aucun statut HTTP existant modifié hormis la collision optimiste (`500` → `409`).

## Spec Change Log

## Review Triage Log

## Design Notes

**Pourquoi le code vient du site, pas du type.** Le défaut central n'est pas l'absence de champ mais l'absence de granularité : `BadRequestException` porte à lui seul « preuve invalide » (6 textes), « trop de fichiers », « commentaire trop court » **et** « fichier illisible » — ce dernier étant *transitoire* quand les autres sont permanents. Un code dérivé du type reproduirait exactement l'ambiguïté qu'AD-10 interdit. D'où : code au throw, défaut par type pour le reste.

**Terminal vs ordinaire — ce qui est honnêtement décidable.** `REFUNDED` n'est atteignable que via `DISPUTED` (matrice `:48-51`) ; `RELEASED` l'est aussi par `SHIPPED --DELIVERY_CONFIRMED-->` (`:44-45`), donc l'état seul **ne peut pas** distinguer « litige résolu » de « transaction terminée normalement ». Aucune entité `Dispute` n'existe (le litige n'est que `EscrowState.DISPUTED`), mais `audit_logs` conserve durablement la ligne `OPEN_DISPUTE→DISPUTED` (`EscrowService:178`). Le rejet passe **déjà** par un `INSERT` d'audit (`recordFailure`, :172) : y ajouter un `SELECT` indexé est négligeable et n'introduit aucune dépendance nouvelle. Le fork de terminalité reste dans le state machine (pur, `isTerminal()` enfin appelé) ; l'affinage, qui exige le dépôt, reste dans le service.

```java
// EscrowStateMachine.determineNextState — au miss de whitelist
throw new TransitionException(
        current.isTerminal() ? ErrorCode.TRANSACTION_TERMINAL : ErrorCode.ILLEGAL_TRANSITION,
        "Event %s is not permitted from state %s".formatted(event, current)); // message inchangé
```

**Le piège `Map.of`.** `body()` est le constructeur unique des 10 branches et `Map.of` lève un NPE sur valeur nulle : un code manquant transformerait une erreur métier propre en `500`. Le défaut par type garantit la totalité ; `requireNonNull` le prouve tôt.

## Verification

**Commands:**
- `cd /Users/Oscard/Projects/Escrow_claude/backend && mvn test` -- expected: BUILD SUCCESS, tests existants (171) + nouveaux verts.

**Manual checks (if no CLI):**
- `grep -rn "AUTH_FAILED" backend/src/main/java` : les 6 sites d'auth partenaire, aucun code d'auth plus fin.
- `git diff --stat frontend/` : vide (aucune modification front dans cette story).
