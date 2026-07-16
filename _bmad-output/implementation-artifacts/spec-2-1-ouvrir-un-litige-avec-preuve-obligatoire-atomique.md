---
title: 'Ouvrir un litige avec preuve obligatoire (atomique)'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: '8d34f93f1ad1dba907d8717ccf059fd27ecbc1b4'
final_revision: 'c00527eac0cdd779ae63bfe53ff821683d0e59e8'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Aujourd'hui un litige ne peut s'ouvrir que via la transition générique `POST /{id}/event` (`OPEN_DISPUTE`), sans exiger la moindre preuve : un litige peut donc exister « à vide », privant l'arbitre de tout dossier.

**Approach:** Ajouter un endpoint composite `POST /api/v1/escrow/{id}/dispute` (multipart) qui, dans une **unique** frontière `@Transactional`, fait passer la transaction à `DISPUTED` **et** attache la/les preuve(s) en réutilisant intégralement la logique d'ingestion d'Epic 1 (`EvidenceService.deposit`) — aucune règle de validation dupliquée, rollback atomique garanti.

## Boundaries & Constraints

**Always:**
- L'ouverture est **composite et atomique** : transition d'état + dépôt de preuve(s) dans la même transaction. Tout échec (validation fichier, stockage MinIO indisponible, transition illégale) annule l'intégralité — ni passage à `DISPUTED`, ni ligne `evidence_files`, ni audit de succès.
- Exiger **≥ 1 fichier** ET un **commentaire de ≥ 10 caractères** (après `trim`) ; à défaut → `400` avant toute transition.
- La validation d'ingestion (content-sniffing Tika, bornes de taille, mime whitelist, clé opaque, audit `EVIDENCE_ADDED`) est **réutilisée telle quelle** via `EvidenceService.deposit` — zéro duplication (AR-13).
- L'autorisation de transition passe par `EscrowStateMachine.determineNextState(state, OPEN_DISPUTE, role)` (source unique de vérité) ; le contrôle d'appartenance par `TransactionAccess.resolveRole` (anti-IDOR).
- Ordonnancement obligatoire : transitionner vers `DISPUTED` **puis** appeler `deposit`. La fenêtre d'upload de `deposit` est `{FUNDS_LOCKED, SHIPPED, DISPUTED}` (le dépôt passerait dans les trois cas), mais ouvrir la transition d'abord garantit qu'un rejet ultérieur du dépôt annule aussi la transition dans la même unité atomique.
- Audit : la transition écrit `recordSuccess(OPEN_DISPUTE)` et chaque preuve écrit `recordEvidenceAdded` (propagation MANDATORY, même transaction). Une transition rejetée écrit `recordFailure` (REQUIRES_NEW), comme `applyEvent`.

**Block If:**
- Aucun blocage attendu : toutes les briques (state machine, deposit réutilisable, audit, rollback-cleanup) existent déjà.

**Never:**
- Ne pas dupliquer la validation de fichiers ni ré-implémenter le stockage/audit.
- Ne pas créer un endpoint d'ouverture « sans preuve » ni exposer `OPEN_DISPUTE` autrement que composite.
- Hors périmètre : verrou aux états terminaux (Story 2.2), retrait de pièce (Story 2.3), UI PWA (Story 2.4), tests de concurrence sur plancher (Story 2.3).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Ouverture nominale | tx `FUNDS_LOCKED`, acteur partie prenante, 1..N fichiers valides, `comment` ≥ 10 car. | tx → `DISPUTED`, pièces `ACTIVE` créées, audit transition + `EVIDENCE_ADDED`, `200 OK` avec `{transaction (DISPUTED), evidence:[...]}` | Aucune |
| Sans fichier | `files` vide/absent | Aucune transition | `400` |
| Commentaire trop court | `comment` < 10 car. (après trim) ou absent | Aucune transition | `400` |
| Fichier invalide | mauvais type (Tika), 0 octet, ou > 10 Mo | Rollback total : pas de `DISPUTED`, aucune ligne evidence — rejets identiques à `POST /evidence` | `400` |
| Stockage indisponible | `deposit` lève pendant le store (MinIO down) | Rollback atomique : pas de `DISPUTED`, aucune ligne `evidence_files`, binaires déjà écrits nettoyés (`afterCompletion`) | `500`/propagée, transaction annulée |
| État non ouvrable | tx déjà `DISPUTED`, `RELEASED` ou `REFUNDED` | `OPEN_DISPUTE` absente de la matrice → transition illégale ; `recordFailure` écrit | `409` |
| Non partie prenante | acteur ni acheteur ni vendeur ni admin | Rejet avant transition | `403` |
| Rôle non autorisé | tx `SHIPPED`, acteur = vendeur (matrice : seul BUYER ouvre après SHIPPED) | `recordFailure` écrit, pas de transition | `403` |
| Transaction inconnue | id inexistant | — | `404` |

