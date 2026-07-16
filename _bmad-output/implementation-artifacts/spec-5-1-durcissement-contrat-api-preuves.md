---
title: 'Durcir le contrat d''API des preuves (bundle de reports)'
type: 'chore'
created: '2026-07-16'
status: 'done'
baseline_revision: '5ab50dd12189b5be566240d187537dc1428bf584'
final_revision: '610e11db20523c95da99558ff41c223b400d0e5d'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [multiple-goals, oversized]
---

<intent-contract>

## Intent

**Problem:** Six reports différés des Epics 1-2 laissent le contrat d'API des preuves fragile avant que l'Epic 3 (partenaire) et l'Epic 4 (offline/volume) n'amplifient la charge : deux lectures de liste non bornées (preuves + trail d'audit), `files[]` sans plafond bufférisé en mémoire, le téléchargement sans entrée d'audit, les erreurs de stockage objet remontant en page whitelabel `500`, l'attribution du retrait absente du DTO, et une course PWA où un retrait peut réapparaître brièvement en `ACTIVE`.

**Approach:** Solder le bundle en un durcissement transverse : borner les deux lectures via un plafond partagé, rejeter `files[]` au-delà de 20 avant bufférisation, auditer le download (`EVIDENCE_DOWNLOADED`), mapper les échecs stockage dans l'enveloppe d'erreur JSON (`502`) en gardant le SDK S3 dans l'adaptateur, exposer `withdrawnAt`/`withdrawnByUserId` dans `EvidenceDto`, et faire participer le retrait au jeton `loadSeq` du store evidence.

## Boundaries & Constraints

**Always:**
- Respecter la règle de dépendance `web → service → {repository, AuditService, port}` ; aucun type du SDK S3 (`S3Exception`, `SdkClientException`, `NoSuchKeyException`) ne franchit l'adaptateur `MinioEvidenceStorage`.
- Toute erreur renvoyée reste dans l'enveloppe unique `{timestamp, status, error, message}` servie par `GlobalExceptionHandler`.
- Le plafond de liste est **identique** et appliqué **de la même façon** sur la liste des preuves et sur le trail d'audit (un seul constant partagé).
- Le plafond `files[]` est **identique** sur le dépôt simple et sur l'ouverture composite de litige (même contrat multipart : parts `files`, `comment`, `clientCapturedAt`).
- L'audit `EVIDENCE_DOWNLOADED` est écrit atomiquement avec l'autorisation d'accès (via le writer unique `AuditService`).
- Les nouveaux champs de retrait suivent le moule DTO existant (`record` + fabrique `static from()`), câblage uniquement.
- La suite backend existante reste verte ; le build front reste vert.

**Block If:**
- Borner une liste exigerait de changer la forme de réponse (tableau JSON → objet `Page`) : NE PAS le faire (voir Never). Si un besoin réel de pagination navigable émerge, HALT `blocked` / `pagination shape decision`.
- Rendre `download` transactionnellement inscriptible casserait un invariant `readOnly` exploité ailleurs : HALT `blocked` / `download tx writability`.

**Never:**
- Ne pas introduire de handler catch-all `@ExceptionHandler(Exception.class)` (masquerait de vrais bugs) — mapper une exception storage-neutre dédiée.
- Ne pas changer la forme JSON des listes (rester un tableau ; le plafond borne, ne pagine pas).
- Ne pas amorcer un framework de test front (Vitest/Jest) dans cette story — la vérif front reste `npm run build`.
- Ne pas renommer les parts multipart ni changer les codes de statut existants (200/400/403/404/409).
- Ne pas toucher aux items du ledger déjà `RÉSOLU`/`ACCEPTÉ` (dont l'oracle 403/404 accepté comme risque POC).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Liste bornée | `GET /escrow/{id}/evidence`, transaction à N pièces | Renvoie au plus `MAX_LIST_RESULTS` lignes, ordre `createdAt asc, id asc` inchangé, corps = tableau JSON | Pas d'erreur |
| Audit borné | `getDetail` sur transaction à M lignes d'audit | Trail borné au même `MAX_LIST_RESULTS`, ordre `timestamp asc, id asc` inchangé | Pas d'erreur |
| `files[]` sur-plafond (dépôt) | `POST /{id}/evidence` avec 21 fichiers | `400` (enveloppe JSON), rejet **avant** lecture/bufférisation des octets | `BadRequestException` |
| `files[]` sur-plafond (litige) | `POST /{id}/dispute` avec 21 fichiers | `400`, rejet **avant** la transition d'état et avant bufférisation | `BadRequestException` |
| `files[]` = 20 | dépôt de 20 fichiers valides | Succès `200` | Pas d'erreur |
| Download audité | ayant droit `GET .../evidence/{eid}/download` | Binaire streamé + 1 audit `EVIDENCE_DOWNLOADED` (acteur, rôle, id pièce) écrit atomiquement | Pas d'erreur |
| Panne stockage (download) | `storage.load` lève `S3Exception`/`SdkClientException` (≠ objet absent) | `502` dans l'enveloppe JSON, aucun détail SDK exposé, aucun audit download écrit (rollback) | `EvidenceStorageException` → 502 |
| Panne stockage (dépôt) | `storage.store` lève `S3Exception`/timeout | `502` enveloppe JSON, transaction annulée | `EvidenceStorageException` → 502 |
| Objet absent (download) | `NoSuchKeyException` de MinIO | `404` (inchangé) | `EvidenceNotFoundException` → 404 |
| DTO retrait | pièce `WITHDRAWN` renvoyée (liste/retrait/dépôt) | `EvidenceDto` porte `withdrawnAt` + `withdrawnByUserId` non nuls ; pièce `ACTIVE` → les deux nuls | Pas d'erreur |
| Course retrait PWA | `loadEvidence` en vol + `withdrawEvidence` committé avant sa résolution | Le `loadEvidence` obsolète baille (`seq !== loadSeq`) ; la pièce ne réapparaît jamais `ACTIVE` | N/A |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/service/PlatformLimits.java` -- **NOUVEAU** : constantes partagées `MAX_FILES_PER_DEPOSIT = 20`, `MAX_LIST_RESULTS = 500`.
- `backend/src/main/java/com/zlecaf/escrow/service/storage/EvidenceStorageException.java` -- **NOUVEAU** : exception storage-neutre (RuntimeException) pour toute panne infra ≠ objet absent.
- `backend/src/main/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorage.java` -- `load`/`store`/`delete` : envelopper `S3Exception`/`SdkClientException` (hors `NoSuchKeyException`) en `EvidenceStorageException`.
- `backend/src/main/java/com/zlecaf/escrow/web/GlobalExceptionHandler.java` -- ajouter `@ExceptionHandler(EvidenceStorageException.class)` → `502` (enveloppe standard).
- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` -- `findByTransactionIdOrderByCreatedAtAscIdAsc` : ajouter param `Pageable`.
- `backend/src/main/java/com/zlecaf/escrow/repository/AuditLogRepository.java` -- `findByTransactionIdOrderByTimestampAscIdAsc` : ajouter param `Pageable`.
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- `list` : passer `PageRequest.of(0, MAX_LIST_RESULTS)` ; `deposit` : garde `files.size() > MAX_FILES_PER_DEPOSIT` → 400 (avant Pass 1) ; `download` : rendre inscriptible + écrire l'audit download.
- `backend/src/main/java/com/zlecaf/escrow/service/EscrowService.java` -- `getDetail` : passer le plafond au repo audit ; `openDispute` : garde `files.size()` (même constant) avant la transition.
- `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` -- **NOUVEAU** `recordEvidenceDownloaded(...)` (`MANDATORY`, action `EVIDENCE_DOWNLOADED`).
- `backend/src/main/java/com/zlecaf/escrow/web/dto/EvidenceDtos.java` -- `EvidenceDto` : ajouter `withdrawnAt`, `withdrawnByUserId` + câblage dans `from()`.
- `frontend/src/stores/evidence.js` -- `withdrawEvidence` : incrémenter `loadSeq` avant la mutation ciblée de `items`.
- Tests : `EvidenceServiceTest`, `EscrowDisputeServiceTest`, `EscrowControllerDisputeTest`, `EvidenceControllerDownloadTest`, `MinioEvidenceStorageTest` (+ éventuel nouveau test de mapping handler via `standaloneSetup`).

## Tasks & Acceptance

**Execution:**
- [x] `backend/.../service/PlatformLimits.java` -- créer les deux constantes partagées -- source unique pour cohérence inter-services.
- [x] `backend/.../service/storage/EvidenceStorageException.java` -- créer l'exception storage-neutre -- garder le SDK S3 hors du service/web.
- [x] `backend/.../service/storage/MinioEvidenceStorage.java` -- dans `load`/`store`/`delete`, `catch (S3Exception | SdkClientException e)` (après le `catch NoSuchKeyException` existant de `load`) → `throw new EvidenceStorageException(storageKey, e)` -- mapping infra.
- [x] `backend/.../web/GlobalExceptionHandler.java` -- `@ExceptionHandler(EvidenceStorageException.class)` renvoyant `body(HttpStatus.BAD_GATEWAY, "Stockage de preuves indisponible")` -- 502 dans l'enveloppe.
- [x] `backend/.../repository/EvidenceFileRepository.java` -- ajouter surcharge `findByTransactionIdOrderByCreatedAtAscIdAsc(Long, Pageable)` -- borne au niveau requête.
- [x] `backend/.../repository/AuditLogRepository.java` -- ajouter surcharge `findByTransactionIdOrderByTimestampAscIdAsc(Long, Pageable)` -- borne au niveau requête.
- [x] `backend/.../service/EvidenceService.java` -- `list` : appeler la surcharge `Pageable` avec `PageRequest.of(0, MAX_LIST_RESULTS)` ; `deposit` : ajouter la garde de plafond `files` à côté de la garde de lot vide (l.94-96) ; `download` : passer `@Transactional` (retirer `readOnly=true`) et appeler `auditService.recordEvidenceDownloaded(...)` après autorisation + `storage.load` réussi -- 3 durcissements.
- [x] `backend/.../service/EscrowService.java` -- `getDetail` : passer `PageRequest.of(0, MAX_LIST_RESULTS)` au repo audit ; `openDispute` : garde `files.size() > PlatformLimits.MAX_FILES_PER_DEPOSIT` → `BadRequestException` dans le bloc de pré-checks (avant la transition) -- rejet précoce.
- [x] `backend/.../service/AuditService.java` -- `recordEvidenceDownloaded(Long txId, Long actorId, ParticipantRole role, EscrowState currentState, Long evidenceId)` `MANDATORY`, payload `action=EVIDENCE_DOWNLOADED` -- calqué sur `recordEvidenceWithdrawn`.
- [x] `backend/.../web/dto/EvidenceDtos.java` -- ajouter `Instant withdrawnAt, Long withdrawnByUserId` au record et au `from()` (via getters entité existants) -- exposition d'attribution.
- [x] `frontend/src/stores/evidence.js` -- `withdrawEvidence` : `this.loadSeq++` juste avant `this.items = this.items.map(...)` -- invalide tout `loadEvidence` en vol.
- [x] Tests backend -- ajouter/étendre : plafond de liste (preuves + audit), rejet `files[]` > 20 (dépôt **et** dispute, sans transition ni bufférisation), audit `EVIDENCE_DOWNLOADED` écrit, mapping `EvidenceStorageException` → 502, nouveaux champs DTO renseignés au retrait -- couverture du bundle.

**Acceptance Criteria:**
- Given une transaction très fournie, when on liste les preuves ou lit le détail (audit), then au plus `MAX_LIST_RESULTS` lignes sont renvoyées sur **chacun** des deux endpoints, avec l'ordre chronologique inchangé et le corps toujours un tableau JSON.
- Given un multipart de plus de 20 fichiers, when il est posté sur `/{id}/evidence` **ou** `/{id}/dispute`, then la requête est rejetée `400` avant toute bufférisation massive (et, pour le litige, avant la transition d'état).
- Given un ayant droit, when il télécharge une preuve, then une entrée d'audit `EVIDENCE_DOWNLOADED` (acteur, rôle, id de pièce) est écrite atomiquement et le binaire est streamé.
- Given une panne du stockage objet autre que « objet absent », when elle survient au dépôt ou au téléchargement, then le client reçoit un `502` dans l'enveloppe JSON `{timestamp,status,error,message}` sans fuite de détail SDK, et aucun type SDK ne quitte l'adaptateur.
- Given une pièce retirée, when l'API la renvoie (liste/retrait/dépôt), then `EvidenceDto` expose `withdrawnAt` et `withdrawnByUserId` (nuls pour une pièce `ACTIVE`).
- Given un `loadEvidence` en vol et un retrait committé avant sa résolution, when le `loadEvidence` obsolète se résout, then il baille sur le jeton `loadSeq` et la pièce ne réapparaît jamais brièvement `ACTIVE`.
- Given la suite de tests, when on exécute `mvn test` (backend) et `npm run build` (front), then tout passe, y compris les nouveaux tests du bundle.

## Spec Change Log

_(Aucune boucle `bad_spec` : la première passe de revue n'a produit que des patches et des reports différés.)_

## Review Triage Log

### 2026-07-16 — Follow-up review pass (fresh, review_loop_iteration 0)
- intent_gap: 0
- bad_spec: 0
- patch: 1: (high 0, medium 1, low 0)
- defer: 1: (high 0, medium 0, low 1)
- reject: 3: (high 0, medium 0, low 3)
- addressed_findings:
  - `[medium]` `[patch]` Régression PWA introduite par le durcissement `loadSeq` : `withdrawEvidence` fait `this.loadSeq++` puis n'entame aucun rechargement de remplacement ; un `loadEvidence` en vol baille (l.24) et son `finally` (l.31) ne remet jamais `loading=false` (car `seq !== loadSeq`), laissant le spinner / tout contrôle `:disabled="loading"` bloqué jusqu'à un rechargement manuel. Corrigé : `withdrawEvidence` remet explicitement `this.loading = false` juste après le bump (il détient l'état terminal de chargement puisqu'aucun load de remplacement ne le fera) — l'invariant d'intent (la pièce ne réapparaît jamais `ACTIVE`) est préservé, build front vert.
- deferred (voir `deferred-work.md`) : fuite du flux S3 de `download` si le **COMMIT** de la tx d'audit (désormais inscriptible) échoue après le retour de `download()` — ni le `catch` service ni le `catch` contrôleur ne ferme le flux ouvert (fenêtre élargie par le passage `readOnly`→inscriptible ; étroite, infra-dépendante, non corrigeable trivialement dans la méthode service).
- rejected (bruit) : audit `EVIDENCE_DOWNLOADED` « fantôme » sur échec de livraison **après** commit (parseMediaType/déconnexion client) — sémantique « accès autorisé » assumée, **déjà adjugée** à la passe précédente ; nuance « avant bufférisation » du commentaire de garde `files[]` (le resolver multipart spoole déjà, borné par `max-request-size`) — **déjà adjugée** à la passe précédente ; perte de changements concurrents même-tx (dépôt contrepartie pendant un retrait) via la mutation ciblée — **voulue par la spec 2.4** (optimiste ciblée, pas de rechargement), auto-corrigée au prochain load.

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3: (high 0, medium 2, low 1)
- defer: 2: (high 0, medium 1, low 1)
- reject: 3: (high 0, medium 0, low 3)
- addressed_findings:
  - `[medium]` `[patch]` Catch SDK trop étroit (`S3Exception | SdkClientException`) dans `MinioEvidenceStorage.store/load/delete` — un `SdkException`/`AwsServiceException` non-S3 (ex. erreur STS/credentials) ou un `SdkException` nu fuyait en 500 whitelabel avec le type SDK, défaisant AC4 (« aucun type SDK ne quitte l'adaptateur »). Élargi à la racine `SdkException` (miss `NoSuchKeyException` toujours capturé en premier → 404).
  - `[medium]` `[patch]` Fuite de connexion de stockage si l'INSERT d'audit `EVIDENCE_DOWNLOADED` échoue après `storage.load` (tx désormais inscriptible) : le stream ouvert n'était ni rendu ni fermé. `EvidenceService.download` ferme désormais le stream (best-effort) avant de rethrow — préserve la propriété « pas d'audit de download fantôme sur panne stockage » sans fuir de connexion.
  - `[low]` `[patch]` Commentaire obsolète « storage down 500 » → 502 dans `EscrowService.openDispute`.
- deferred (voir `deferred-work.md`) : troncature silencieuse du bout récent des listes bornées (aggravée par le volume d'audit `EVIDENCE_DOWNLOADED`) ; NPE d'unboxing pré-existant sur `size_bytes` nullable dans `download`.
- rejected (bruit) : nuance « avant bufférisation » (le resolver multipart spoole déjà, borné par `max-request-size` — la garde de comptage protège bien Pass-1) ; résidu de course store PWA au changement de transaction (map sur `items` vidé — extrêmement étroit, auto-corrigé, serveur cohérent) ; audit écrit à l'ouverture du stream et non à la fin (sémantique « accès autorisé » assumée).

## Design Notes

**Plafond vs pagination.** Choix : plafond dur partagé (`MAX_LIST_RESULTS = 500`), pas de `Page<>`. Motif : aucune infra `Pageable` n'existe (0 usage projet) ; passer à un objet `Page` changerait la forme de réponse (tableau → enveloppe paginée) sur toute la plateforme — hors périmètre POC. Le plafond borne la mémoire/charge sans casser le contrat. Exemple (garde l'ordre du nom de méthode car `Pageable` non trié) :
```java
// repo : surcharge, même dérivation OrderBy, borne par le Pageable non trié
List<EvidenceFile> findByTransactionIdOrderByCreatedAtAscIdAsc(Long txId, Pageable page);
// service : List<EvidenceDto> list(...) {
return repo.findByTransactionIdOrderByCreatedAtAscIdAsc(txId, PageRequest.of(0, MAX_LIST_RESULTS))
           .stream().map(EvidenceDto::from).toList();
```

**Audit download + writable tx.** `download` passe de `readOnly=true` à `@Transactional` inscriptible : un `INSERT` d'audit dans une tx `readOnly` échouerait (connexion Postgres en lecture seule). L'audit (`MANDATORY`) commit atomiquement avec l'autorisation ; il est écrit **après** `resolveRole` et **après** un `storage.load` réussi, de sorte qu'une panne stockage (502) rollback sans laisser d'audit de download fantôme.

**Isolation du SDK S3.** Le service/web ne connaît que `EvidenceNotFoundException` (404) et `EvidenceStorageException` (502). L'adaptateur `MinioEvidenceStorage` reste le seul point qui `import software.amazon.awssdk...` — respect de la règle de dépendance. Pas de catch-all générique dans le handler.

**Course PWA (loadSeq).** Précédent (dépôt Epic 1.5) : la cohérence passe par le rechargement gardé, pas par mutation optimiste. `withdrawEvidence` garde sa mutation ciblée (voulue par la spec 2.4) mais `++this.loadSeq` d'abord, invalidant tout `loadEvidence` en vol (`seq !== this.loadSeq` → bail). Un `loadEvidence` démarré après le retrait relit l'état serveur (déjà `WITHDRAWN`) — cohérent.

## Verification

**Commands:**
- `cd /Users/Oscard/Projects/Escrow_claude/backend && mvn test` -- expected: BUILD SUCCESS, tous tests verts (existants + nouveaux du bundle).
- `cd /Users/Oscard/Projects/Escrow_claude/frontend && npm run build` -- expected: build Vite réussi (pas de runner de test front dans cette story).

**Manual checks (if no CLI):**
- Inspecter `grep -rl "software.amazon.awssdk" backend/src/main/java` : ne doit lister **que** `MinioEvidenceStorage.java` (aucune fuite SDK vers service/web).

## Auto Run Result

**Passe de revue de suivi (2026-07-16).** Déclenchée par `followup_review_recommended: true` de la passe initiale. Deux relecteurs indépendants (Blind Hunter + Edge Case Hunter) relancés sur le diff complet depuis le baseline. Résultat : **1 patch** (medium) + **1 différé** (low) + **3 rejets**.
- Patch appliqué : régression PWA sur le flag `loading`. `withdrawEvidence` invalidait tout `loadEvidence` en vol via `this.loadSeq++` sans démarrer de load de remplacement ; le load invalidé bailait sans que son `finally` remette `loading=false` (garde `seq === loadSeq` désormais fausse), laissant le spinner bloqué jusqu'à un rechargement manuel. Corrigé en remettant explicitement `this.loading = false` juste après le bump. Build front re-vérifié vert.
- Différé (nouvelle entrée ledger) : fuite du flux S3 de `download` si le COMMIT de la tx d'audit inscriptible échoue après le retour de la méthode (fenêtre élargie par `readOnly`→inscriptible ; non corrigeable dans la méthode service).
- Rejets : audit « fantôme » sur échec de livraison post-commit (sémantique « accès autorisé », déjà adjugée) ; nuance « avant bufférisation » du commentaire (déjà adjugée) ; perte de changements concurrents même-tx (mutation optimiste ciblée voulue par la spec 2.4).
- `followup_review_recommended` repassé à **false** : la passe de suivi n'a produit qu'un unique correctif frontend localisé, sans impact contrat/sécurité/données — un nouveau tour indépendant n'apporterait pas de valeur.

---

**Statut : done** — bundle de durcissement livré, revu (2 passes), patché et vérifié (110 tests backend verts + build front).

**Changement implémenté.** Solde les 6 reports différés ouverts des Epics 1-2 en un durcissement transverse du contrat d'API des preuves : (1) bornage cohérent des deux lectures de liste (preuves + trail d'audit) via un plafond partagé `MAX_LIST_RESULTS = 500` ; (2) rejet `400` de `files[]` > 20 avant bufférisation Pass-1 (dépôt) et avant la transition d'état (litige) ; (3) audit `EVIDENCE_DOWNLOADED` écrit atomiquement au téléchargement (`download` rendu inscriptible) ; (4) mapping des pannes de stockage objet dans l'enveloppe d'erreur JSON (`502`) via une exception storage-neutre `EvidenceStorageException`, SDK S3 confiné à l'adaptateur ; (5) exposition de `withdrawnAt`/`withdrawnByUserId` dans `EvidenceDto` ; (6) participation du retrait au jeton `loadSeq` du store PWA (fin de la course de réapparition `ACTIVE`).

**Fichiers modifiés / créés.**
- `service/PlatformLimits.java` (NEW) — constantes partagées `MAX_FILES_PER_DEPOSIT=20`, `MAX_LIST_RESULTS=500`.
- `service/storage/EvidenceStorageException.java` (NEW) — exception infra storage-neutre.
- `service/storage/MinioEvidenceStorage.java` — mapping SDK → `EvidenceStorageException` (catch racine `SdkException`, `NoSuchKeyException`→404 en premier).
- `web/GlobalExceptionHandler.java` — `EvidenceStorageException` → 502 (enveloppe).
- `repository/EvidenceFileRepository.java`, `repository/AuditLogRepository.java` — surcharges `Pageable` (borne au niveau requête).
- `service/EvidenceService.java` — `list` bornée, garde `files[]` dans `deposit`, `download` inscriptible + audit + fermeture de stream défensive.
- `service/EscrowService.java` — `getDetail` audit borné, garde `files[]` avant transition dans `openDispute`.
- `service/AuditService.java` — `recordEvidenceDownloaded` (MANDATORY, `EVIDENCE_DOWNLOADED`).
- `web/dto/EvidenceDtos.java` — 2 champs de retrait câblés via `from()`.
- `frontend/src/stores/evidence.js` — `withdrawEvidence` bump `loadSeq`.
- Tests : `EvidenceServiceTest`, `EscrowDisputeServiceTest`, `EvidenceStorageErrorMappingTest` (NEW), `MinioEvidenceStorageTest`, + fix d'arité DTO dans `EscrowControllerDisputeTest`/`EvidenceControllerWithdrawTest`.

**Revue (1 passe).** patch 3 (2 medium, 1 low) — tous appliqués ; defer 2 (1 medium, 1 low) — inscrits au ledger ; reject 3. Aucun intent_gap ni bad_spec (pas de loopback).
- Patches : élargissement du catch SDK à `SdkException` (une fuite de type SDK non-S3 défaisait AC4) ; fermeture du stream de stockage si l'INSERT d'audit du download échoue (fuite de connexion nouvellement introduite par la tx inscriptible) ; commentaire obsolète 500→502.
- Différés : troncature silencieuse du bout récent des listes bornées (aggravée par le volume d'audit download) ; NPE d'unboxing pré-existant sur `size_bytes` nullable dans `download`.

**Follow-up review recommandé : true** — les deux patches medium touchent une frontière de sécurité/contrat (isolation du SDK) et la sûreté des ressources sur des chemins d'erreur non couverts par un test dédié ; un regard indépendant frais y ajoute de la valeur.

**Vérification.** `cd backend && mvn test` → BUILD SUCCESS, `Tests run: 110, Failures: 0, Errors: 0`. `cd frontend && npm run build` → succès Vite. `grep -rl software.amazon.awssdk backend/src/main/java` → seuls `MinioEvidenceStorage.java` (adaptateur) et `config/StorageConfig.java` (composition root du bean S3Client — aucun type d'**exception** SDK, frontière respectée).

**Risques résiduels.** (a) Plafond de liste = troncature dure du bout récent sans signal (différé ; non déclenchable au volume POC) ; (b) NPE `size_bytes` nullable pré-existante (différé ; non déclenchable sur données applicatives) ; (c) le correctif de course PWA reste non testé unitairement (aucun runner front dans le périmètre) — vérifié par lecture + build.
