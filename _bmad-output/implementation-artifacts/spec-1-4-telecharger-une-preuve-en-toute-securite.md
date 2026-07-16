---
title: 'Story 1.4 — Télécharger une preuve en toute sécurité'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: 'bb85617fc9661e5f198d4f4fb003bf51b80297a0'
final_revision: '6eaf1d4'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** Le dépôt (1.2) et la lecture des métadonnées (1.3) existent, mais aucune partie prenante ne peut encore récupérer le **binaire original** d'une pièce pour l'examiner. Sans téléchargement, la preuve reste une ligne de liste : l'arbitre ne peut pas ouvrir le PDF ou l'image qui fonde sa décision. La PWA (1.5) est bloquée sur ce chemin.

**Approach:** Exposer `GET /api/v1/escrow/{id}/evidence/{eid}/download` : un `EvidenceService.download(actor, txId, evidenceId)` charge la transaction (404), passe par le **contrôle d'appartenance partagé** `TransactionAccess.resolveRole` (403), récupère la pièce via une requête **scellée sur les deux clés** `findByIdAndTransactionId(eid, txId)` (404 anti-IDOR), puis ouvre le flux via le **port** `EvidenceStorage.load(storageKey)`. Le controller sert le flux en `Content-Disposition: attachment` (jamais inline), content-type = MIME stocké. Aucune UI, aucun accès direct S3 hors adaptateur.

## Boundaries & Constraints

**Always:**
- **Anti-IDOR par requête scellée.** La pièce est chargée par `findByIdAndTransactionId(evidenceId, txId)` : une pièce inconnue **ou** appartenant à une autre transaction ⇒ `NotFoundException` (**404**), sans révéler son existence ailleurs. La vérification d'appartenance `{eid}.transaction_id == {id}` n'est jamais dérivée d'un `findById(eid)` non scellé.
- **Autorité serveur, ordre des contrôles.** `transactions.findById(txId)` (404 si inconnue, lecture non verrouillante) → `access.resolveRole(actor, tx)` (403 si non partie, rôle ignoré) → requête scellée (404) → `storage.load`. Une transaction dont l'appelant n'est pas partie ⇒ **403** avant toute recherche de pièce.
- **Restitution en pièce jointe (anti-XSS stocké, NFR-2).** L'en-tête est **`Content-Disposition: attachment`**, construit via `ContentDisposition.attachment().filename(...)` (encodage RFC 5987, pare l'injection d'en-tête). **Jamais** `inline`. Content-type = `evidence.getMimeType()` ; `Content-Length` = `evidence.getSizeBytes()`.
- **Binaire via le port uniquement.** Le flux provient exclusivement de `EvidenceStorage.load(evidence.getStorageKey())` (retourne `InputStream`). Aucun code de la couche web ni du service ne connaît MinIO/S3 — invariant du port de stockage (AD-1). Le service ouvre le flux ; le controller ne fait que câbler les en-têtes et le corps.
- **Round-trip observable octet pour octet.** Un test automatisé dépose (ou persiste) un binaire, le télécharge et prouve qu'il ressort **identique** — la preuve par test observable, pas par « ça compile » (idiome 1.1/1.2/1.3).
- **Conventions brownfield ratifiées** : controller mince, injection par constructeur, exceptions applicatives existantes (`ApiExceptions.*`), aucun handler d'exception superflu (traduire les échecs du port en exceptions déjà mappées).

**Block If:**
- Le round-trip observable (dépôt puis téléchargement identique depuis un vrai Postgres via Testcontainers) ne peut pas s'exécuter parce que Docker/Testcontainers est inopérant ⇒ HALT `blocked` : la preuve observable est non négociable.

