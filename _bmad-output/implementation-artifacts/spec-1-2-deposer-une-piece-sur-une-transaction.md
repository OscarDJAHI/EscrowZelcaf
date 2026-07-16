---
title: 'Story 1.2 — Déposer une pièce sur une transaction'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: 'e69d1a010590a01b059549de7039aeb86e53929f'
final_revision: '2bf832e'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** Le socle de la Story 1.1 (table `evidence_files`, port `EvidenceStorage`, backend MinIO) existe mais n'est câblé à aucune couche web : aucune partie prenante ne peut encore déposer une preuve. Les stories 1.3 à 1.5 et l'ouverture composite de l'Epic 2 sont bloquées sur ce chemin d'écriture.

**Approach:** Exposer `POST /api/v1/escrow/{id}/evidence` (multipart `files[]` 1..N, `comment` optionnel, `clientCapturedAt` optionnel). Un `EvidenceService` transactionnel orchestre : contrôle d'appartenance partagé, garde de fenêtre d'état, validation de contenu par content-sniffing Tika réutilisable, stockage via le port, écriture d'une ligne `ACTIVE` et d'une entrée d'audit `EVIDENCE_ADDED` **dans la même transaction**. La validation et le contrôle d'appartenance sont conçus comme des composants réutilisables — l'Epic 2 devra les réemployer sans duplication.

## Boundaries & Constraints

