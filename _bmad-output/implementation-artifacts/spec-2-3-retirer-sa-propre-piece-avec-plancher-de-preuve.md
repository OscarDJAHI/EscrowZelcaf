---
title: 'Retirer sa propre pièce avec plancher de preuve'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: '420ff07319a3a5d628f1c3a98d33022bd3f725cd'
final_revision: '71b213af72f2c80b82e92fd17d28b8ada6bea3ad'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Un déposant doit pouvoir corriger un dépôt en retirant **sa propre** pièce sans jamais la supprimer physiquement (traçabilité, AD-4), mais aucun endpoint de retrait n'existe. Un retrait naïf pourrait vider un litige de toute preuve ou, sous deux retraits concurrents, faire tomber un dossier `DISPUTED` sous le plancher d'une preuve minimum (FR-6).

**Approach:** Ajouter `EvidenceService.withdraw(actor, txId, evidenceId)` (retrait logique `ACTIVE → WITHDRAWN`) exposé par `POST /api/v1/escrow/{id}/evidence/{eid}/withdraw`, réutilisant les gardes existantes : lecture verrouillée pessimiste de la transaction (`findByIdForUpdate`, sérialise la concurrence), contrôle d'appartenance (`TransactionAccess.resolveRole`), et le prédicat de fenêtre `EscrowState.allowsEvidenceMutation()` promu par la Story 2.2. Ajouter par-dessus : une garde « propriétaire de la pièce » (403), un plancher transactionnel de comptage `ACTIVE` en `DISPUTED` (409), et une entrée d'audit `EVIDENCE_WITHDRAWN`.

## Boundaries & Constraints

**Always:**
- Retrait **logique uniquement** : `status ACTIVE → WITHDRAWN`, `withdrawn_at` (heure serveur) et `withdrawn_by_user_id` renseignés ; jamais de suppression physique ni de mutation du binaire de stockage.
- Retrait **strictement de sa propre pièce** : autorisé ssi `evidence.uploadedByUserId == actor.userId()` (et non null) ; sinon `403 ForbiddenException` (FR-12).
- Verrou terminal réutilisé : le retrait délègue au **même** prédicat `EscrowState.allowsEvidenceMutation()` que le dépôt (contrat de réutilisation Story 2.2) ; hors fenêtre (`INITIATED`/`RELEASED`/`REFUNDED`) → `409 ConflictException`, avec son propre message de retrait.
- Plancher en litige : en `DISPUTED`, un retrait qui ferait passer le nombre de pièces `ACTIVE` — **toutes parties confondues** — sous `1` est refusé `409`. Le comptage se fait dans la transaction, derrière le verrou pessimiste de la ligne transaction, pour tenir sous la course.
- Audit dans la même transaction : `AuditService.recordEvidenceWithdrawn(...)` en propagation `MANDATORY` (commit atomique avec la mutation de la ligne). Payload JSONB `action = "EVIDENCE_WITHDRAWN"`, `actorRole`, `evidenceId`.
- Contrôle d'appartenance d'abord (anti-IDOR) : charger la transaction, résoudre le rôle (`403` non-partie), puis charger la pièce par la requête scellée `findByIdAndTransactionId` (`404` si inconnue/étrangère) — jamais un `findById` non scellé.

**Block If:**
- Le plancher minimum devrait être une valeur autre que `1`, ou s'appliquer hors `DISPUTED` — non spécifié par l'epic ; ne pas inventer.