**Never:**
- Aucune UI (Story 1.5), aucun retrait ni transition de statut (Epic 2), aucun dépôt.
- Aucun `Content-Disposition: inline`, jamais. Aucune fuite de `storageKey` dans la réponse ou les en-têtes.
- Aucun accès à S3/MinIO hors de l'adaptateur `MinioEvidenceStorage`. La couche web ne référence ni `EvidenceStorage`, ni l'AWS SDK.
- Aucun `findById(eid)` non scellé suivi d'une comparaison manuelle comme unique garde (la requête scellée est l'anti-IDOR de référence).
- Aucun filtre de statut : une pièce `WITHDRAWN` reste téléchargeable (visibilité contradictoire — la restitution du binaire n'est pas un masquage).
- Aucun nouveau handler global si une traduction en exception déjà mappée suffit ; aucune pagination, aucun gold-plating.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Téléchargement nominal | Partie prenante ; pièce `{eid}` appartenant à `{id}` | `200`, corps = binaire **identique** octet pour octet, `Content-Disposition: attachment` avec le nom d'origine, `Content-Type` = MIME stocké, `Content-Length` = taille | Aucune |
| Pièce retirée téléchargeable | Partie prenante ; pièce `{eid}` `WITHDRAWN` de `{id}` | `200`, binaire servi normalement | Aucune |
| Pièce étrangère à la transaction (IDOR) | Partie prenante de `{id}` ; `{eid}` appartient à une autre transaction | Aucune donnée servie | `404` |
| Pièce inexistante | `{eid}` inconnu | Aucune donnée servie | `404` |
| Non partie prenante | Utilisateur étranger à `{id}` tentant une de ses pièces | Aucune donnée servie | `403` (avant recherche de pièce) |
| Transaction inconnue | `{id}` inexistant | Aucune donnée servie | `404` |
| Binaire absent du stockage | Métadonnée présente mais objet introuvable (`EvidenceNotFoundException`) | Aucune donnée servie | `404` (traduit en `NotFoundException`) |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` -- MODIFIER : ajouter `Optional<EvidenceFile> findByIdAndTransactionId(Long id, Long transactionId)` (requête dérivée scellée = anti-IDOR). Ne pas toucher la requête de liste.
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- MODIFIER : ajouter `@Transactional(readOnly = true) EvidenceDownload download(AuthPrincipal actor, Long txId, Long evidenceId)` : `findById` (404) → `resolveRole` (403) → `findByIdAndTransactionId` (404) → `storage.load(storageKey)`, traduire `EvidenceNotFoundException` → `NotFoundException` (404). Ne pas toucher `deposit`/`list`.
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceDownload.java` -- CRÉER : `record EvidenceDownload(InputStream content, String filename, String contentType, long sizeBytes)` — porteur binaire service→web (pas un DTO JSON, n'expose pas `storageKey`).
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` -- MODIFIER : ajouter `@GetMapping("/{id}/evidence/{evidenceId}/download") ResponseEntity<InputStreamResource> download(...)` construisant `Content-Disposition: attachment`, content-type et `Content-Length`. Ne pas toucher `deposit`/`list`.
- `backend/src/main/java/com/zlecaf/escrow/service/storage/EvidenceStorage.java` -- LIRE : `InputStream load(String storageKey)`, lève `EvidenceNotFoundException` sur absence (API figée en 1.1).
- `backend/src/main/java/com/zlecaf/escrow/service/TransactionAccess.java` -- LIRE : `resolveRole` (lève `ForbiddenException`), API figée.
- `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` -- LIRE : `getStorageKey()`, `getMimeType()`, `getOriginalFilename()`, `getSizeBytes()`, `getTransactionId()`.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- MODIFIER : ajouter les tests de téléchargement sur le harnais existant (`@DataJpaTest` + Postgres Testcontainer, `InMemoryEvidenceStorage`). Ne pas altérer les 16 tests existants.
- `backend/src/test/java/com/zlecaf/escrow/web/EvidenceControllerDownloadTest.java` -- CRÉER : test ciblé du controller (service mocké) prouvant l'en-tête `Content-Disposition: attachment` (jamais inline) et le content-type — le contrôle anti-XSS est observable.

## Tasks & Acceptance

**Execution:**
- [x] `backend/src/main/java/.../repository/EvidenceFileRepository.java` -- Ajouter `findByIdAndTransactionId(Long, Long)` -- requête scellée = anti-IDOR de référence.
- [x] `backend/src/main/java/.../service/EvidenceDownload.java` -- Créer le record porteur binaire (flux + nom + MIME + taille) -- transporte le binaire sans exposer `storageKey`.
- [x] `backend/src/main/java/.../service/EvidenceService.java` -- Ajouter `download(actor, txId, evidenceId)` : 404 tx → 403 appartenance → 404 pièce scellée → `storage.load` → traduction `EvidenceNotFoundException`→404 -- cœur du téléchargement sécurisé.
- [x] `backend/src/main/java/.../web/EvidenceController.java` -- Ajouter `@GetMapping(".../download")` construisant `attachment` + content-type + `Content-Length` -- surface web binaire (nouveau pattern).
- [x] `backend/src/test/java/.../service/EvidenceServiceTest.java` -- Couvrir : round-trip octet pour octet, pièce `WITHDRAWN` téléchargeable, pièce étrangère 404 (IDOR), pièce inconnue 404, non-partie 403, tx inconnue 404 -- preuve observable sur Postgres réel.
- [x] `backend/src/test/java/.../web/EvidenceControllerDownloadTest.java` -- Asserter `Content-Disposition: attachment` (`isAttachment()` vrai, jamais inline), content-type = MIME, corps = binaire -- le contrôle anti-XSS est prouvé.

**Acceptance Criteria:**
- Given une pièce `{eid}` appartenant à `{id}` dont je suis partie prenante, when j'appelle `GET .../{id}/evidence/{eid}/download`, then `200`, le binaire est servi **identique** octet pour octet depuis `EvidenceStorage`, en `Content-Disposition: attachment` (jamais inline), content-type = MIME stocké.
- Given une pièce `{eid}` n'appartenant PAS à `{id}` (ou inexistante), when je tente le téléchargement, then `404` — l'appartenance est vérifiée par la requête scellée, sans révéler d'existence.
- Given une transaction dont je ne suis pas partie prenante, when je tente le téléchargement d'une de ses pièces, then `403` (avant toute recherche de pièce).
- Given le code livré, when on relit le diff, then la couche web ne référence ni `EvidenceStorage` ni l'AWS SDK, `storageKey` ne fuite pas, et aucune restitution `inline` n'existe.
- Given le refactor, when on exécute `mvn clean test`, then les 44 tests préexistants restent verts et les nouveaux tests (service + controller) passent.

## Spec Change Log

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 1: (high 0, medium 1, low 0)
- defer: 2: (high 0, medium 0, low 2)
- reject: 7: (high 0, medium 0, low 7)
- addressed_findings:
  - `[medium]` `[patch]` Fuite de flux : `EvidenceService.download` ouvrait le flux S3 **avant** que la réponse ne soit sûrement construite. Deux colonnes nullables au schéma (`size_bytes`, `mime_type` — V2 sans NOT NULL) rendent le post-ouverture faillible : `getSizeBytes()` null ⇒ NPE d'unboxing dans le service ; `mime_type` non parseable ⇒ `InvalidMediaTypeException` dans le controller. Dans les deux cas le flux ouvert n'était jamais confié à Spring (donc jamais fermé) ⇒ connexion S3 fuitée, épuisement du pool à répétition. Non atteignable via le dépôt (validation content-sniffing + taille), mais réel au niveau schéma. Corrigé : (1) service — lecture de `filename`/`contentType`/`sizeBytes` **avant** `storage.load` (l'unboxing null échoue avant l'ouverture) ; (2) controller — construction de la réponse sous `try/catch (RuntimeException)` qui ferme le flux (`closeQuietly`) avant de relancer ; (3) test de non-régression `downloadClosesStreamWhenResponseBuildFails` (content-type non parseable + flux traceur ⇒ `closed == true`).

<!-- Reports (2, low) → deferred-work.md :
     (a) Le téléchargement n'écrit aucune entrée d'audit alors que le dépôt le fait — trou de non-répudiation sur le chemin de lecture (qui a téléchargé quelle preuve, quand), sensible en contexte de litige. Décision produit + design (audit MANDATORY dans une transaction readOnly), hors intent capturé de 1.4.
     (b) Les échecs stockage autres que `EvidenceNotFoundException` (MinIO indisponible, timeout, S3Exception) ne sont pas mappés : réponse 500 whitelabel hors enveloppe JSON de la plateforme. Pré-existant (le dépôt appelle aussi `storage.store` sans mapping) ; durcissement du contrat d'erreur de niveau plateforme.
     Rejets (7, tous low, par-design ou pré-existants) : oracle 403-avant-404 (convention plateforme, déjà rejetée en 1.2/1.3) ; `Content-Length` depuis la DB (objet immuable write-once, `storage_key` UNIQUE, pas de cycle de vie POC) ; `WITHDRAWN` téléchargeable (AD-4, « aucun filtre de statut » par-design) ; fallback nom de fichier null (`ContentDisposition.filename(null)` bénin, le navigateur nomme le fichier — cosmétique) ; en-tête `nosniff` (l'`attachment` couvre déjà NFR-2, aucun endpoint n'a de header sécurité dédié, pas de filtre global) ; `MethodArgumentTypeMismatchException` hors enveloppe (pré-existant, tous controllers, déjà rejeté en 1.2/1.3) ; trous de test sur des cas non-défauts (null mime/size non atteignables via dépôt). -->

## Design Notes

**Porteur binaire + construction de la réponse.** Le service ouvre le flux (accès stockage confiné au service) ; le controller ne câble que les en-têtes. Squelette (guide, pas prescription) :

```java
// EvidenceService
@Transactional(readOnly = true)
public EvidenceDownload download(AuthPrincipal actor, Long txId, Long evidenceId) {
    EscrowTransaction tx = transactions.findById(txId)
            .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
    access.resolveRole(actor, tx);                                   // 403 si non partie
    EvidenceFile ev = evidenceFiles.findByIdAndTransactionId(evidenceId, txId)
            .orElseThrow(() -> new NotFoundException("Evidence " + evidenceId + " not found")); // 404 anti-IDOR
    try {
        InputStream in = storage.load(ev.getStorageKey());
        return new EvidenceDownload(in, ev.getOriginalFilename(), ev.getMimeType(), ev.getSizeBytes());
    } catch (EvidenceNotFoundException e) {
        throw new NotFoundException("Evidence binary not found for " + evidenceId); // 404
    }
}
```

```java
// EvidenceController
@GetMapping("/{id}/evidence/{evidenceId}/download")
public ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal AuthPrincipal actor,
        @PathVariable Long id, @PathVariable Long evidenceId) {
    EvidenceDownload d = evidenceService.download(actor, id, evidenceId);
    ContentDisposition cd = ContentDisposition.attachment().filename(d.filename(), StandardCharsets.UTF_8).build();
    return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
            .contentType(MediaType.parseMediaType(d.contentType()))
            .contentLength(d.sizeBytes())
            .body(new InputStreamResource(d.content()));
}
```

**Cycle de vie du flux.** L'`InputStream` provient de S3/MinIO, indépendant de la transaction DB (qui se referme au retour du service) : Spring le consomme après le retour du controller. Ne pas lire le flux en mémoire côté service.

**Test controller sans infra web lourde.** Le header étant le contrôle de sécurité (et non une délégation triviale comme la liste de 1.3), il mérite un test — mais sans `@WebMvcTest` + sécurité : instancier le controller avec un `EvidenceService` mocké (Mockito) retournant un `EvidenceDownload` de binaire connu, appeler `controller.download(...)`, puis asserter `response.getHeaders().getContentDisposition().isAttachment()` vrai, `isInline()` faux, content-type et corps corrects. Rapide, cible l'invariant anti-XSS.

**Test service — round-trip.** Persister une transaction + une `EvidenceFile` dont `storageKey` référence un binaire préchargé dans `InMemoryEvidenceStorage` (ou déposer via `deposit` puis télécharger). Lire entièrement `download(...).content()` et asserter l'égalité octet pour octet avec la source, plus `filename`/`contentType`/`sizeBytes`. IDOR : pièce de la tx A, appel via l'id de la tx B ⇒ `NotFoundException`. Non-partie ⇒ `ForbiddenException`.

## Verification

**Commands:**
- `cd backend && mvn clean test` -- expected : BUILD SUCCESS ; 44 tests préexistants verts + nouveaux tests service (round-trip, IDOR 404, non-partie 403, tx inconnue 404, `WITHDRAWN`) et controller (attachment).
- `grep -rn "inline" backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` -- expected : aucun résultat (jamais de restitution inline).
- `grep -rln "software.amazon.awssdk\|EvidenceStorage" backend/src/main/java/com/zlecaf/escrow/web` -- expected : aucun résultat (la couche web ne connaît ni S3 ni le port de stockage).
- `grep -rn "storageKey\|StorageKey" backend/src/main/java/com/zlecaf/escrow/service/EvidenceDownload.java` -- expected : aucun résultat (le porteur n'expose pas la clé).

**Manual checks:**
- Stack up : `GET /api/v1/escrow/{id}/evidence/{eid}/download` (JWT partie prenante) → `200`, en-tête `Content-Disposition: attachment; filename=...`, binaire identique ; `{eid}` d'une autre transaction → `404` ; JWT non partie → `403`.

## Auto Run Result

Status: done
Blocking condition: aucune

### Changement implémenté

Le **téléchargement sécurisé** du binaire d'une preuve est câblé : `GET /api/v1/escrow/{id}/evidence/{eid}/download` sert le fichier original en `Content-Disposition: attachment` (jamais inline — anti-XSS stocké, NFR-2), content-type = MIME stocké, `Content-Length` = taille enregistrée. `EvidenceService.download` (`@Transactional(readOnly = true)`) applique l'autorité serveur dans l'ordre strict : `findById` transaction (404) → `TransactionAccess.resolveRole` (403 non partie, avant toute recherche de pièce) → requête **scellée** `findByIdAndTransactionId(eid, txId)` (404 anti-IDOR — une pièce inconnue ou étrangère est indistinguable, sans révéler d'existence) → ouverture du flux via le **port** `EvidenceStorage.load`. La couche web ne connaît ni S3 ni le port de stockage ; `storageKey` ne fuite pas ; une pièce `WITHDRAWN` reste téléchargeable (restitution ≠ masquage). Un objet stockage absent est traduit en 404.

### Fichiers

**Créés**
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceDownload.java` — record porteur binaire service→web (`InputStream content, filename, contentType, sizeBytes`), n'expose pas `storageKey`.
- `backend/src/test/java/com/zlecaf/escrow/web/EvidenceControllerDownloadTest.java` — test ciblé du controller (service mocké, sans contexte web/sécurité) : en-tête `attachment` (jamais inline) + content-type + corps, **et** fermeture du flux si la construction de la réponse échoue.

**Modifiés**
- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` — requête scellée `findByIdAndTransactionId` (anti-IDOR).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` — méthode `download` (404 tx → 403 appartenance → 404 pièce scellée → `storage.load`, traduction `EvidenceNotFoundException`→404) ; métadonnées lues **avant** l'ouverture du flux (resource-safety).
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` — `@GetMapping(".../download")` construisant la réponse binaire sous garde `try/catch` fermant le flux en cas d'échec.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` — 7 tests de téléchargement (round-trip octet pour octet, `WITHDRAWN`, pièce étrangère 404/IDOR, pièce inconnue 404, non-partie 403, tx inconnue 404, objet absent 404) sur le harnais Postgres Testcontainer existant.

### Revue

2 revues adversariales parallèles (Blind Hunter + Edge Case Hunter). Aucun défaut d'authz/injection exploitable (IDOR scellé, encodage RFC 5987 anti-injection d'en-tête, `attachment`, `storageKey` serveur — tous corrects). 1 correctif appliqué (medium), 2 points reportés (low), 7 rejetés (tous low). Aucun `intent_gap`, aucun `bad_spec` : zéro boucle de reprise.