</intent-contract>

## Code Map

- `backend/.../service/EscrowService.java` -- **MODIF** : ajouter `openDispute(actor, txId, files, comment, clientCapturedAt)` `@Transactional` ; injecter `EvidenceService`.
- `backend/.../web/EscrowController.java` -- **MODIF** : ajouter `POST /{id}/dispute` (multipart, `@RequestParam` `files`/`comment`/`clientCapturedAt`) → `200` `DisputeOpenedDto`.
- `backend/.../web/dto/EscrowDtos.java` -- **MODIF** : ajouter `record DisputeOpenedDto(TransactionDto transaction, List<EvidenceDto> evidence)`.
- `backend/.../service/EvidenceService.java` -- RÉUTILISÉ (`deposit`, non modifié) : validation + stockage + audit + rollback-cleanup.
- `backend/.../service/EscrowStateMachine.java` -- RÉUTILISÉ : matrice `(FUNDS_LOCKED|SHIPPED, OPEN_DISPUTE)→DISPUTED` déjà présente, non modifié.
- `backend/.../service/AuditService.java`, `TransactionAccess.java`, `TransitionException.java` -- RÉUTILISÉS.
- `backend/.../web/dto/EvidenceDtos.java` (`EvidenceDto.from`) -- RÉUTILISÉ pour mapper la réponse.
- `backend/src/test/.../service/EscrowDisputeServiceTest.java` -- **NOUVEAU** : test d'intégration (Testcontainers Postgres + fake `InMemoryEvidenceStorage`, sur le modèle d'`EvidenceServiceTest`).

## Tasks & Acceptance

**Execution:**
- [x] `backend/.../web/dto/EscrowDtos.java` -- ajouter `DisputeOpenedDto(TransactionDto, List<EvidenceDto>)` -- contrat de réponse composite.
- [x] `backend/.../service/EscrowService.java` -- ajouter `EvidenceService` au constructeur et implémenter `openDispute(...)` : garde `files` non vide + `comment.trim().length() ≥ 10` (400) → `findByIdForUpdate` (404) → `resolveRole` (403) → `determineNextState(state, OPEN_DISPUTE, role)` en try/catch `TransitionException` → `recordFailure`+rethrow (403/409) → `setState(DISPUTED)`+`save` → `recordSuccess(OPEN_DISPUTE)` → `evidenceService.deposit(actor, txId, files, comment, clientCapturedAt)` → retourner `DisputeOpenedDto` -- cœur composite atomique.
- [x] `backend/.../web/EscrowController.java` -- ajouter `POST /{id}/dispute` (consumes multipart, part `files` requis, `comment`/`clientCapturedAt` en `@RequestParam`) déléguant à `openDispute`, réponse `200` -- surface HTTP mince.
- [x] `backend/src/test/.../service/EscrowDisputeServiceTest.java` -- couvrir toute l'I/O & Edge-Case Matrix ci-dessus, dont **un test de rollback** (fake storage qui lève → tx reste `FUNDS_LOCKED`, 0 ligne evidence, pas d'audit de succès) et **un test de non-duplication** (fichier invalide rejeté `400` identiquement au dépôt simple) -- gate AD-1/AR-13. (11 tests, tous verts.)

**Acceptance Criteria:**
- Given une tx `FUNDS_LOCKED`/`SHIPPED` dont l'acteur est partie prenante autorisée, when il POST `/dispute` avec fichier(s) valide(s) + commentaire ≥ 10 car., then dans une seule transaction l'état passe à `DISPUTED`, la/les pièce(s) sont `ACTIVE`, et l'audit contient la transition ET l'ajout de preuve.
- Given une ouverture sans fichier ou commentaire < 10 car., when envoyée, then `400` et aucune transition.
- Given un échec de persistance de pièce, when l'ouverture s'exécute, then rollback total : pas de `DISPUTED`, aucune ligne `evidence_files`.
- Given une tx déjà `DISPUTED`/`RELEASED`/`REFUNDED`, when ouverture tentée, then `409`.
- Given un fichier invalide (type/0 octet/>10 Mo), when joint, then `400` — identique à `POST /{id}/evidence`.

## Spec Change Log