**Never:**
- Ne PAS supprimer physiquement une pièce, ni masquer une pièce `WITHDRAWN` en lecture (elle reste visible — AD-4).
- Ne PAS introduire de suppression `DELETE` REST ni de second chemin de retrait ; un seul endpoint `POST .../withdraw`.
- Ne PAS lire le binaire de stockage pendant le retrait (opération de métadonnées pure ; ne doit pas dépendre de la disponibilité du stockage objet).
- Ne PAS redéfinir l'ensemble d'états autorisés ni affaiblir/dupliquer le prédicat `allowsEvidenceMutation()`.
- Ne PAS inverser la direction de dépendance : `EscrowService → EvidenceService`, jamais l'inverse.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Retrait nominal | `POST .../evidence/{eid}/withdraw`, pièce `ACTIVE` déposée par l'acteur, tx `FUNDS_LOCKED`/`SHIPPED`/`DISPUTED` (≥2 actives si `DISPUTED`) | `200`, pièce passe `WITHDRAWN`, `withdrawn_at`/`withdrawn_by_user_id` renseignés, audit `EVIDENCE_WITHDRAWN` | Aucune erreur |
| Pièce d'un tiers | Pièce `ACTIVE` dont `uploadedByUserId != actor` (ou pièce partenaire, user null) | Refus, aucune mutation | `403 ForbiddenException` |
| Non-partie | Acteur non acheteur/vendeur/arbitre de la tx | Refus avant tout accès pièce | `403 ForbiddenException` |
| Dernière preuve en litige | tx `DISPUTED`, la pièce est la dernière `ACTIVE` (compte `ACTIVE == 1`) | Refus, aucune mutation | `409 ConflictException` (plancher FR-6) |
| Transaction terminale | tx `RELEASED`/`REFUNDED` | Refus (gel Story 2.2), aucune mutation | `409 ConflictException` |
| Déjà retirée | Pièce déjà `WITHDRAWN` | Refus, idempotence non garantie | `409 ConflictException` |
| Transaction inconnue | `txId` absent | Refus | `404 NotFoundException` |
| Pièce inconnue/étrangère | `eid` absent ou hors de `txId` | Refus (indiscernable) | `404 NotFoundException` |
| Deux retraits concurrents | tx `DISPUTED`, 2 pièces `ACTIVE`, 2 retraits parallèles | **Au plus un** réussit ; l'autre refusé (plancher tient) | `409 ConflictException` pour le perdant |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- ajouter `@Transactional withdraw(AuthPrincipal, Long txId, Long evidenceId): EvidenceDto` ; réutiliser `findByIdForUpdate` (l.92 pattern), `access.resolveRole` (l.95), `findByIdAndTransactionId` (l.196 pattern) ; ajouter garde propriétaire + plancher. Ajouter `requireWithdrawWindow(state)` privé délégant à `allowsEvidenceMutation()` (miroir de `requireUploadWindow` l.213-218, message propre au retrait).
- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` -- ajouter `long countByTransactionIdAndStatus(Long transactionId, EvidenceStatus status)` pour le plancher.
- `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` -- ajouter `@Transactional(propagation = MANDATORY) recordEvidenceWithdrawn(Long txId, Long actorId, ParticipantRole actorRole, EscrowState currentState, Long evidenceId)` (miroir de `recordEvidenceAdded` l.65-78 ; payload `action=EVIDENCE_WITHDRAWN`, `actorRole`, `evidenceId` ; `previous == next == currentState`).
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` -- ajouter `POST /{id}/evidence/{evidenceId}/withdraw` → `evidenceService.withdraw(actor, id, evidenceId)`, `200 OK` + `EvidenceDto` (miroir des mappings existants, `@AuthenticationPrincipal AuthPrincipal actor`).
- `backend/src/main/java/com/zlecaf/escrow/domain/EscrowState.java` -- **inchangé** : source du prédicat `allowsEvidenceMutation()` réutilisé.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- couverture service : retrait nominal + audit, tiers `403`, plancher `DISPUTED` `409`, terminal `409`, déjà-retiré `409` (réutiliser `persistActiveEvidence`/`persistEvidence`, l.373-395).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceWithdrawConcurrencyTest.java` -- **nouveau** : test de concurrence sur le harnais `NOT_SUPPORTED` (miroir `EscrowDisputeServiceTest`) — 2 retraits parallèles, exactement un réussit.
- `backend/src/test/java/com/zlecaf/escrow/web/EvidenceControllerWithdrawTest.java` -- **nouveau** : MockMvc (miroir `EscrowControllerDisputeTest`) — `200`/`403`/`404`/`409` mappés depuis les exceptions service.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` -- ajouter `long countByTransactionIdAndStatus(Long transactionId, EvidenceStatus status)`.
- [x] `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` -- ajouter `recordEvidenceWithdrawn(...)` `MANDATORY` (payload `EVIDENCE_WITHDRAWN`, `actorRole`, `evidenceId`).
- [x] `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- ajouter `withdraw(...)` : `findByIdForUpdate` (404) → `resolveRole` (403) → `findByIdAndTransactionId` (404) → garde propriétaire (403) → `requireWithdrawWindow` (409) → refus si déjà `WITHDRAWN` (409) → plancher en `DISPUTED` via `countByTransactionIdAndStatus(txId, ACTIVE) <= 1` (409) → set `WITHDRAWN`/`withdrawnAt=Instant.now()`/`withdrawnByUserId`, save, `recordEvidenceWithdrawn`, retourner `EvidenceDto.from`. Ajouter `requireWithdrawWindow(state)` privé.
- [x] `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` -- ajouter `POST /{id}/evidence/{evidenceId}/withdraw` → `200` + `EvidenceDto`.
- [x] `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- tester chaque ligne de l'I/O Matrix côté service : nominal+audit, tiers 403, plancher DISPUTED 409, terminal 409, déjà-retiré 409, pièce étrangère 404 ; asserter l'absence de mutation/audit sur les refus.
- [x] `backend/src/test/java/com/zlecaf/escrow/service/EvidenceWithdrawConcurrencyTest.java` -- harnais `NOT_SUPPORTED` : tx `DISPUTED` avec 2 pièces `ACTIVE` d'un même déposant, `ExecutorService` + `CountDownLatch` lancent 2 retraits ; asserter exactement 1 succès, 1 `ConflictException` (ou `OptimisticLockException`→409), compte `ACTIVE` final = 1.
- [x] `backend/src/test/java/com/zlecaf/escrow/web/EvidenceControllerWithdrawTest.java` -- MockMvc : `200` succès, `403`/`404`/`409` depuis `thenThrow`.

