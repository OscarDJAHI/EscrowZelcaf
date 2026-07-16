---
title: 'Corriger les reports remontés par le durcissement 5.1'
type: 'bugfix'
created: '2026-07-16'
baseline_revision: '933117df79286bc6871a1b444391d62ce24f93ba'
final_revision: '980bd19cb0ea56c4af0ad93e6432ca87a1e82052'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** La revue de la Story 5.1 a elle-même remonté trois défauts encore ouverts au ledger : (A) les deux listes désormais bornées (`GET /escrow/{id}/evidence` et le trail d'audit de `EscrowService.getDetail`) tronquent le **bout le plus récent** — `ORDER BY ... ASC LIMIT 500` garde les 500 plus **anciennes**, et chaque audit `EVIDENCE_DOWNLOADED` rapproche le plafond ; (B) `EvidenceService.download` déréférence `long sizeBytes = evidence.getSizeBytes()` sur une colonne `size_bytes` **nullable** → NPE d'unboxing (500 whitelabel) si la taille est absente ; (C) `download` ouvre un flux S3 vivant puis, la tx étant devenue inscriptible (audit), un échec du **COMMIT** JPA après le retour de la méthode ne ferme jamais ce flux — fuite de connexion/bail de pool.

**Approach:** (A) Interroger les **N plus récentes** via une requête dérivée `DESC` bornée par `Pageable`, puis ré-inverser en mémoire pour restituer l'ordre chronologique ascendant du contrat (invariant : « le bout récent n'est jamais perdu »), de façon cohérente sur les deux listes. (B) Rendre la taille nullable de bout en bout (`EvidenceDownload.sizeBytes : Long`), sans unboxing, et omettre l'en-tête `Content-Length` côté contrôleur quand elle est nulle. (C) Garantir la fermeture du flux via une `TransactionSynchronization` `afterCompletion` qui ferme `content` dès que la transaction **ne committe pas** (best-effort), en conservant l'ordre existant « audit après `storage.load` réussi » (donc aucun audit de download fantôme sur panne stockage).

## Boundaries & Constraints