(Aucun loopback bad_spec — section vide.)

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3: (high 1, medium 2, low 0)
- defer: 2: (high 0, medium 1, low 1)
- reject: 10
- addressed_findings:
  - `[high]` `[patch]` Garantie « pas de litige sans preuve » contournable via `POST /{id}/event {"event":"OPEN_DISPUTE"}` → `EscrowService.applyEvent` rejette désormais `OPEN_DISPUTE` (400, renvoie vers `/dispute`) ; test `applyEventRejectsOpenDisputeToForceCompositeEndpoint` ajouté.
  - `[medium]` `[patch]` Test AD-1 de rollback stockage sur-permissif (`isInstanceOf(RuntimeException)`) → assertion durcie avec `hasMessageContaining("simulated object-store outage")`.
  - `[medium]` `[patch]` Ordre causal du trail d'audit non garanti quand transition + `EVIDENCE_ADDED` partagent le timestamp → nouvelle requête `findByTransactionIdOrderByTimestampAscIdAsc` (départage par id) consommée par `getDetail` ; assertion d'ordre ajoutée au happy-path.

### 2026-07-16 — Review pass (follow-up)
- intent_gap: 0
- bad_spec: 0
- patch: 0
- defer: 1: (high 0, medium 0, low 1)
- reject: 10
- addressed_findings:
  - none
- note: Relecture de suivi indépendante (2 relecteurs adverses). L'invariant central — transition `DISPUTED` + dépôt de preuve dans une unique transaction, rollback atomique — est confirmé sain par les deux relecteurs (propagations `MANDATORY`/`REQUIRES_NEW` correctes, nettoyage stockage au rollback, porte dérobée `/event` fermée). Aucun patch ni loopback `bad_spec`. Un seul report NEW (absence de test de couche web pour `/dispute`, sévérité faible). Les autres findings sont soit déjà au ledger (DoS lot multipart non borné ; oracle d'existence 403-vs-404 ; absence d'audit sur échec au stade dépôt), soit des décisions déjà assumées dans les Design Notes (double verrou réentrant, choix `200 OK`), soit spéculatifs/hors périmètre (couplage `DISPUTED∈UPLOAD_WINDOW`, ADMIN non ouvrant, 400 uniforme sur `/event`).

## Design Notes

**Nom du part multipart = `files`** (et non `files[]`) : la notation `files[]` des epics désigne le caractère multi-valué du part ; le contrat gelé impose des noms **identiques** à Story 1.2, qui utilise `@RequestParam("files") List<MultipartFile>`. On répète le part `files` pour plusieurs fichiers. `comment` devient **obligatoire** ici (≥ 10 car.), alors qu'il reste optionnel pour le dépôt simple — la contrainte est portée par `openDispute`, pas par `deposit`.

**Réutilisation sans duplication** : `openDispute` ne revalide aucun fichier ; il délègue à `deposit`, qui re-verrouille la même ligne (réentrant, même transaction), re-résout le rôle (inoffensif) et applique la fenêtre d'upload (`DISPUTED` ∈ fenêtre). L'ordre transition-puis-dépôt est donc requis.