**Acceptance Criteria:**
- Given une pièce `ACTIVE` que j'ai déposée en `DISPUTED` (avec d'autres actives), when je la retire, then `status=WITHDRAWN`, `withdrawn_at`/`withdrawn_by_user_id` renseignés, binaire intact, et une entrée `audit_logs` `EVIDENCE_WITHDRAWN` est écrite atomiquement.
- Given une pièce déposée par un tiers, when je tente de la retirer, then `403`, aucune mutation.
- Given une transaction `DISPUTED` où ma pièce est la dernière `ACTIVE` toutes parties confondues, when je tente de la retirer, then `409`, le plancher tient.
- Given deux retraits concurrents des deux dernières pièces `ACTIVE` d'un litige, when ils s'exécutent en parallèle, then au plus un réussit, l'autre reçoit `409`, et il reste ≥ 1 pièce `ACTIVE`.
- Given une pièce `WITHDRAWN`, when je la consulte via la liste (Story 1.3/2.x), then elle reste visible (jamais masquée).

## Spec Change Log

_(Aucune modification de spec : 0 bad_spec, 0 intent_gap.)_

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 0, medium 0, low 4)
- defer: 1
- reject: 5
- addressed_findings:
  - `[low]` `[patch]` Test de concurrence : matcher du perdant resserré à `ConflictException` seul (les branches `OptimisticLock*` étaient inatteignables — `withdraw()` n'écrit jamais la ligne transaction, aucun bump `@Version`) ; commentaire « garde-fou `@Version` » corrigé ; imports inutilisés retirés.
  - `[low]` `[patch]` Ajout du cas FR-6 inter-parties (EvidenceServiceTest) : retrait de ma dernière pièce en `DISPUTED` réussit tant qu'une pièce du vendeur maintient le plancher — verrouille contre une régression qui scoperait le compte aux seules pièces de l'acteur.
  - `[low]` `[patch]` Ajout d'un test service-layer « non-partie » (tiers ni acheteur ni vendeur) → `403` via `resolveRole`, distinct de la garde propriétaire.
  - `[low]` `[patch]` Commentaire ajouté sur la garde plancher : la relecture-après-verrou suppose l'isolation READ COMMITTED (défaut Postgres).
- deferred (résumé) : `EvidenceDto` n'expose pas `withdrawn_at`/`withdrawn_by_user_id` (forme de DTO héritée de la Story 1.3 ; les AC de 2.3 exigent les champs DB renseignés + audit `EVIDENCE_WITHDRAWN`, non leur exposition en réponse ; pertinent pour la Story 2.4 PWA).
- rejected (résumé) : branche `actorRole == null` morte (convention identique à `recordEvidenceAdded`, inoffensive) ; absence d'index dédié pour le comptage (`transaction_id` en tête d'`idx_evidence_transaction` suffit, cardinalité faible) ; ordre garde propriétaire `403` avant fenêtre `409` (cohérent avec l'oracle 403/404 déjà assumé) ; état `INITIATED` non testé au retrait (inatteignable — aucun dépôt possible en `INITIATED`) ; duplication des fixtures du test de concurrence (le harnais `NOT_SUPPORTED` en a légitimement besoin, comme `EscrowDisputeServiceTest`).

## Design Notes

**Concurrence — pourquoi le plancher tient.** Le retrait ouvre par `transactions.findByIdForUpdate(txId)` (verrou pessimiste `PESSIMISTIC_WRITE`, déjà utilisé par `deposit`). Deux retraits sur la même transaction contendent donc sur la **même ligne** : le second bloque jusqu'au commit du premier, puis relit `countByTransactionIdAndStatus(txId, ACTIVE)` sur l'état **committé** et voit le compte décrémenté → `409`. Aucune fenêtre TOCTOU. Le `@Version` de `EscrowTransaction` reste un garde-fou secondaire. Le test de concurrence doit s'exécuter sur le harnais `@Transactional(propagation = NOT_SUPPORTED)` (chaque appel commite pour de vrai), comme `EscrowDisputeServiceTest` — un `@DataJpaTest` classique (transaction partagée annulée) ne prouverait rien.

**Audit sans `sha256`.** `EvidenceFile` ne stocke pas le `sha256` (calculé au dépôt, présent dans l'entrée `EVIDENCE_ADDED`). Le retrait est une opération de métadonnées et **ne doit pas** lire le binaire de stockage ; le payload `EVIDENCE_WITHDRAWN` porte donc `evidenceId` (corrèle avec l'entrée `EVIDENCE_ADDED` qui détient le `sha256`) sans recalculer le hash. Déviation assumée de la ligne « payload porte sha256 » de l'epic-context, justifiée par l'indépendance vis-à-vis du stockage.

**Plancher borné à `DISPUTED`.** Hors litige (`FUNDS_LOCKED`/`SHIPPED`) il n'y a pas de dossier de preuve à protéger : le retrait est libre, y compris jusqu'à zéro. Le plancher FR-6 (`>= 1`) ne s'applique qu'en `DISPUTED`, conformément à l'epic-context (« en DISPUTED, un retrait est refusé… »).

**Pas de migration.** Les colonnes `withdrawn_at`/`withdrawn_by_user_id`, la contrainte `ck_evidence_withdrawal` (`ACTIVE ⟺ null` / `WITHDRAWN ⟺ non-null`, V3) et `EvidenceStatus.WITHDRAWN` existent déjà ; aucun changement de schéma. La contrainte DB est le dernier rempart de l'invariant d'attribution du retrait.

## Verification

**Commands:**
- `cd backend && mvn -q -Dtest=EvidenceServiceTest test` -- attendu : vert, incluant les nouveaux cas de retrait.
- `cd backend && mvn -q -Dtest=EvidenceWithdrawConcurrencyTest test` -- attendu : vert, exactement un succès sous course.
- `cd backend && mvn -q -Dtest=EvidenceControllerWithdrawTest test` -- attendu : vert, mappings HTTP `200`/`403`/`404`/`409`.
- `cd backend && mvn -q compile` -- attendu : compilation sans erreur.

## Auto Run Result

Status: done

**Changement implémenté :** ajout du retrait logique de sa propre pièce de preuve avec plancher de litige. Nouvel endpoint `POST /api/v1/escrow/{id}/evidence/{eid}/withdraw` → `EvidenceService.withdraw(actor, txId, evidenceId)` : lecture verrouillée pessimiste de la transaction (`findByIdForUpdate`) → appartenance (`resolveRole`, `403`) → requête scellée anti-IDOR (`findByIdAndTransactionId`, `404`) → garde propriétaire (`403`) → fenêtre d'état réutilisant `EscrowState.allowsEvidenceMutation()` de la Story 2.2 (`409` en terminal) → refus si déjà `WITHDRAWN` (`409`) → plancher FR-6 en `DISPUTED` (`countByTransactionIdAndStatus(txId, ACTIVE) <= 1` → `409`) → bascule `ACTIVE → WITHDRAWN` (`withdrawn_at`/`withdrawn_by_user_id`, heure serveur) + audit `EVIDENCE_WITHDRAWN` atomique (`MANDATORY`). Le plancher tient sous course : deux retraits concurrents contendent sur la même ligne transaction verrouillée, le perdant recompte l'état committé et est refusé. Aucune migration (colonnes/contrainte/enum préexistants).

**Fichiers modifiés :**
- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` — `countByTransactionIdAndStatus(...)` pour le plancher.
- `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` — `recordEvidenceWithdrawn(...)` (`MANDATORY`, payload `EVIDENCE_WITHDRAWN`/`actorRole`/`evidenceId`).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` — `withdraw(...)`, `requireWithdrawWindow(...)`, constante `MIN_ACTIVE_EVIDENCE_IN_DISPUTE`.
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` — endpoint `POST .../withdraw` → `200` + `EvidenceDto`.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` — 9 cas de retrait (I/O Matrix + FR-6 inter-parties + non-partie).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceWithdrawConcurrencyTest.java` — **nouveau** : preuve de concurrence (harnais `NOT_SUPPORTED`).
- `backend/src/test/java/com/zlecaf/escrow/web/EvidenceControllerWithdrawTest.java` — **nouveau** : MockMvc `200`/`403`/`404`/`409`.

**Revue :** 0 intent_gap, 0 bad_spec, 4 patches appliqués (tous low : resserrage du matcher de concurrence + correction du commentaire `@Version`, test FR-6 inter-parties, test non-partie service-layer, commentaire READ COMMITTED), 1 report différé (`EvidenceDto` sans métadonnées de retrait), 5 findings rejetés (voir Review Triage Log). Aucune boucle de réparation.

**Vérification :** `mvn -Dtest=EvidenceServiceTest,EvidenceWithdrawConcurrencyTest,EvidenceControllerWithdrawTest test` → `Tests run: 40, Failures: 0, Errors: 0` — BUILD SUCCESS (EvidenceServiceTest 35, EvidenceWithdrawConcurrencyTest 1, EvidenceControllerWithdrawTest 4 ; Testcontainers Postgres actif, verrou `for no key update` + recomptage confirmés dans les logs). `mvn compile` vert.

**Risques résiduels :** la garantie du plancher sous course repose sur l'isolation READ COMMITTED (défaut Postgres) — documentée en commentaire ; sous REPEATABLE READ/SERIALIZABLE le recomptage devrait être revu. L'attribution du retrait (qui/quand) n'est pas exposée en réponse API (différé, voir deferred-work) — capturée côté serveur (audit + colonnes DB).
