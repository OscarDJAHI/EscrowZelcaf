---
title: 'Story 1.3 — Consulter la liste chronologique des preuves'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: '0ffcf112a961219e6d97b3f0fd87953046617920'
final_revision: 'eee881f'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** Le chemin d'écriture des preuves existe (Story 1.2 : `POST .../evidence`), mais aucune partie prenante ne peut encore **consulter** le dossier. Sans lecture contradictoire, la preuve déposée est aveugle : ni l'acheteur, ni le vendeur, ni l'arbitre ne voit l'ensemble des pièces. Les stories 1.4 (téléchargement) et 1.5 (PWA) et l'Epic 2 (fil de litige) sont bloqués sur ce chemin de lecture.

**Approach:** Exposer `GET /api/v1/escrow/{id}/evidence` : un `EvidenceService.list(actor, txId)` en `@Transactional(readOnly = true)` charge la transaction (lecture non verrouillante), passe par le **contrôle d'appartenance unique et partagé** `TransactionAccess.resolveRole` (403 si non partie), puis retourne **toutes** les pièces triées par `created_at` croissant, mappées via le `EvidenceDto` existant. Aucune nouvelle exception, aucun nouveau DTO, aucun accès au stockage objet.

## Boundaries & Constraints

**Always:**
- **Autorité serveur, anti-IDOR.** Le service charge la transaction de l'URL, puis passe par le **même** contrôle `TransactionAccess.resolveRole(actor, tx)` que le dépôt et `EscrowService.getDetail` : non partie ⇒ `ForbiddenException` (403). Le rôle retourné est ignoré (lecture) — exactement comme `getDetail`.
- **Visibilité contradictoire.** La liste contient **toutes** les pièces de la transaction quelle qu'en soit l'origine (BUYER, SELLER, ADMIN, CARRIER_PARTNER). Aucun filtrage par déposant, aucun filtrage par statut.
- **Pièces retirées visibles.** Une pièce `WITHDRAWN` reste dans la liste avec son statut, jamais masquée (AD-4). La requête ne filtre pas `status`.
- **Tri serveur unique.** Ordre chronologique **croissant** sur `created_at` (heure serveur, seule clé de tri). Jamais `clientCapturedAt`. Le tri est porté par la requête dérivée `findByTransactionIdOrderByCreatedAtAsc`, adossée à l'index `(transaction_id, created_at)` de la migration `V2`.
- **Lecture non verrouillante.** Charger via `transactions.findById(txId)` (pas `findByIdForUpdate`) : c'est une lecture pure, aucun verrou pessimiste. Transaction inconnue ⇒ `NotFoundException` (404).
- **Contrat de réponse figé.** Réutiliser `EvidenceDto` + `EvidenceDto.from(EvidenceFile)` tels quels — ils exposent déjà les 7 champs requis (déposant, type de déposant, horodatage, MIME, taille, commentaire, statut) plus `id`/`transactionId`. Ne jamais exposer `storageKey`.
- **Conventions brownfield ratifiées** (cf. Code Map) : controller mince (`@GetMapping`, délégation directe), injection par constructeur, mapping entité→DTO côté service (idiome `EscrowService.getDetail`), exceptions applicatives existantes.

**Block If:**
- Le round-trip observable (liste triée lue depuis un vrai Postgres via Testcontainers, incluant une ligne `WITHDRAWN`) ne peut pas s'exécuter parce que Docker/Testcontainers est inopérant ⇒ HALT `blocked` : la preuve observable est non négociable (même principe qu'en 1.1/1.2).