- **Patché** (medium) : fuite de flux S3 si la construction de la réponse échoue après l'ouverture (colonnes `size_bytes`/`mime_type` nullables au schéma). Corrigé côté service (lecture des métadonnées avant `load`) et controller (fermeture du flux sous garde), + test de non-régression.
- **Reportés** (low, `deferred-work.md`) : (a) téléchargement non audité (non-répudiation sur le chemin de lecture) ; (b) échecs stockage hors « objet absent » non mappés → 500 whitelabel (pré-existant, concerne aussi le dépôt).
- **Rejetés** (7, tous low — justification en commentaire du *Review Triage Log*) : oracle 403/404, `Content-Length` depuis la DB, `WITHDRAWN` téléchargeable (par-design), fallback nom null (bénin), `nosniff` (attachment couvre NFR-2), `MethodArgumentTypeMismatch` hors enveloppe (pré-existant), trous de test sur non-défauts.

### Vérification

- `cd backend && mvn clean test` → **BUILD SUCCESS ; 53 tests, 0 échec** (44 préexistants intacts + 8 de la story + 1 de non-régression du patch ; `EvidenceServiceTest` 16→23, `EvidenceControllerDownloadTest` = 2). Docker/Testcontainers disponible. SQL confirmé : `where ef1_0.id=? and ef1_0.transaction_id=?` (requête scellée anti-IDOR).
- Grep : aucun `inline` dans le controller ; la couche web ne référence ni `software.amazon.awssdk` ni `EvidenceStorage` ; `EvidenceDownload` ne mentionne pas `storageKey`.

### Risques résiduels

- **Téléchargement non audité** (reporté) : pas de trace de consultation du binaire — à trancher avant l'Epic 2 (litige).
- **Contrat d'erreur stockage** (reporté) : une panne MinIO renvoie un 500 whitelabel hors enveloppe.
- **`mvn test` exige Docker** : `EvidenceServiceTest` (Postgres) et `MinioEvidenceStorageTest` (MinIO) reposent sur Testcontainers.