**Nuance d'autorisation** : la matrice restreint `SHIPPED + OPEN_DISPUTE` au seul `BUYER` → un vendeur qui tente d'ouvrir après expédition reçoit `403` (`UNAUTHORIZED`), comportement hérité de la state machine (l'AC « déjà DISPUTED… » vise le `409` `ILLEGAL_TRANSITION`). C'est cohérent avec `EscrowStateMachineTest` existant, aucun changement de la matrice.

**Choix `200 OK`** : l'action porte sur une ressource escrow existante (transition) ; la réponse renvoie l'état à jour + les pièces créées. (Le dépôt simple renvoie `201` car il ne fait que créer des pièces ; ici l'acte primaire est la transition.)

**Pas de dépendance circulaire** : `EvidenceService` n'injecte pas `EscrowService`, donc `EscrowService → EvidenceService` est sûr.

## Verification

**Commands:** (le module utilise `mvn` système ; pas de wrapper `./mvnw`)
- `cd backend && mvn test -Dtest=EscrowDisputeServiceTest` -- expected: tous les cas de la matrice verts (nominal, SHIPPED-buyer, 400 sans fichier / commentaire court, 400 fichier invalide, 409 état non ouvrable / terminal, 403 non-partie / vendeur-après-SHIPPED, 404 inconnu, rollback storage, garde `/event` OPEN_DISPUTE). ✓ 12/12 (après patchs de revue).
- `cd backend && mvn test` -- expected: suite complète verte (aucune régression Epic 1). ✓ 67/67.
- `cd backend && mvn -DskipTests compile` -- expected: compilation OK. ✓

## Auto Run Result

Status: done

**Résumé** — Endpoint composite atomique `POST /api/v1/escrow/{id}/dispute` (multipart) qui, dans une seule frontière `@Transactional`, fait passer la transaction à `DISPUTED` puis attache la/les preuve(s) obligatoire(s) en réutilisant intégralement `EvidenceService.deposit` (validation Tika, bornes de taille, clé opaque, audit `EVIDENCE_ADDED`, nettoyage stockage au rollback). Commentaire ≥ 10 caractères et ≥ 1 fichier exigés (400) avant toute transition ; rollback total garanti si le dépôt échoue (test AD-1).

**Fichiers modifiés / créés**
- `backend/.../web/dto/EscrowDtos.java` — nouveau `record DisputeOpenedDto(TransactionDto, List<EvidenceDto>)`.
- `backend/.../service/EscrowService.java` — `openDispute(...)` composite atomique ; injection d'`EvidenceService` ; **patch revue** : `applyEvent` rejette `OPEN_DISPUTE` (garantie preuve-obligatoire non contournable) ; `getDetail` utilise le trail ordonné par (timestamp, id).
- `backend/.../web/EscrowController.java` — `POST /{id}/dispute` (multipart, `200`).
- `backend/.../repository/AuditLogRepository.java` — **patch revue** : `findByTransactionIdOrderByTimestampAscIdAsc` (ordre causal du trail).
- `backend/.../service/EscrowDisputeServiceTest.java` — nouveau, 12 tests (matrice complète + garde `/event` + assertions renforcées).

**Revue (2 relecteurs adverses, mêmes findings dédupliqués)** — 3 patchs appliqués (1 high : backdoor `/event` OPEN_DISPUTE fermée ; 2 medium : assertion rollback stockage durcie, ordre causal du trail d'audit), 2 items reportés (oracle d'existence 403-vs-404 ; absence de trace d'audit sur échec au stade dépôt), 10 items rejetés (bruit / vérifiés faux / tradeoffs assumés). Détail dans `## Review Triage Log`.

**Vérification** — `mvn test` : **67/67 verts** (dont `EscrowDisputeServiceTest` 12/12), 0 régression Epic 1. Diff de production relu manuellement (conforme à la spec, style cohérent).

**Recommandation de relecture de suivi** : `true` — le patch high-sévérité modifie `applyEvent` (méthode partagée par toutes les transitions d'état) et le changement d'ordre du trail d'audit touche le contrat de consultation (`getDetail`) ; impact comportemental/compliance transverse justifiant une relecture indépendante.

**Risques résiduels** — (1) échec au stade dépôt sans trace d'audit durable (reporté) ; (2) 500 hors-enveloppe si stockage indisponible (déjà au ledger, transverse Epic 1) ; (3) oracle d'existence 403-vs-404 (reporté, posture plateforme). Aucun ne bloque la story.

---

### Relecture de suivi — 2026-07-16

Déclenchée par `followup_review_recommended: true` du run initial (le patch high modifiait `applyEvent`, méthode partagée, et l'ordre du trail d'audit touchait `getDetail`). Deux relecteurs adverses indépendants (Blind Hunter + Edge Case Hunter), sans contexte de conversation préalable, sur le diff complet depuis `8d34f93`.

**Résultat : invariant confirmé sain, aucune modification de code.** Les deux relectures concluent que l'atomicité transition↔dépôt tient (une seule transaction physique, propagations `MANDATORY` pour `recordSuccess`/`EVIDENCE_ADDED` donc annulées au rollback, `recordFailure` en `REQUIRES_NEW` donc survivant, nettoyage des objets stockés au rollback), que la porte dérobée `OPEN_DISPUTE` sur `/event` est effectivement fermée, et que le tri causal de l'audit `(timestamp, id)` est nécessaire et correct.

**Triage : 0 intent_gap, 0 bad_spec, 0 patch, 1 defer (NEW), 10 reject.** Un seul report nouveau au ledger : absence de test de couche web (`@WebMvcTest`/`MockMvc`) sur `POST /{id}/dispute` — gap réel mais faible (mapping d'exceptions centralisé et déjà éprouvé ailleurs ; convention projet = tests niveau service). Rejets : trois findings déjà au ledger (DoS lot multipart non borné ; oracle 403-vs-404 ; absence d'audit sur échec au stade dépôt), deux décisions déjà assumées dans les Design Notes (double verrou réentrant inoffensif ; choix `200 OK`), et cinq spéculatifs/hors périmètre (couplage futur `DISPUTED∈UPLOAD_WINDOW`, ADMIN non ouvrant hérité de la matrice, `400` uniforme sur `/event`, commentaire stocké non-trimé cosmétique, méthode repository encore référencée par les tests).

**Vérification** — Aucun fichier `backend/**` modifié durant cette passe (uniquement spec, ledger, sprint-status) ; la vérification initiale `mvn test` 67/67 reste valide. Confirmé par `git status` (diff strictement documentaire).

**Recommandation de relecture de suivi : `false`** — cette passe n'a produit aucun changement de code ; la boucle de revue a convergé.