**Never:**
- Aucun téléchargement de binaire (Story 1.4), aucune UI (Story 1.5), aucun retrait/transition de statut (Epic 2), aucun dépôt.
- Aucun accès à `EvidenceStorage` / MinIO / S3 depuis ce chemin de lecture (la liste ne sert que des métadonnées).
- Aucun nouveau DTO, aucun nouveau champ, aucun nouveau type d'exception, aucun handler d'exception supplémentaire.
- Aucune pagination, aucun tri paramétrable, aucun filtre par statut ou déposant (gold-plating hors périmètre POC).
- Ne jamais dériver l'appartenance ou le tri d'une autre source que `TransactionAccess` et `created_at`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Liste nominale | Partie prenante, transaction avec 3 pièces déposées à des instants distincts | `200`, tableau de 3 `EvidenceDto` triés par `created_at` croissant, chaque item portant déposant, type, horodatage, MIME, taille, commentaire, statut | Aucune |
| Visibilité contradictoire | Acheteur appelant ; pièces déposées par BUYER, SELLER et ADMIN | `200`, les 3 pièces présentes quel que soit le déposant | Aucune |
| Pièce retirée visible | Transaction avec 1 pièce `ACTIVE` et 1 pièce `WITHDRAWN` | `200`, les 2 pièces présentes, la retirée avec `status=WITHDRAWN` | Aucune |
| Liste vide | Partie prenante, transaction sans aucune pièce | `200`, tableau vide `[]` | Aucune |
| Non partie prenante | Utilisateur étranger à la transaction | Aucune donnée servie | `403` |
| Transaction inconnue | `{id}` inexistant | Aucune donnée servie | `404` |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` -- MODIFIER : ajouter la requête dérivée `List<EvidenceFile> findByTransactionIdOrderByCreatedAtAsc(Long transactionId)` (interface aujourd'hui vide — c'est la requête réservée à la Story 1.3 par la Story 1.2). Aucun filtre de statut.
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- MODIFIER : ajouter `@Transactional(readOnly = true) List<EvidenceDto> list(AuthPrincipal actor, Long txId)` : `findById` (404) → `access.resolveRole` (403, rôle ignoré) → `findByTransactionIdOrderByCreatedAtAsc(...).stream().map(EvidenceDto::from).toList()`. Ne pas toucher `deposit`.
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` -- MODIFIER : ajouter `@GetMapping("/{id}/evidence") List<EvidenceDto> list(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable Long id)` déléguant à `evidenceService.list(actor, id)` (retour nu 200, idiome `EscrowController.list/get`). Ne pas toucher `deposit`.
- `backend/src/main/java/com/zlecaf/escrow/web/dto/EvidenceDtos.java` -- LIRE : `EvidenceDto` + `from()` réutilisés tels quels (exposent déjà les 7 champs ; `storageKey` non exposé). Aucune modification.
- `backend/src/main/java/com/zlecaf/escrow/service/TransactionAccess.java` -- LIRE : `resolveRole(AuthPrincipal, EscrowTransaction)` (lève `ForbiddenException`), API figée en 1.2.
- `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` -- LIRE : entité et enums (`UploaderType`, `EvidenceStatus`), `created_at` clé de tri.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- MODIFIER : ajouter les tests de liste sur le harnais existant (`@DataJpaTest` + Postgres Testcontainer, `EvidenceStorage` fake). Ne pas altérer les 9 tests existants.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/.../repository/EvidenceFileRepository.java` -- Ajouter `findByTransactionIdOrderByCreatedAtAsc(Long)` -- tri chronologique adossé à l'index `V2`, sans filtre de statut (pièces retirées visibles).
- [x] `backend/src/main/java/.../service/EvidenceService.java` -- Ajouter `list(actor, txId)` `readOnly` : 404 → appartenance 403 → map DTO -- cœur de la lecture contradictoire réutilisable.
- [x] `backend/src/main/java/.../web/EvidenceController.java` -- Ajouter `@GetMapping("/{id}/evidence")` mince -- surface web de lecture.
- [x] `backend/src/test/java/.../service/EvidenceServiceTest.java` -- Couvrir chaque ligne de la matrice I/O (tri croissant, contradictoire, `WITHDRAWN` visible, liste vide, non-partie 403, tx inconnue 404) -- preuve observable sur Postgres réel.

**Acceptance Criteria:**
- Given une transaction dont je suis partie prenante contenant des pièces de plusieurs déposants (dont une `WITHDRAWN`), when j'appelle `GET .../{id}/evidence`, then `200` et **toutes** les pièces sont retournées triées par `created_at` croissant, chaque item portant déposant, type de déposant, horodatage, MIME, taille, commentaire et statut, la pièce retirée présente avec `status=WITHDRAWN`.
- Given le code livré, when on relit `EvidenceController`, then aucune règle d'appartenance n'est dupliquée dans le controller (elle vit dans `TransactionAccess`), et `EvidenceDto`/`storageKey` ne fuite pas la clé de stockage.
- Given le refactor, when on exécute `mvn clean test`, then les 37 tests préexistants restent verts (aucune régression) et les nouveaux tests de liste passent.
- Given le périmètre, when on relit le diff, then il ne contient ni téléchargement, ni retrait, ni UI, ni accès au stockage objet, ni nouveau DTO/exception.

## Spec Change Log

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 1: (high 0, medium 1, low 0)
- defer: 1: (high 0, medium 0, low 1)
- reject: 7: (high 0, medium 0, low 7)
- addressed_findings:
  - `[medium]` `[patch]` Le tri ne portait que sur `created_at`, sans clé secondaire : deux pièces au même instant (dépôt batch `files[]` en une transaction, ou tout chemin s'appuyant sur `DEFAULT now()` = heure de début de transaction) ressortaient dans un ordre non déterministe que la suite ne pouvait pas détecter (tests espacés d'≥1 s). Corrigé : requête `findByTransactionIdOrderByCreatedAtAscIdAsc` (`ORDER BY created_at ASC, id ASC`) + test `listTieBreaksByIdWhenCreatedAtEqual`. SQL confirmé : `order by ef1_0.created_at, ef1_0.id`.

<!-- Report (1, low) : lecture non bornée / pas de pagination → deferred-work.md (durcissement de niveau contrat d'API, cohérent avec le report 1.2 sur `files[]` non borné et la lecture non bornée d'audit).
     Rejets (7, tous low, bruit ou par-design) : mapping DTO côté service (choix délibéré de la spec — idiome lecture `EscrowService.getDetail`) ; oracle 404-avant-403 (convention plateforme, déjà rejetée en 1.2) ; `uploadedByUserId` exposé (contrat `EvidenceDto` figé en 1.2, identité du déposant requise par l'AC epic) ; `comment` verbatim aux parties (champ party-facing listé par l'AC epic) ; `MethodArgumentTypeMismatchException` hors enveloppe (pré-existant, tous controllers, déjà rejeté en 1.2) ; absence de `@WebMvcTest` (gold-plating POC, délégation triviale, aucun endpoint n'en a) ; absence de test « ADMIN liste » (chemin `resolveRole` ADMIN couvert ailleurs). -->

## Design Notes

**Mapping côté service, pas côté controller.** Le dépôt (1.2) mappe entité→DTO dans le controller ; en lecture, l'idiome de la plateforme est le mapping côté service (`EscrowService.getDetail` retourne un DTO). On suit l'idiome de lecture : `EvidenceService.list` retourne `List<EvidenceDto>`, le controller ne fait que déléguer. Squelette (guide, pas prescription) :

```java
@Transactional(readOnly = true)
public List<EvidenceDto> list(AuthPrincipal actor, Long txId) {
    EscrowTransaction tx = transactions.findById(txId)
            .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
    access.resolveRole(actor, tx);                       // 403 si non partie (rôle ignoré)
    return evidenceFiles.findByTransactionIdOrderByCreatedAtAscIdAsc(txId)  // id ASC = départage déterministe
            .stream().map(EvidenceDto::from).toList();
}
```

**Test de tri déterministe.** Pour asserter l'ordre croissant sans dépendre du `@PrePersist` (qui ne pose `created_at` que s'il est nul), persister directement des `EvidenceFile` via `TestEntityManager` avec des `created_at` explicites hors ordre d'insertion, puis vérifier que la liste ressort croissante. Le test `WITHDRAWN` persiste une ligne `status=WITHDRAWN` (avec `withdrawn_at`/`withdrawn_by_user_id` non nuls pour respecter le CHECK de retrait de la migration `V3`) et vérifie sa présence. Non-partie ⇒ `assertThatThrownBy(...).isInstanceOf(ForbiddenException.class)`, calqué sur le test existant.

## Verification

**Commands:**
- `cd backend && mvn clean test` -- expected : BUILD SUCCESS ; 37 tests préexistants verts + nouveaux tests de liste (tri, contradictoire, `WITHDRAWN`, vide, 403, 404).
- `grep -rn "storageKey\|StorageKey" backend/src/main/java/com/zlecaf/escrow/web/dto/EvidenceDtos.java` -- expected : aucun résultat (la clé de stockage ne fuite pas dans le DTO).
- `grep -rln "software.amazon.awssdk\|EvidenceStorage" backend/src/main/java/com/zlecaf/escrow/web backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- expected : `EvidenceService` peut référencer `EvidenceStorage` (dépôt), mais la **méthode `list`** n'y touche pas ; la couche web ne référence ni S3 ni le port de stockage.