**Always:**
- Le contrat de réponse des listes reste un **tableau JSON** en ordre chronologique **ascendant** (`created_at asc, id asc` pour les preuves ; `timestamp asc, id asc` pour l'audit) — la ré-inversion restitue exactement cet ordre.
- Le plafond reste le constant **partagé** `PlatformLimits.MAX_LIST_RESULTS`, appliqué **de la même façon** sur les deux listes (une seule politique).
- Conserver l'invariant 5.1 : l'audit `EVIDENCE_DOWNLOADED` est écrit **après** un `storage.load` réussi (aucun audit fantôme sur panne stockage 502) et atomiquement avec l'autorisation.
- Respecter la règle de dépendance `web → service → {repository, AuditService, port}` ; aucun type SDK S3 ne franchit l'adaptateur.
- Toute la suite backend reste verte ; le build front reste vert (aucune touche front dans cette story).

**Block If:**
- Corriger la troncature exigerait de changer la forme de réponse (tableau JSON → objet `Page`/curseur navigable) : NE PAS le faire → HALT `blocked` / `pagination shape decision`.
- Rendre `size_bytes` `NOT NULL` exigerait une migration de données rétroactive risquée : préférer l'unboxing défensif ; si une migration s'impose malgré tout → HALT `blocked` / `size_bytes migration decision`.

**Never:**
- Ne pas réordonner l'audit **avant** l'ouverture du flux (réintroduirait l'audit de download fantôme sur panne stockage que la 5.1 a délibérément écarté).
- Ne pas changer les codes de statut existants (200/400/403/404/409/502) ni renommer les endpoints.
- Ne pas introduire un runner de test front ; aucune modification front n'est requise.
- Ne pas ajouter d'en-tête/flag de troncature (hors périmètre ; la sélection du bout récent est le correctif retenu).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Liste preuves > plafond | transaction à `MAX_LIST_RESULTS + k` pièces | Renvoie les `MAX_LIST_RESULTS` pièces les **plus récentes**, en ordre `created_at asc, id asc`, corps = tableau JSON | Pas d'erreur |
| Trail audit > plafond | `getDetail` sur transaction à `MAX_LIST_RESULTS + k` lignes d'audit | Renvoie les `MAX_LIST_RESULTS` lignes les **plus récentes**, en ordre `timestamp asc, id asc` | Pas d'erreur |
| Liste sous le plafond | transaction à quelques pièces/lignes | Toutes renvoyées, ordre ascendant inchangé (non-régression) | Pas d'erreur |
| Download `size_bytes` NULL | pièce dont `size_bytes` est `NULL` | Binaire streamé, `EvidenceDownload.sizeBytes()` = `null`, réponse **sans** en-tête `Content-Length` | Aucune NPE |
| Download `size_bytes` présent | pièce à taille connue | Binaire streamé + `Content-Length` = taille (non-régression) | Pas d'erreur |
| Download, COMMIT audit échoue | `storage.load` a réussi, flux ouvert, puis le commit de la tx échoue après retour | Le flux `content` est fermé par la `TransactionSynchronization` (statut ≠ committed) ; aucune connexion/bail fuité ; le client reçoit une erreur | fermeture best-effort |
| Download, commit OK | flux ouvert, tx committée | Le flux n'est **pas** fermé par la synchronisation ; la couche web le consomme puis le ferme | Pas d'erreur |

</intent-contract>

## Code Map

- `backend/.../repository/EvidenceFileRepository.java` -- ajouter la surcharge `findByTransactionIdOrderByCreatedAtDescIdDesc(Long, Pageable)` (N plus récentes) ; retirer la surcharge `...AscIdAsc(Long, Pageable)` de la 5.1 devenue morte.
- `backend/.../repository/AuditLogRepository.java` -- ajouter `findByTransactionIdOrderByTimestampDescIdDesc(Long, Pageable)` ; retirer la surcharge `...AscIdAsc(Long, Pageable)` de la 5.1 devenue morte.
- `backend/.../service/EvidenceService.java` -- `list` : appeler la requête `Desc` bornée puis ré-inverser (`new ArrayList<>(...)` + `Collections.reverse`) avant le mapping DTO ; `download` : `Long sizeBytes = evidence.getSizeBytes()` (plus d'unboxing), et enregistrer une `TransactionSynchronization` qui ferme `content` sur complétion ≠ committed.
- `backend/.../service/EscrowService.java` -- `getDetail` : idem `list` pour le trail (requête `Desc` bornée + ré-inversion).
- `backend/.../service/EvidenceDownload.java` -- champ `sizeBytes` : `long` → `Long` (nullable).
- `backend/.../web/EvidenceController.java` -- `download` : n'ajouter `.contentLength(...)` que si `d.sizeBytes() != null`.
- Tests : `EvidenceServiceTest` (recent-tail preuves, `size_bytes` NULL), `EscrowDisputeServiceTest`/service détail (recent-tail audit), `EvidenceControllerDownloadTest` (omission `Content-Length`), nouveau `EvidenceDownloadStreamLifecycleTest` (Mockito pur : fermeture du flux sur abort, non-fermeture sur commit).

## Tasks & Acceptance

**Execution:**
- [x] `backend/.../repository/EvidenceFileRepository.java` -- ajouter `findByTransactionIdOrderByCreatedAtDescIdDesc(Long, Pageable)` et supprimer l'overload `...AscIdAsc(Long, Pageable)` inutilisé -- source des N plus récentes.
- [x] `backend/.../repository/AuditLogRepository.java` -- ajouter `findByTransactionIdOrderByTimestampDescIdDesc(Long, Pageable)` et supprimer l'overload `...AscIdAsc(Long, Pageable)` inutilisé -- idem pour l'audit.
- [x] `backend/.../service/EvidenceService.java` -- `list` : `Desc` borné + `Collections.reverse` avant map DTO ; `download` : `Long sizeBytes` sans unboxing + `registerStreamCloseOnAbort(content)` (helper `TransactionSynchronization` calqué sur `registerRollbackCleanup`, ferme si `status != STATUS_COMMITTED`) -- reports A, B, C.
- [x] `backend/.../service/EscrowService.java` -- `getDetail` : `Desc` borné + ré-inversion du trail avant map `AuditLogDto` -- report A (audit).
- [x] `backend/.../service/EvidenceDownload.java` -- `sizeBytes` en `Long` nullable -- report B.
- [x] `backend/.../web/EvidenceController.java` -- `download` : garder `.contentLength(d.sizeBytes())` derrière un `if (d.sizeBytes() != null)` -- report B (en-tête omis proprement).
- [x] Tests backend -- ajouter/étendre : recent-tail des deux listes (via requête `Desc` bornée avec un `Pageable` réduit prouvant que les plus récentes sont gardées, puis ordre ascendant restitué au niveau service) ; `download` sur `size_bytes NULL` (pas de NPE, taille nulle) ; omission `Content-Length` au contrôleur ; fermeture du flux sur abort et non-fermeture sur commit (Mockito) -- couverture A/B/C.

**Acceptance Criteria:**
- Given une transaction dépassant le plafond sur les preuves **ou** sur l'audit, when on liste/consulte le détail, then ce sont les `MAX_LIST_RESULTS` entrées **les plus récentes** qui sont renvoyées, jamais les plus anciennes, restituées en ordre chronologique ascendant, corps = tableau JSON ; un test le prouve pour **chacune** des deux listes.
- Given une pièce dont `size_bytes` est `NULL`, when un ayant droit la télécharge, then le binaire est streamé sans NPE et la réponse omet l'en-tête `Content-Length` ; un test couvre le cas.
- Given un `download` dont le flux est ouvert puis dont la transaction ne committe pas, when la complétion survient, then `content` est fermé (aucune fuite) ; when la transaction committe, then le flux n'est pas fermé par la synchronisation et reste consommé par la couche web ; l'ordre « audit après `storage.load` réussi » est préservé.
- Given la suite de tests, when on exécute `cd backend && mvn test` et `cd frontend && npm run build`, then tout passe, y compris les nouveaux tests A/B/C.

## Spec Change Log

_(Aucune boucle `bad_spec` : la passe de revue n'a produit qu'un patch et un report différé.)_

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 1: (high 0, medium 0, low 1)
- defer: 1: (high 0, medium 0, low 1)
- reject: 9: (high 0, medium 0, low 9)
- addressed_findings:
  - `[low]` `[patch]` Double-fermeture du flux de stockage sur le chemin d'échec d'audit dans `EvidenceService.download` : le hook `registerStreamCloseOnAbort(content)` était enregistré **avant** le `try/catch` de l'audit ; si `recordEvidenceDownloaded` levait en méthode, le `catch` fermait `content` puis, le rollback survenant, `afterCompletion(STATUS_ROLLED_BACK)` du hook déjà enregistré le refermait — double `close()` (double-release possible d'une connexion sur un flux S3 non idempotent). Corrigé en déplaçant l'enregistrement du hook **après** le `try/catch` (donc uniquement une fois l'audit réussi) : le `catch` synchrone et le hook de commit deviennent mutuellement exclusifs, le flux n'est jamais fermé deux fois. Invariant « audit après `storage.load` réussi » et protection contre la fuite au commit préservés ; 117 tests backend verts.
- deferred (voir `deferred-work.md`) : `MediaType.parseMediaType(d.contentType())` au contrôleur sur un `mime_type` nullable — symétrique pré-existant de la NPE `size_bytes` (report B), hors périmètre 5.2, non déclenchable sur données applicatives.
- rejected (bruit) : early-return silencieux de `registerStreamCloseOnAbort` quand aucune synchronisation n'est active (identique à l'idiome existant `registerRollbackCleanup`, chemin inatteignable sous `@Transactional`) ; profondeur des tests du cycle de vie du flux (le hook est prouvé par invocation directe de `afterCompletion`, un test d'intégration forçant un échec de commit réel étant impraticable/instable) ; `syncs.get(0)` et assertion faible dans les tests Mockito ; couverture via le vrai message-converter Spring (comportement confirmé sûr) ; note de changement sémantique client (le choix « bout récent, sans flag de troncature » est explicitement figé par la spec) ; duplication de l'idiome DESC+reverse entre deux services (≤ 3 lignes, entités/repos distincts) ; triple allocation négligeable (≤ 500 éléments).

## Design Notes

**A — garder le bout récent, restituer en ascendant.** L'idiome projet impose un `Pageable` **non trié** (l'ordre vient du nom de méthode) ; on ajoute donc une requête dérivée `Desc` bornée et on ré-inverse en mémoire (≤ 500 éléments) :
```java
// service : List<EvidenceDto> list(...)
List<EvidenceFile> recent = new ArrayList<>(evidenceFiles
        .findByTransactionIdOrderByCreatedAtDescIdDesc(txId, PageRequest.of(0, PlatformLimits.MAX_LIST_RESULTS)));
Collections.reverse(recent);                 // DESC (récentes gardées) → ASC (contrat)
return recent.stream().map(EvidenceDto::from).toList();
```
Même schéma dans `EscrowService.getDetail` pour `AuditLog`. `new ArrayList<>(...)` protège d'une liste éventuellement immuable.