**Always:**
- **Autorité serveur, anti-IDOR.** Le service charge la transaction de l'URL, puis passe par le **contrôle d'appartenance unique et partagé** `TransactionAccess.resolveRole(actor, tx)` (extrait d'`EscrowService`, comportement identique) : non partie ⇒ `ForbiddenException` (403). `uploaderType` est **dérivé** du `ParticipantRole` retourné (BUYER/SELLER/ADMIN), jamais du client.
- **Garde de fenêtre fondée sur l'état.** Dépôt autorisé **ssi** `tx.state ∈ {FUNDS_LOCKED, SHIPPED, DISPUTED}`. Tout autre état (dont `INITIATED`, `RELEASED`, `REFUNDED`) ⇒ `ConflictException` (409). Jamais dérivé du dernier événement.
- **Validation par le type réel du contenu.** `EvidenceContentValidator` (composant réutilisable enveloppant Tika) sniffe les octets : accepté **ssi** le type réel ∈ {`image/jpeg`, `image/png`, `application/pdf`}. Incohérence entre type réel, extension du nom fourni et `Content-Type` déclaré ⇒ rejet `BadRequestException` (400).
- **Bornes de taille arbitrées par le service.** `0 < taille ≤ 10 485 760` octets par fichier, vérifié dans le service (pas par le conteneur multipart) ⇒ hors borne `BadRequestException` (400). Le multipart Spring est réglé **au-dessus** de 10 Mo pour que le service tranche ; le dépassement du plafond conteneur (`MaxUploadSizeExceededException`) est mappé → 400.
- **Atomicité.** Pour chaque fichier accepté : `EvidenceStorage.store(txId, bytes, sniffedMime)` → clé opaque, puis ligne `evidence_files` (`status=ACTIVE`, `created_at` = heure serveur via `@PrePersist`), puis `AuditService.recordEvidenceAdded(...)` en **propagation MANDATORY** (payload `action=EVIDENCE_ADDED`, `evidenceId`, `sha256`, `clientCapturedAt` si fourni). Ligne métier et audit commitent ensemble. Un lot `files[]` est **tout-ou-rien** : la validation de **tous** les fichiers précède tout stockage/persistance ; un seul rejet ⇒ rien n'est écrit.
- **Anti-path-traversal.** La clé de stockage est générée par l'adaptateur (`{txId}/{uuid}`), jamais dérivée du nom fourni. `original_filename` est **assaini** (basename seul, sans séparateur de chemin) et conservé en simple métadonnée.
- **Conventions brownfield ratifiées** (cf. Code Map) : controller mince, injection par constructeur sans Lombok, `@Transactional` par méthode sur le service, DTO en `record` avec `static from()`, FK scalaires `Long`, exceptions applicatives existantes (aucun nouveau type d'exception métier hormis le handler multipart).

**Block If:**
- Le round-trip observable (persistance `evidence_files` + `audit_logs` prouvée contre un vrai Postgres via Testcontainers) ne peut pas s'exécuter parce que Docker/Testcontainers est inopérant ⇒ HALT `blocked` : la preuve observable est non négociable (même principe qu'en 1.1).
- `tika-core:3.3.1` s'avère absent de Maven Central au build (vérifié présent au moment de la planification) ⇒ HALT `blocked`.

**Never:**
- Aucun retrait/`WITHDRAWN` (Epic 2), aucune liste chronologique (Story 1.3), aucun téléchargement (Story 1.4), aucune UI (Story 1.5).
- Aucun chemin partenaire `CARRIER_PARTNER`/`partner_company_id` (Epic 3) : `uploadedByUserId` = `actor.userId()`, `partnerCompanyId` = `null`.
- Aucun scan antivirus (risque POC accepté). Aucune suppression physique. Aucune query custom sur `EvidenceFileRepository` (la liste est 1.3).
- Ne jamais dupliquer la logique de validation ou d'appartenance dans le controller : elles vivent dans des composants service réutilisables.
- Ne pas ordonner quoi que ce soit par `clientCapturedAt` : `created_at` serveur est la seule clé de tri.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Dépôt nominal | Partie prenante, `FUNDS_LOCKED`, un PDF valide 2 Mo, `comment` présent | `201`, 1 ligne `evidence_files` `ACTIVE`, `created_at` serveur, 1 `audit_logs` `EVIDENCE_ADDED` (même tx), corps = métadonnées | Aucune |
| Dépôt multi-fichiers | Partie prenante, `SHIPPED`, `files[]` = {JPG valide, PNG valide} | `201`, 2 lignes + 2 entrées d'audit, atomiques | Aucune |
| Type réel refusé | Fichier `.pdf` dont les octets sont un exécutable | Aucune écriture | `400` message explicite (type non autorisé) |
| Incohérence déclarée | Octets = PNG, extension `.pdf` / `Content-Type: application/pdf` | Aucune écriture | `400` (incohérence type réel / extension / Content-Type) |
| Fichier vide | `files[]` contient un fichier 0 octet | Aucune écriture | `400` |
| Hors borne | Fichier de 10 485 761 octets | Aucune écriture | `400` (limite service) |
| Plafond conteneur | Fichier ≫ plafond multipart | Aucune écriture | `MaxUploadSizeExceededException` → `400` |
| Non partie prenante | Utilisateur étranger à la transaction | Aucune écriture | `403` |
| Fenêtre fermée | Partie prenante mais `RELEASED` (ou `INITIATED`) | Aucune écriture | `409` |
| Transaction inconnue | `{id}` inexistant | Aucune écriture | `404` |
| Lot partiellement invalide | `files[]` = {JPG valide, fichier vide} | **Rien** n'est persisté (tout-ou-rien) | `400`, 0 ligne, 0 audit |
| `clientCapturedAt` invalide | Chaîne non ISO-8601 | Aucune écriture | `400` |

</intent-contract>

## Code Map

- `backend/pom.xml` -- MODIFIER : ajouter `org.apache.tika:tika-core:3.3.1` (version explicite — non gérée par le BOM Boot) et `org.testcontainers:postgresql` en scope `test` (version du BOM Testcontainers surchargé en 1.21.4, sans version explicite).
- `backend/src/main/resources/application.yml` -- MODIFIER : ajouter `spring.servlet.multipart` (`max-file-size` et `max-request-size` **au-dessus** de 10 Mo, p. ex. 15 Mo / 60 Mo) — absent aujourd'hui, défauts Boot 1 Mo/10 Mo sinon.
- `backend/src/main/java/com/zlecaf/escrow/service/TransactionAccess.java` -- CRÉER : `@Component` exposant `ParticipantRole resolveRole(AuthPrincipal, EscrowTransaction)` (lève `ForbiddenException`). Contrôle d'appartenance unique réutilisable.
- `backend/src/main/java/com/zlecaf/escrow/service/EscrowService.java` -- MODIFIER : injecter `TransactionAccess`, remplacer les `private resolveRole`/`authorizeView` par une délégation **iso-comportement** (aucune régression de `EscrowStateMachineTest` ni des chemins existants).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceContentValidator.java` -- CRÉER : `@Component` Tika ; `String validate(byte[] content, String originalFilename, String declaredContentType)` → MIME réel validé, ou lève `BadRequestException`.
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- CRÉER : orchestrateur `@Transactional` (appartenance → garde d'état → validation/taille → store → persist → audit), 1..N atomique.
- `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` -- MODIFIER : ajouter `recordEvidenceAdded(Long txId, Long actorId, ParticipantRole role, EscrowState currentState, Long evidenceId, String sha256, String clientCapturedAt)` en `Propagation.MANDATORY` (payload `action=EVIDENCE_ADDED` ; `previous==next==currentState`). Ne pas toucher aux méthodes existantes.
- `backend/src/main/java/com/zlecaf/escrow/web/dto/EvidenceDtos.java` -- CRÉER : `record EvidenceDto(...) { static from(EvidenceFile) }` (holder final, constructeur privé — idiome `AuthDtos`/`EscrowDtos`).
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` -- CRÉER : `@RestController`, `POST /api/v1/escrow/{id}/evidence`, params `@AuthenticationPrincipal AuthPrincipal`, `@RequestParam("files") List<MultipartFile>`, `comment`/`clientCapturedAt` optionnels ; renvoie `ResponseEntity.status(CREATED).body(List<EvidenceDto>)`.
- `backend/src/main/java/com/zlecaf/escrow/web/GlobalExceptionHandler.java` -- MODIFIER : ajouter `@ExceptionHandler(MaxUploadSizeExceededException.class)` → `400` via le helper `body(...)` existant. Aucun autre mapping modifié.
- `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` / `repository/EvidenceFileRepository.java` / `service/storage/EvidenceStorage.java` -- LIRE : API figée de la Story 1.1 (entité, `store(Long,byte[],String)`).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceContentValidatorTest.java` -- CRÉER : unitaire pur (sans Spring), couvre la matrice accept/reject sur octets réels JPG/PNG/PDF + incohérences.
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` -- CRÉER : `@DataJpaTest` + Postgres Testcontainer prouvant persistance `evidence_files` + `audit_logs` dans la même transaction, garde d'état, appartenance, tout-ou-rien.

## Tasks & Acceptance

**Execution:**
- [x] `backend/pom.xml` -- Ajouter `tika-core:3.3.1` (compile) et `org.testcontainers:postgresql` (test, sans version) -- dépendances du sniffing et du test d'intégration.
- [x] `backend/src/main/resources/application.yml` -- Ajouter `spring.servlet.multipart.{max-file-size,max-request-size}` > 10 Mo -- le service arbitre la vraie limite.
- [x] `backend/src/main/java/.../service/TransactionAccess.java` -- Extraire le contrôle d'appartenance en `@Component` réutilisable -- point unique pour tous les endpoints preuve (Epics 2-4).
- [x] `backend/src/main/java/.../service/EscrowService.java` -- Déléguer à `TransactionAccess` sans changer le comportement observable -- éviter deux règles d'accès divergentes.
- [x] `backend/src/main/java/.../service/EvidenceContentValidator.java` -- Composant Tika de validation tri-cohérence + whitelist -- réutilisé tel quel par l'Epic 2.
- [x] `backend/src/main/java/.../service/AuditService.java` -- Ajouter `recordEvidenceAdded(...)` MANDATORY -- audit atomique du dépôt.
- [x] `backend/src/main/java/.../service/EvidenceService.java` -- Orchestration transactionnelle 1..N atomique -- cœur métier réutilisable.
- [x] `backend/src/main/java/.../web/dto/EvidenceDtos.java` -- `EvidenceDto` + `from()` -- contrat de réponse.
- [x] `backend/src/main/java/.../web/EvidenceController.java` -- Endpoint multipart mince déléguant au service -- surface web.
- [x] `backend/src/main/java/.../web/GlobalExceptionHandler.java` -- Mapper `MaxUploadSizeExceededException` → 400 -- borne conteneur cohérente avec la borne service.
- [x] `backend/src/test/java/.../service/EvidenceContentValidatorTest.java` -- Couvrir chaque ligne de contenu de la matrice I/O -- preuve de la validation.
- [x] `backend/src/test/java/.../service/EvidenceServiceTest.java` -- Prouver persistance + audit même-transaction + gardes sur Postgres réel -- preuve observable ; referme aussi le trou entité↔schéma reporté en 1.1.

**Acceptance Criteria:**
- Given une transaction dont je suis partie prenante à l'état `FUNDS_LOCKED`/`SHIPPED`/`DISPUTED`, when j'appelle `POST .../{id}/evidence` avec un JPG/PNG/PDF valide ≤ 10 Mo, then `201`, une ligne `evidence_files` `ACTIVE` avec `created_at` serveur et une entrée `audit_logs` `EVIDENCE_ADDED` sont créées dans la même transaction.
- Given le code livré, when on relit `GlobalExceptionHandler` et `EvidenceController`, then aucune règle d'appartenance ni de validation n'est dupliquée dans le controller (elles vivent dans `TransactionAccess` et `EvidenceContentValidator`).
- Given le refactor de `EscrowService`, when on exécute `mvn test`, then `EscrowStateMachineTest`, `HmacSignerTest` et `MinioEvidenceStorageTest` restent verts (aucune régression) et les nouveaux tests passent.
- Given aucun type S3/Tika ne doit fuir, when on `grep` la couche web, then `EvidenceController` ne référence ni `software.amazon.awssdk` ni `org.apache.tika`.
- Given le périmètre, when on relit le diff, then il ne contient ni retrait, ni liste, ni téléchargement, ni chemin partenaire.

## Spec Change Log

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3: (high 0, medium 2, low 1)
- defer: 2: (high 0, medium 1, low 1)
- reject: 10: (high 0, medium 0, low 10)
- addressed_findings:
  - `[medium]` `[patch]` Le dépôt lisait la transaction via `findById` (sans verrou) alors que `EscrowService.applyEvent` prend un `findByIdForUpdate` (PESSIMISTIC_WRITE) : course TOCTOU où une transition `RELEASE`/`REFUND` concurrente pouvait valider entre la garde de fenêtre et le commit, laissant une preuve atterrir sur un état terminal. Corrigé : `deposit` prend le même verrou pessimiste (`... for no key update` confirmé en test).
  - `[medium]` `[patch]` `sanitizeFilename` ne bornait pas la longueur alors que `original_filename` est `VARCHAR(255)` : un basename > 255 (valide par ailleurs) déclenchait un `DataIntegrityViolationException` non mappé → 500 (et un objet MinIO orphelin). Corrigé : troncature à 255 en conservant la queue (l'extension survit) + test `overlongFilenameIsTruncated`.
  - `[low]` `[patch]` Une requête multipart sans la partie `files` levait `MissingServletRequestPartException`, non gérée par `GlobalExceptionHandler` → réponse hors enveloppe standard. Corrigé : handler `MissingServletRequestPart/Parameter` → 400 dans l'enveloppe usuelle.
  - Couverture ajoutée (comblant des lignes de la matrice I/O non testées) : `multiFileDepositPersistsAll` (2 fichiers → 2 lignes + 2 audits) et `invalidClientCapturedAtRejected`. Suite : 37 tests, 0 échec (les 22 préexistants intacts).

<!-- Rejets (10, tous low, bruit ou par-design) : 404-avant-403 (énumération d'ID — convention déjà en vigueur dans `EscrowService.getDetail`, la diverger sur ce seul endpoint créerait une incohérence) ; `MethodArgumentTypeMismatchException` sur id non numérique (comportement à l'échelle de tous les controllers, préexistant) ; rejet Tika sur octets de tête (spéculatif, aucun input défaillant concret ; le sniffing strict est l'intention de la spec) ; `clientCapturedAt` sans offset rejeté (exiger un offset explicite pour un instant de capture est défendable et non ambigu) ; commentaire non borné (gold-plating POC) ; valeur de `clientCapturedAt` non fiable (par-design, métadonnée d'audit qui n'ordonne rien) ; `sha256` seulement en audit (conforme à la spec) ; PDF polyglotte (AV = risque explicitement accepté) ; 400 vs 413 sur dépassement conteneur (la spec impose 400) ; branche null morte dans `recordEvidenceAdded` (inoffensive, calquée sur `recordFailure` par cohérence). -->

## Design Notes

**Décisions résolues (arbitrées, non bloquantes) :**

1. **Contrôle d'appartenance — extraction, pas duplication.** L'invariant d'architecture (« contrôle unique réutilisé par les Epics 2-4 », « deux règles divergentes = défaut ») impose un composant partagé. `EscrowService.resolveRole` est aujourd'hui `private`. On l'extrait en `TransactionAccess.resolveRole` (retourne `ParticipantRole`, lève `ForbiddenException` — texte de message inchangé) et `EscrowService` délègue. `authorizeView` (chemins lecture) devient un `resolveRole(...)` dont on ignore la valeur, ou reste délégué — comportement observable identique, c'est le critère.

2. **Fenêtre d'état → 409.** La fenêtre fermée est un conflit avec l'état courant de la ressource : `ConflictException` (→409) pour tous les états hors `{FUNDS_LOCKED, SHIPPED, DISPUTED}`, y compris les terminaux. L'AC de l'epic tolère « 409/400 » ; on fige 409 pour cohérence avec l'enveloppe existante.

3. **`files[]` 1..N tout-ou-rien.** Le contrat multipart figé (`files[]`, `comment`, `clientCapturedAt`) est identique sur tous les points d'entrée (AD contrat figé) — divergence = casse du rejeu offline Epic 4. On accepte donc l'array dès maintenant. Ordre impératif dans le service : (a) valider **tous** les fichiers (type + taille), (b) puis, pour chacun, store → persist → audit. Ainsi un rejet survient avant toute écriture ; le `@Transactional` du service garantit le rollback si une écriture ultérieure échoue.

4. **Audit du dépôt.** `AuditService` ne peut pas auditer un dépôt via son API existante (typée `EscrowEvent`, `save()` privé). On ajoute `recordEvidenceAdded(...)` en `Propagation.MANDATORY` (rejoint la tx du service — atomicité structurelle). Le dépôt n'est pas un changement d'état : `previous == next == tx.state` (précédent : `recordFailure`). Payload : `action=EVIDENCE_ADDED`, `evidenceId`, `sha256` (hex SHA-256 des octets), `clientCapturedAt` si fourni. Le schéma `audit_logs` ne bouge pas (JSONB schemaless).

5. **Forme du test observable.** `EvidenceContentValidatorTest` est unitaire pur (Tika sur octets fixtures, aucun Spring). `EvidenceServiceTest` est le premier test à booter un contexte JPA : `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = NONE)` + `PostgreSQLContainer` (Flyway applique `V1`+`V2`), `@Import`ant `EvidenceService`, `AuditService`, `EvidenceContentValidator`, `TransactionAccess` et une `EvidenceStorage` en mémoire (fake) via `@TestConfiguration`. Il prouve : ligne `ACTIVE` persistée + `audit_logs` écrit dans la même tx (referme le trou entité↔schéma reporté en 1.1), garde d'état, appartenance, et tout-ou-rien sur lot invalide. Suffixe `*Test` (Surefire ; pas de failsafe configuré).

```java
// EvidenceService — squelette d'orchestration (guide, pas prescription ligne à ligne)
@Transactional
public List<EvidenceFile> deposit(AuthPrincipal actor, Long txId,
                                  List<MultipartFile> files, String comment, String clientCapturedAt) {
    EscrowTransaction tx = transactions.findById(txId)
            .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
    ParticipantRole role = access.resolveRole(actor, tx);        // 403 si non partie
    requireUploadWindow(tx.getState());                          // 409 hors fenêtre
    // 1) valider TOUS les fichiers (type réel + taille) — aucune écriture ici
    // 2) pour chacun : store(txId, bytes, mime) → persist ACTIVE → recordEvidenceAdded(MANDATORY)
}
```

## Verification

**Commands:**
- `cd backend && mvn clean test` -- expected : BUILD SUCCESS ; `mvn clean` élimine les `.class` orphelins de l'ancien 1.2 dans `target/`. Nouveaux tests verts, 21 tests existants intacts.
- `grep -rl "software.amazon.awssdk\|org.apache.tika" backend/src/main/java/com/zlecaf/escrow/web` -- expected : aucun résultat (ni S3 ni Tika ne fuit dans la couche web).
- `grep -rn "spring.servlet.multipart" backend/src/main/resources/application.yml` -- expected : bloc présent, limites > 10 Mo.
- Manuel (stack up) : `POST /api/v1/escrow/{id}/evidence` (JWT partie prenante, `FUNDS_LOCKED`, PDF 2 Mo) → `201` ; `psql -c "select status, created_at from evidence_files"` → 1 ligne `ACTIVE` ; `select payload from audit_logs order by id desc limit 1` → `action=EVIDENCE_ADDED` avec `sha256`.

## Auto Run Result

Status: done
Blocking condition: aucune

### Changement implémenté

Le chemin d'écriture des preuves est câblé de bout en bout : `POST /api/v1/escrow/{id}/evidence` (multipart `files[]` 1..N, `comment`/`clientCapturedAt` optionnels) déposé sur un `EvidenceService` transactionnel qui enchaîne appartenance partagée → garde de fenêtre d'état → validation de contenu Tika → stockage via le port → ligne `evidence_files` `ACTIVE` + entrée d'audit `EVIDENCE_ADDED`, le tout atomique côté base. Le contrôle d'appartenance et la validation sont des composants réutilisables, prêts pour l'ouverture composite de l'Epic 2.

### Fichiers

**Créés**
- `backend/src/main/java/com/zlecaf/escrow/service/TransactionAccess.java` — contrôle d'appartenance unique réutilisable (`resolveRole` → `ParticipantRole`, `ForbiddenException`).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceContentValidator.java` — validation Tika (whitelist JPG/PNG/PDF + tri-cohérence type réel / extension / Content-Type).
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` — orchestrateur `@Transactional`, dépôt 1..N tout-ou-rien.
- `backend/src/main/java/com/zlecaf/escrow/web/dto/EvidenceDtos.java` — `EvidenceDto` + `from()`.
- `backend/src/main/java/com/zlecaf/escrow/web/EvidenceController.java` — endpoint multipart mince (201).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceContentValidatorTest.java` — 6 tests (accept/reject de contenu).
- `backend/src/test/java/com/zlecaf/escrow/service/EvidenceServiceTest.java` — 9 tests `@DataJpaTest` + Postgres Testcontainer (round-trip persistance + audit, gardes, tout-ou-rien, multi-fichiers, nom trop long, `clientCapturedAt` invalide).

**Modifiés**
- `backend/pom.xml` — `tika-core:3.3.1` (compile) + `testcontainers:postgresql` (test).
- `backend/src/main/resources/application.yml` — bloc `spring.servlet.multipart` (15 Mo / 60 Mo, au-dessus de la limite service de 10 Mo).
- `backend/src/main/java/com/zlecaf/escrow/service/EscrowService.java` — délègue l'appartenance à `TransactionAccess` (iso-comportement).
- `backend/src/main/java/com/zlecaf/escrow/service/AuditService.java` — `recordEvidenceAdded(...)` en propagation MANDATORY.
- `backend/src/main/java/com/zlecaf/escrow/web/GlobalExceptionHandler.java` — mappe `MaxUploadSizeExceededException` et `MissingServletRequestPart/Parameter` → 400.

### Revue

3 correctifs appliqués (2 medium, 1 low), 2 points reportés, 10 rejetés. Détail et justification des rejets dans le *Review Triage Log* ; reports dans `deferred-work.md`. Aucun `intent_gap`, aucun `bad_spec` : zéro boucle de reprise.

- **Patchés** : verrou pessimiste sur le dépôt (course TOCTOU sur la fenêtre d'état) ; troncature du nom de fichier à 255 (évite un 500 sur `DataIntegrityViolationException`) ; enveloppe d'erreur uniforme sur partie `files` absente.
- **Reportés** : orphelinage d'objets MinIO sur échec de rollback (stockage non transactionnel — décision niveau port/architecture) ; absence de borne sur le nombre de fichiers par lot (durcissement).

### Vérification

- `mvn clean test` → **37 tests, 0 échec** (13 `EscrowStateMachineTest` + 3 `HmacSignerTest` + 6 `MinioEvidenceStorageTest` intacts ; 6 `EvidenceContentValidatorTest` + 9 `EvidenceServiceTest` nouveaux). Le SQL `... for no key update` confirme le verrou pessimiste sur le chemin de dépôt.
- `grep -rl "software.amazon.awssdk\|org.apache.tika" backend/src/main/java/com/zlecaf/escrow/web` → **aucun résultat** : ni S3 ni Tika ne fuit dans la couche web.
- `spring.servlet.multipart` présent (15 Mo / 60 Mo, > 10 Mo) — le service arbitre la vraie limite.

### Risques résiduels

- **Stockage non transactionnel** : un échec DB en milieu de lot peut orpheliner des objets MinIO (reporté ; déclencheur concret le plus probable neutralisé par les deux patchs).
- **`mvn test` exige Docker** : `EvidenceServiceTest` (Postgres) et `MinioEvidenceStorageTest` (MinIO) reposent sur Testcontainers, comme en 1.1.
- **Lot non borné** : mémoire proportionnelle au plafond de requête (60 Mo) × concurrence (reporté).