**Manual checks:**
- Stack up : `GET /api/v1/escrow/{id}/evidence` (JWT partie prenante) → `200` tableau trié `created_at` croissant ; même appel par un JWT non partie → `403` ; `{id}` inexistant → `404`.

## Auto Run Result

Status: done
Blocking condition: aucune

### Changement implémenté

Le chemin de **lecture** contradictoire des preuves est câblé : `GET /api/v1/escrow/{id}/evidence` sert **toutes** les pièces d'une transaction, triées par `created_at` croissant (départage déterministe par `id`), à toute partie prenante. `EvidenceService.list` (`@Transactional(readOnly = true)`) charge la transaction en lecture non verrouillante (404 si inconnue), passe par le contrôle d'appartenance partagé `TransactionAccess.resolveRole` (403 hors partie), puis mappe via le `EvidenceDto` existant. Visibilité contradictoire (tout déposant, BUYER/SELLER/ADMIN/CARRIER_PARTNER) et pièces `WITHDRAWN` visibles, jamais masquées. Aucun accès au stockage objet, aucun nouveau DTO/exception.

### Fichiers

**Modifiés**
- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` — requête dérivée `findByTransactionIdOrderByCreatedAtAscIdAsc` (tri chronologique + départage `id`, sans filtre de statut).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` — méthode `list(actor, txId)` en lecture seule (404 → appartenance 403 → map DTO), sans accès stockage.
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` — `@GetMapping("/{id}/evidence")` mince déléguant au service (200).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` — 7 tests ajoutés (tri croissant, **départage sur `created_at` égal**, visibilité contradictoire, `WITHDRAWN` visible, liste vide, non-partie 403, tx inconnue 404) sur le harnais Postgres Testcontainer existant.