**C — fermeture garantie sans réordonner l'audit.** Le commit survient dans le proxy **après** le retour de `download()`, hors de portée d'un `try/catch` interne ; on enregistre une `TransactionSynchronization` (même idiome que `registerRollbackCleanup`) qui ferme `content` sur toute complétion `!= STATUS_COMMITTED`. Sur commit, la couche web possède et ferme le flux ; on ne le ferme donc pas. L'audit reste écrit **après** `storage.load` (invariant 5.1 : pas d'audit fantôme sur 502). Le `catch` synchrone existant (fermeture immédiate si l'INSERT d'audit lève dans la méthode) est conservé — il couvre une fenêtre distincte et est testable unitairement.

## Verification

**Commands:**
- `cd /Users/Oscard/Projects/Escrow_claude/backend && mvn test` -- expected: BUILD SUCCESS, tous tests verts (existants + A/B/C).
- `cd /Users/Oscard/Projects/Escrow_claude/frontend && npm run build` -- expected: build Vite réussi.

**Manual checks (if no CLI):**
- `grep -rl "software.amazon.awssdk" backend/src/main/java` : inchangé (seuls l'adaptateur et la composition root du bean).

## Auto Run Result

**Statut : done** — les 3 défauts A/B/C remontés par la revue de la Story 5.1 sont corrigés, revus (1 passe : Blind Hunter + Edge Case Hunter), patchés et vérifiés (117 tests backend verts + build front).

**Changement implémenté.**
- **A — troncature du bout récent (deux listes).** Les listes bornées gardaient les N **plus anciennes** (`ORDER BY ... ASC LIMIT`). Remplacé par des requêtes dérivées `Desc` bornées (`findByTransactionIdOrderByCreatedAtDescIdDesc`, `findByTransactionIdOrderByTimestampDescIdDesc`) qui gardent les N **plus récentes**, puis ré-inversion en mémoire (`new ArrayList<>` + `Collections.reverse`) pour restituer l'ordre ascendant du contrat. Cohérent sur la liste des preuves (`EvidenceService.list`) et le trail d'audit (`EscrowService.getDetail`). Les surcharges `...AscIdAsc(…, Pageable)` de la 5.1, devenues mortes, sont supprimées.
- **B — NPE d'unboxing `size_bytes`.** `EvidenceDownload.sizeBytes` passe de `long` à `Long` (nullable) ; `download` ne déboxe plus ; `EvidenceController.download` n'émet `Content-Length` que si la taille est connue.
- **C — fuite du flux au commit.** `download` enregistre une `TransactionSynchronization` (`registerStreamCloseOnAbort`, calquée sur `registerRollbackCleanup`) qui ferme le flux sur toute complétion `!= STATUS_COMMITTED`. Invariant 5.1 préservé (audit écrit **après** un `storage.load` réussi ; pas d'audit fantôme sur 502). Patch de revue : hook enregistré **après** le `catch` d'audit → pas de double `close()`.

**Fichiers modifiés / créés.**
- `repository/EvidenceFileRepository.java`, `repository/AuditLogRepository.java` — surcharges `Desc`+`Pageable` (N plus récentes) ; retrait des surcharges `Asc`+`Pageable` mortes.
- `service/EvidenceService.java` — `list` : `Desc` borné + `reverse` ; `download` : `Long sizeBytes` sans unboxing + `registerStreamCloseOnAbort` (hook après audit).
- `service/EscrowService.java` — `getDetail` : `Desc` borné + `reverse` du trail.
- `service/EvidenceDownload.java` — `sizeBytes` en `Long` nullable.
- `web/EvidenceController.java` — `Content-Length` conditionnel.
- Tests : `EvidenceServiceTest` (+recent-tail preuves, +`size_bytes` NULL), `EscrowDisputeServiceTest` (+recent-tail audit, +getDetail sur-plafond), `EvidenceControllerDownloadTest` (+omission `Content-Length`, fix arité `Long`), `EvidenceDownloadStreamLifecycleTest` (NEW, Mockito : fermeture sur abort / non-fermeture sur commit).

**Revue (1 passe).** patch 1 (low) appliqué — double-close du flux sur échec d'audit, corrigé par réordonnancement du hook ; defer 1 (low) inscrit au ledger — `parseMediaType(null)` sur `mime_type` nullable, pré-existant hors périmètre ; reject 9. Aucun intent_gap ni bad_spec.

**Follow-up review recommandé : false** — la passe finale n'a produit qu'un unique patch localisé de faible conséquence (réordonnancement d'un enregistrement de hook, sans impact contrat/sécurité/données), déjà couvert par les tests existants ; un tour indépendant frais n'apporterait pas de valeur.

**Vérification.** `cd backend && mvn test` → `Tests run: 117, Failures: 0, Errors: 0, Skipped: 0`, BUILD SUCCESS. `cd frontend && npm run build` → build Vite réussi (aucune touche front). `grep -rl software.amazon.awssdk backend/src/main/java` → inchangé (adaptateur + composition root uniquement).

**Risques résiduels.** (a) `mime_type` nullable → `parseMediaType(null)` (500) reste ouvert au ledger (différé, non déclenchable sur données applicatives) ; (b) le hook de fermeture au commit est prouvé par test unitaire du branchement `afterCompletion`, pas par un échec de commit réel (impraticable à simuler de façon déterministe) ; (c) la sélection du bout récent change l'ensemble renvoyé au-delà du plafond (voulu par la spec, non signalé au client par choix figé).