### Revue

2 revues adversariales parallèles (Blind Hunter + Edge Case Hunter). 1 correctif appliqué (medium), 1 point reporté (low), 7 rejetés (tous low). Aucun `intent_gap`, aucun `bad_spec` : zéro boucle de reprise.

- **Patché** : tri sans clé secondaire → ordre non déterministe pour des `created_at` égaux (dépôt batch). Corrigé par `ORDER BY created_at ASC, id ASC` + test de départage dédié.
- **Reporté** : lecture non bornée / pas de pagination (durcissement contrat d'API — `deferred-work.md`).
- **Rejetés** (justification en commentaire du *Review Triage Log*) : mapping DTO côté service (par-design), oracle 404/403, `uploadedByUserId`/`comment` exposés (contrat figé + AC epic), `MethodArgumentTypeMismatch` hors enveloppe (pré-existant), `@WebMvcTest`/test ADMIN (gold-plating POC).

### Vérification

- `cd backend && mvn clean test` → **44 tests, 0 échec** (37 préexistants intacts + 7 nouveaux ; `EvidenceServiceTest` 9→16). SQL Hibernate confirmé : `order by ef1_0.created_at, ef1_0.id`. Docker/Testcontainers disponible.
- `grep storageKey` sur `EvidenceDtos.java` → aucun résultat : la clé de stockage ne fuite pas.
- La méthode `list` ne référence pas `EvidenceStorage` (lecture de métadonnées seule) ; la couche web ne référence ni S3 ni le port de stockage.

### Risques résiduels

- **Lecture non bornée** : pas de pagination (reporté ; POC — peu de pièces par dossier).
- **`mvn test` exige Docker** : `EvidenceServiceTest` (Postgres) et `MinioEvidenceStorageTest` (MinIO) reposent sur Testcontainers.
