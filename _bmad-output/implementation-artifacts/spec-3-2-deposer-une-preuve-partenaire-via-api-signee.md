---
title: 'Story 3.2 — Déposer une preuve partenaire via API signée'
type: 'feature'
created: '2026-07-16'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '7bd9d15b3493f69ab28e86f27eee8e41c52f7a3f'
final_revision: '96b4099294f9059241bb1a3e0705bd4acb873145'
context: []
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Un partenaire logistique (machine, sans écran ni compte interactif) doit pouvoir pousser une preuve horodatée de l'état de la marchandise sur une transaction qu'il sert. Le socle de persistance (clés HMAC entrantes durcies, magasin de nonces — Stories 3.1/3.3) et l'ingestion de preuves (validation, stockage, audit — Epic 1) existent, mais aucun endpoint ne les relie : il n'y a ni authentification par signature, ni anti-rejeu applicatif, ni contrôle « société impliquée », ni attribution `CARRIER_PARTNER`.

**Approach:** Ajouter l'endpoint machine `POST /api/v1/partner/escrow/{id}/evidence` (route `permitAll`, auth 100 % portée par la signature HMAC-SHA256). Un `PartnerEvidenceService` orchestre : résolution `key-id`→clé active, vérification de signature (via `HmacSigner.verify`) sur une chaîne canonique couvrant corps+métadonnées+horodatage, garde timestamp ±5 min, anti-rejeu par nonce scindé `key-id`, contrôle « société du partenaire partie à la transaction ». L'ingestion réelle **réutilise** le cœur d'`EvidenceService` (validation Tika, borne 10 Mo, clé de stockage opaque, audit MANDATORY) via un cœur `ingest(...)` extrait et paramétré par l'attribution — aucune règle d'ingestion dupliquée.

## Boundaries & Constraints

**Always:**
- Route `POST /api/v1/partner/escrow/{id}/evidence`, `multipart/form-data`, champs identiques aux autres dépôts : `files` (1..N), `comment` (opt), `clientCapturedAt` (ISO-8601, opt). En-têtes : `X-Escrow-Key-Id`, `X-Escrow-Signature`, `X-Escrow-Timestamp` (epoch secondes), `X-Escrow-Nonce`.
- **Chaîne canonique signée** (UTF-8, champs joints par `\n` LF, ordre figé) : `keyId` · `transactionId` · `timestamp` · `nonce` · `contentDigest` · `comment ?? ""` · `clientCapturedAt ?? ""`, où `contentDigest` = SHA-256 hex minuscule de chaque fichier dans l'ordre de la requête, joints par `,`. Signature = `HmacSigner.sign(canonical, secret)` (HMAC-SHA256, hex minuscule), vérifiée en temps constant via `HmacSigner.verify`.
- **Clé** résolue par `PartnerHmacKeyRepository.findByKeyIdAndActiveTrue` (actives seulement ⇒ révocation immédiate). Secret jamais logué ni renvoyé.
- **Anti-rejeu figé** : fenêtre `abs(now - timestamp) ≤ escrow.partner.timestamp-tolerance-seconds` (défaut 300). Nonce rejeté s'il existe déjà pour ce `key-id` (`existsByKeyIdAndNonce`) ; le nonce d'un dépôt réussi est **inséré dans la même transaction** que la preuve (la contrainte `uq_partner_key_nonces_key_nonce` est l'arbitre en cas de course → `DataIntegrityViolationException` traitée comme rejeu).
- **Attribution partenaire** : `uploader_type = CARRIER_PARTNER`, `partner_company_id = <companyId de la clé>`, `uploaded_by_user_id = null` — conforme au `CHECK ck_evidence_attribution` (V3). L'horodatage serveur (`created_at`) reste la source de vérité du tri.
- **Contrôle d'appartenance centralisé (anti-IDOR)** : « la société de la clé est-elle partie (acheteur OU vendeur) à la transaction `{id}` ? » — logique ajoutée à `TransactionAccess`, unique autorité d'appartenance. Sinon `403`.
- **Réutilisation stricte du cœur d'ingestion** : validation du type réel, borne de taille, atomicité tout-ou-rien du lot, clé de stockage opaque, nettoyage best-effort au rollback, et écriture d'audit `EVIDENCE_ADDED` (`evidenceId`, `sha256`) en propagation `MANDATORY` — tout passe par la **même** logique de service que le dépôt utilisateur.
- Réponses d'erreur dans **l'enveloppe JSON de la plateforme** (`GlobalExceptionHandler` : `{timestamp,status,error,message}`).

**Block If:**
- Le `CHECK ck_evidence_attribution` (V3) ou l'enum `UploaderType.CARRIER_PARTNER` diffèrent de l'investigation (le cœur d'ingestion ne pourrait pas écrire une ligne partenaire valide).

**Never:**
- Pas de JWT / `@AuthenticationPrincipal` sur cette route (auth = signature seule).
- Pas de scan antivirus (NFR-6, risque POC accepté et documenté) ; pas de notification asynchrone (le code HTTP synchrone suffit).
- Pas de réutilisation du secret **sortant** des webhooks ; pas de chiffrement au repos (POC).
- Pas de job `@Scheduled` de purge des nonces (la méthode `deleteBySeenAtBefore` reste non planifiée) ; pas de duplication de la validation/stockage/audit d'Epic 1.
- Ne pas déposer sur une transaction en état terminal (le cœur d'ingestion applique déjà la fenêtre `allowsEvidenceMutation` → `409`).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Dépôt signé valide | clé active, signature valide, timestamp dans ±5 min, nonce neuf, société partie, fichier valide, état non terminal | `201` + `List<EvidenceDto>` ; ligne `CARRIER_PARTNER`/`partner_company_id`/`user_id=null` ; nonce inséré ; audit `EVIDENCE_ADDED` écrit (même transaction) | Aucun |
| En-tête requis manquant/vide | un de `X-Escrow-Key-Id/Signature/Timestamp/Nonce` absent | Rejet avant tout traitement | `400` `BadRequestException` |
| Key-id inconnu ou inactif | `findByKeyIdAndActiveTrue` vide | Rejet auth | `401` `UnauthorizedException` |
| Signature invalide | HMAC recalculé ≠ `X-Escrow-Signature` (corps ou métadonnée altéré) | Rejet auth | `401` `UnauthorizedException` |
| Timestamp hors fenêtre | `abs(now - ts) > tolérance` | Rejet anti-rejeu | `401` `UnauthorizedException` |
| Rejeu de nonce | `(key_id, nonce)` déjà vu (probe) ou course perdue à l'INSERT | Rejet anti-rejeu | `401` `UnauthorizedException` |
| Société non partie | société de la clé ≠ acheteur ni vendeur de `{id}` | Rejet appartenance | `403` `ForbiddenException` |
| Transaction inexistante | `{id}` inconnu | Rejet | `404` `NotFoundException` |
| État terminal | état sans `allowsEvidenceMutation` | Dépôt verrouillé | `409` `ConflictException` |
| Fichier invalide/trop gros/vide | type réel refusé, `> 10 Mo`, ou vide | Lot entier rejeté (tout-ou-rien), nonce **non** consommé | `400` `BadRequestException` |

</intent-contract>

## Code Map

- `backend/src/main/java/com/zlecaf/escrow/web/PartnerEvidenceController.java` -- NOUVEAU. Endpoint machine multipart, lit les 4 en-têtes, `201` + `List<EvidenceDto>`.
- `backend/src/main/java/com/zlecaf/escrow/service/PartnerEvidenceService.java` -- NOUVEAU. Orchestration `@Transactional` : résolution clé → vérif signature/timestamp/nonce → délégation ingestion → insertion nonce.
- `backend/src/main/java/com/zlecaf/escrow/service/PartnerSignatureVerifier.java` -- NOUVEAU. `@Component` : construction de la chaîne canonique (statique, testable), `HmacSigner.verify`, garde timestamp. Lève `UnauthorizedException`.
- `backend/src/main/java/com/zlecaf/escrow/service/EvidenceService.java` -- MODIF. Extraire le cœur `ingest(EscrowTransaction, files, comment, clientCapturedAt, Attribution)` partagé ; ajouter `depositAsPartner(Long companyId, Long txId, files, comment, clientCapturedAt)` (charge tx `FOR UPDATE`, appelle `TransactionAccess`, garde de fenêtre, ingère avec attribution `CARRIER_PARTNER`). `deposit(actor,…)` inchangé en comportement (bascule sur le cœur partagé, attribution humaine).
- `backend/src/main/java/com/zlecaf/escrow/service/TransactionAccess.java` -- MODIF. Ajouter `requireCompanyParticipant(EscrowTransaction tx, Long companyId)` (injecte `UserRepository`, résout société acheteur/vendeur, `ForbiddenException` sinon). Autorité d'appartenance centralisée.
- `backend/src/main/java/com/zlecaf/escrow/web/ApiExceptions.java` -- MODIF. Ajouter `UnauthorizedException` (401).
- `backend/src/main/java/com/zlecaf/escrow/web/GlobalExceptionHandler.java` -- MODIF. Handler `UnauthorizedException` → `401`.
- `backend/src/main/java/com/zlecaf/escrow/config/SecurityConfig.java` -- MODIF. `.requestMatchers("/api/v1/partner/**").permitAll()` avant `anyRequest().authenticated()`.
- `backend/src/main/resources/application.yml` -- MODIF. `escrow.partner.timestamp-tolerance-seconds: 300`.
- `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` + `domain/UploaderType.java` + `db/migration/V3__evidence_files_constraints.sql` -- RÉFÉRENCE. Attribution `CARRIER_PARTNER` et `CHECK`.
- `backend/src/main/java/com/zlecaf/escrow/service/HmacSigner.java` / `repository/PartnerHmacKeyRepository.java` / `repository/PartnerKeyNonceRepository.java` / `domain/EscrowTransaction.java` / `domain/User.java` -- RÉFÉRENCE. Primitive HMAC, magasin clés/nonces, modèle d'appartenance.
- `backend/src/test/java/com/zlecaf/escrow/web/EscrowControllerDisputeTest.java` -- RÉFÉRENCE. Patron MockMvc `standaloneSetup` + `GlobalExceptionHandler` + multipart.

## Tasks & Acceptance

**Execution:**
- [x] `service/PartnerSignatureVerifier.java` -- Construire la chaîne canonique (ordre figé ci-dessus), calculer `contentDigest` (SHA-256 hex par fichier, joints `,`), vérifier signature (`HmacSigner.verify`) et fenêtre timestamp ; `UnauthorizedException` sur échec. -- Auth par signature isolée et testable.
- [x] `service/EvidenceService.java` -- Extraire le cœur `ingest(...)` (Pass1 validation lot + Pass2 store/persist/audit/rollback) paramétré par une `Attribution` (uploaderType, uploadedByUserId, partnerCompanyId, auditActorId nullable, auditRole nullable) ; `deposit(actor,…)` construit l'attribution humaine, `depositAsPartner(companyId, txId,…)` charge tx `FOR UPDATE`, appelle `TransactionAccess.requireCompanyParticipant`, garde de fenêtre, ingère l'attribution `CARRIER_PARTNER` (`partnerCompanyId=companyId`, `uploadedByUserId=null`, audit actor null). -- Zéro règle d'ingestion dupliquée.
- [x] `service/TransactionAccess.java` -- `requireCompanyParticipant(tx, companyId)` : charger acheteur/vendeur via `UserRepository`, comparer `user.getCompany().getId()` (null-safe) à `companyId`, `ForbiddenException` si aucune correspondance. -- Anti-IDOR au niveau société.
- [x] `service/PartnerEvidenceService.java` -- `@Transactional deposit(keyId, signature, timestamp, nonce, txId, files, comment, clientCapturedAt)` : résoudre clé active (`401` sinon) → `PartnerSignatureVerifier.verify` → probe nonce (`401` si vu) → `evidenceService.depositAsPartner(...)` → `partnerKeyNonceRepository.save(new PartnerKeyNonce(keyId, nonce))` (catch `DataIntegrityViolationException` → `401`). -- Orchestration atomique auth+ingestion+nonce.
- [x] `web/PartnerEvidenceController.java` -- `POST /api/v1/partner/escrow/{id}/evidence` : `@RequestHeader` des 4 en-têtes (requis, `400` si absent), `@RequestParam("files")`/`comment`/`clientCapturedAt`, appeler le service, `201` + `List<EvidenceDto>` via `EvidenceDto.from`. -- Frontière machine.
- [x] `web/ApiExceptions.java` + `web/GlobalExceptionHandler.java` -- Ajouter `UnauthorizedException` + handler → `401` dans l'enveloppe existante. -- Auth échouée dans le contrat d'erreur.
- [x] `config/SecurityConfig.java` + `application.yml` -- Whitelister `/api/v1/partner/**` (`permitAll`) ; ajouter la propriété de tolérance. -- Route non-JWT + config fenêtre.
- [x] `test/.../service/PartnerSignatureVerifierTest.java` -- Unitaire : signature valide acceptée ; corps altéré / métadonnée altérée / timestamp hors fenêtre → `UnauthorizedException` ; chaîne canonique déterministe (mono & multi-fichiers). -- Preuve de l'auth signature.
- [x] `test/.../service/PartnerEvidenceServiceTest.java` -- Unitaire (mocks `EvidenceService`/repos) : chemin heureux (nonce sauvé, `depositAsPartner` appelé avec `companyId`) ; nonce déjà vu → `401` sans dépôt ; course INSERT (`DataIntegrityViolationException`) → `401`. -- Orchestration & anti-rejeu.
- [x] `test/.../web/PartnerEvidenceControllerTest.java` -- MockMvc `standaloneSetup` + `GlobalExceptionHandler` : `201` sérialisant `EvidenceDto` ; en-tête manquant → `400` ; service `thenThrow` `Unauthorized`→`401`, `Forbidden`→`403`. -- Contrat HTTP & enveloppe d'erreur.

**Acceptance Criteria:**
- Given une clé HMAC entrante active pour une société partie à la transaction `{id}` (état non terminal), when un `POST /api/v1/partner/escrow/{id}/evidence` arrive avec signature valide, timestamp dans ±5 min et nonce neuf, then la pièce est attachée avec `uploader_type=CARRIER_PARTNER`, `partner_company_id` renseigné et `uploaded_by_user_id=null`, visible de toutes les parties, le nonce est enregistré et un audit `EVIDENCE_ADDED` (`evidenceId`, `sha256`) est écrit dans la même transaction ; réponse `201`.
- Given un timestamp hors ±5 min, un nonce déjà vu pour ce `key-id`, une signature invalide ou un `key-id` inconnu/inactif, when l'appel arrive, then il est rejeté `401` dans l'enveloppe JSON de la plateforme, sans dépôt ni consommation de nonce (hors le rejeu réel du même nonce).
- Given une transaction où la société de la clé n'est ni acheteur ni vendeur, when le partenaire dépose (signature par ailleurs valide), then il reçoit `403`.
- Given la route `/api/v1/partner/**` en `permitAll`, when un appel sans JWT est reçu, then il n'est pas rejeté par la sécurité (auth portée par la seule signature) ; aucun autre endpoint n'est ouvert.
- Given la suite complète, when `mvn test` s'exécute, then tous les tests passent (nouveaux + aucune régression sur le dépôt utilisateur d'Epic 1).

## Spec Change Log

## Review Triage Log

### 2026-07-17 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 0, medium 3, low 1)
- defer: 1: (high 0, medium 0, low 1)
- reject: 7
- addressed_findings:
  - `[medium]` `[patch]` **Amplification pré-auth** : `PartnerSignatureVerifier` lisait et hachait (SHA-256) tous les fichiers *avant* le plafond `MAX_FILES_PER_DEPOSIT` (situé dans `ingest`), joignable avec un `key-id` public et une signature bidon. Extrait `EvidenceService.requireDepositableBatch(files)` (public static) et appelé dans `PartnerEvidenceService.deposit` **avant** `verify`, plus en tête de `deposit`/`depositAsPartner`. Test `tooManyFilesRejectedBeforeSignatureCheck` (21 fichiers → 400, `verify` jamais appelé).
  - `[medium]` `[patch]` **Oracle d'énumération de clés** : messages 401 distincts (« Unknown or inactive key » vs « Invalid signature » vs timestamp/nonce) permettaient de distinguer clé absente/révoquée d'une clé active. Unifiés en un unique `AUTH_FAILED = "Invalid partner credentials"` dans `PartnerEvidenceService` et `PartnerSignatureVerifier`.
  - `[medium]` `[patch]` **Couverture manquante** des branches `404` (transaction inconnue) et `409` (état terminal) de l'endpoint (lignes I/O Matrix). Ajout de `notFoundMapsTo404` + `conflictMapsTo409` dans `PartnerEvidenceControllerTest`. (Risque « 500 sur audit null / CHECK CARRIER_PARTNER » écarté : `audit_logs.action_by` est nullable et le `CHECK ck_evidence_attribution` V3 autorise explicitement l'attribution partenaire.)
  - `[low]` `[patch]` **Précédence d'erreur régressée sur le chemin utilisateur** : le refactor déplaçait la garde vide/plafond *après* verrou+appartenance ; la spec promet `deposit(actor,…)` « inchangé ». Garde ré-appelée en tête de `deposit` avant le `findByIdForUpdate`.

### 2026-07-17 — Review pass (follow-up)
- intent_gap: 0
- bad_spec: 0
- patch: 2: (high 0, medium 1, low 1)
- defer: 6: (high 0, medium 2, low 4)
- reject: 7
- addressed_findings:
  - `[medium]` `[patch]` **Garde anti-IDOR sans test direct** : `TransactionAccess.requireCompanyParticipant` (seule autorité d'appartenance au périmètre société, traversée par chaque dépôt partenaire) n'était exercée qu'indirectement via un `EvidenceService` mocké. Ajout de `TransactionAccessTest` (6 cas : société acheteur → autorisé, société vendeur → autorisé, société tierce → 403, `companyId` null → 403, user partie sans société → 403 fail-closed, user partie non résolu → 403 fail-closed). Aucun code de production touché.
  - `[low]` `[patch]` **Bornes exactes de la fenêtre timestamp non testées** : `PartnerSignatureVerifier` n'exerçait que « maintenant » et « très périmé » ; un passage `>`→`>=` ou une perte du `abs` passait CI. Ajout de `timestampAtWindowBoundaryAccepted` (now±tolérance acceptés) et `timestampJustOutsideWindowRejected` (now±(tolérance+1) → 401) — verrou de régression sur le `abs(diff) > tolerance` symétrique.
  - Différés (6, ledger) : `permitAll` sur sous-arbre `/api/v1/partner/**` (durcissement config, risque futur) ; catch-all `DataIntegrityViolationException` + absence de garde de longueur d'en-tête (robustesse, fail-safe) ; absence de test d'intégration vraie-base de la ligne `CARRIER_PARTNER` contre `ck_evidence_attribution` (medium) ; absence de test de concurrence réel de l'arbitrage de nonce (medium) ; double chargement d'agrégat `User` dans le garde d'appartenance (perf) ; message `AUTH_FAILED` dupliqué en deux classes (maintenabilité de la propriété anti-oracle).
  - Rejetés (7) : règle d'appartenance acheteur/vendeur (contredit le contrat d'intention explicite — la société partenaire du POC est l'une des sociétés parties agissant via machine) ; nom de fichier non signé (chaîne canonique figée par le contrat, exclut délibérément le nom) ; comparaison hex sensible à la casse (hex minuscule mandaté par le contrat) ; injectivité de la chaîne canonique / newline dans comment (format contractuel, aucun chemin de forge inter-clés — le partenaire signe avec sa propre clé) ; amplification DoS pré-auth « survendue » (compromis POC accepté, plafond de 20 fichiers déjà en place) ; double lecture/mémoire du lot (compromis POC documenté) ; code I/O 401 vs 400 sur échec de lecture transitoire (conséquence négligeable).

## Design Notes

- **Signature sur digest, pas sur octets bruts multipart.** Signer le corps multipart brut est fragile (frontières, ordre des parties, buffering Spring). La chaîne canonique inclut `contentDigest` = SHA-256 par fichier : elle couvre intégralement le contenu (« corps ») + les métadonnées + l'horodatage, reste déterministe et reconstructible côté serveur après parsing multipart. Le `sha256Hex(bytes)` par fichier est **déjà** calculé par le cœur d'ingestion pour l'audit — le vérificateur le recalcule (double lecture `MultipartFile.getBytes()`, sûre et acceptable pour un POC ≤ 10 Mo ; optimisation = defer).
- **Extraction du cœur d'ingestion, pas de duplication.** `EvidenceService.deposit(actor,…)` et `depositAsPartner(companyId,…)` ne diffèrent que par (1) l'autorisation (rôle utilisateur vs société partie) et (2) l'attribution. Le reste (Pass1 tout-ou-rien, Pass2 store/persist/audit, nettoyage rollback, garde de fenêtre `allowsEvidenceMutation`) est un cœur partagé paramétré par une `Attribution`. `toUploaderType` (BUYER/SELLER/ADMIN) reste inchangé : le chemin partenaire pose `CARRIER_PARTNER` directement.
- **Atomicité auth+dépôt+nonce.** Tout est dans une seule transaction : si l'ingestion échoue (fichier invalide, `403`, `409`), le nonce n'est pas consommé (retry légitime avec le même nonce possible). Le nonce n'est inséré qu'après une ingestion réussie ; la contrainte `uq_partner_key_nonces_key_nonce` protège contre deux dépôts concurrents du même `(key_id, nonce)` (course → `401`).
- **401 vs 403.** Échec d'authentification (clé, signature, timestamp, rejeu) → `401` (nouvel `UnauthorizedException`, la plateforme n'en avait pas) ; échec d'autorisation (société non partie) → `403` (`ForbiddenException` existant). Les deux dans l'enveloppe `GlobalExceptionHandler`.
- **Audit partenaire.** `recordEvidenceAdded` accepte `actorId`/`role` nullables : le dépôt partenaire passe `actorId=null` et `role=null` ; l'attribution société est portée par la ligne `evidence_files` (`partner_company_id`). L'entrée `EVIDENCE_ADDED` conserve `evidenceId` + `sha256`.

## Verification

**Commands:**
- `cd backend && mvn -q -Dtest=PartnerSignatureVerifierTest,PartnerEvidenceServiceTest,PartnerEvidenceControllerTest test` -- attendu : vert, tous les cas de l'I/O Matrix couverts.
- `cd backend && mvn -q test` -- attendu : suite complète verte, aucune régression sur le dépôt utilisateur.
- `cd backend && mvn -q compile` -- attendu : compilation sans erreur.

## Auto Run Result

Status: done

**Changement implémenté :** endpoint machine partenaire signé de l'Epic 3 — `POST /api/v1/partner/escrow/{id}/evidence` (route `permitAll`, auth 100 % par signature HMAC-SHA256). Un `PartnerEvidenceService` `@Transactional` orchestre : résolution `key-id`→clé active, vérification de signature (`HmacSigner.verify`, temps constant) sur une chaîne canonique figée (keyId·txId·timestamp·nonce·digest-SHA256-par-fichier·comment·clientCapturedAt), garde timestamp ±5 min, anti-rejeu par nonce scindé `key-id` (sonde + contrainte unique arbitre de course), contrôle « société de la clé partie à la transaction » (`TransactionAccess.requireCompanyParticipant`, anti-IDOR centralisé). L'ingestion **réutilise** le cœur extrait d'`EvidenceService` (validation Tika, borne 10 Mo, clé de stockage opaque, nettoyage rollback, audit `EVIDENCE_ADDED` MANDATORY) via `depositAsPartner`, attribution `CARRIER_PARTNER`/`partner_company_id`/`user_id=null`. Aucune règle d'ingestion dupliquée.

**Fichiers modifiés :**
- `web/PartnerEvidenceController.java` — NOUVEAU. Endpoint multipart, 4 en-têtes `X-Escrow-*`, `201` + `List<EvidenceDto>`.
- `service/PartnerEvidenceService.java` — NOUVEAU. Orchestration atomique auth+ingestion+nonce ; plafond de lot avant hachage ; 401 générique unifié.
- `service/PartnerSignatureVerifier.java` — NOUVEAU. Chaîne canonique (statique testable) + `HmacSigner.verify` + fenêtre timestamp ; 401 générique.
- `service/EvidenceService.java` — MODIF. Cœur `ingest(...)` partagé paramétré par `Attribution` ; `depositAsPartner(...)` ; garde `requireDepositableBatch` (public static) appelée tôt sur les deux chemins.
- `service/TransactionAccess.java` — MODIF. `requireCompanyParticipant(tx, companyId)` (injecte `UserRepository`).
- `domain/PartnerKeyNonce.java` — MODIF. Constructeurs no-arg + `(keyId, nonce)`.
- `web/ApiExceptions.java` + `web/GlobalExceptionHandler.java` — MODIF. `UnauthorizedException` → 401 ; `MissingRequestHeaderException` → 400 ; tout dans l'enveloppe.
- `config/SecurityConfig.java` — MODIF. `/api/v1/partner/**` `permitAll`.
- `application.yml` — MODIF. `escrow.partner.timestamp-tolerance-seconds: 300`.
- 3 classes de test NEUVES : `PartnerSignatureVerifierTest` (7), `PartnerEvidenceServiceTest` (5), `PartnerEvidenceControllerTest` (6).

**Revue (1 passe, Blind Hunter + Edge Case Hunter) :** 4 patches appliqués — (medium) plafond de lot avant hachage pré-auth (anti-amplification), (medium) unification des messages 401 (anti-oracle d'énumération de clés), (medium) tests 404/409 manquants ajoutés, (low) précédence d'erreur du chemin utilisateur restaurée. 1 différé (ledger) : croissance non bornée de `partner_key_nonces` (purge non planifiée, sanctionnée par la spec). 7 rejets : nonce non consommé en cas d'échec (conception documentée, nécessite MITM sous TLS), contrôle société fail-closed (sûr), verrou tenu pendant le stockage (identique au chemin utilisateur existant), énumération 404/403 d'existence de transaction (comportement plateforme préexistant), incohérence de message d'en-tête (cosmétique), `permitAll` de namespace (idiome identique aux matchers webhook/auth existants, spécifié), fenêtre de skew symétrique (tolérance d'horloge intentionnelle).

**Vérification :** `mvn -Dtest=Partner*Test,EvidenceServiceTest,EscrowDisputeServiceTest test` → 76/76 verts (dont 42 EvidenceServiceTest + 16 EscrowDisputeServiceTest, aucune régression du chemin utilisateur après extraction du cœur). Suite complète `mvn test` → `Tests run: 147, Failures: 0, Errors: 0` — BUILD SUCCESS.

**Risques résiduels :** faibles. Double lecture des octets multipart (vérif signature + ingestion) acceptée pour un POC ≤ 10 Mo. Croissance non bornée des nonces différée (ledger). Génération à haute entropie et rotation/GC des clés = outillage d'admin hors périmètre.

**Follow-up review recommandé :** true — les correctifs incluent deux durcissements sécurité (amplification pré-auth, oracle d'énumération) et un refactor de la précédence de contrôle sur un service partagé (`EvidenceService`) ; une passe indépendante fraîche est justifiée malgré la suite complète verte.

---

### Passe de suivi — 2026-07-17

Passe de review indépendante (Blind Hunter + Edge Case Hunter, sans contexte préalable) déclenchée par `followup_review_recommended: true` de la passe initiale. Diff reconstruit depuis `baseline_revision` (7bd9d15).

**Triage :** 0 intent_gap, 0 bad_spec, 2 patches, 6 différés (ledger), 7 rejets. Aucun `bad_spec`/`intent_gap` — le contrat d'intention est explicite et le code le suit fidèlement ; les findings « high » des reviewers (règle acheteur/vendeur, nom de fichier non signé, hex minuscule) contredisent des décisions explicites et immuables du contrat → rejetés.

**Patches appliqués (tests seuls, zéro code de production) :**
- `test/service/TransactionAccessTest.java` — NOUVEAU (6 tests). Preuve directe du garde anti-IDOR `requireCompanyParticipant`, jusqu'ici testé seulement via un `EvidenceService` mocké : société acheteur/vendeur → autorisé, société tierce/`companyId` null/user sans société/user non résolu → 403 (fail-closed).
- `test/service/PartnerSignatureVerifierTest.java` — MODIF (+2 tests). Bornes exactes de la fenêtre timestamp : now±tolérance acceptés, now±(tolérance+1) → 401. Verrou de régression sur le `abs(diff) > tolerance` symétrique.

**Vérification :** `mvn test` → `Tests run: 155, Failures: 0, Errors: 0` — BUILD SUCCESS (147 → +8 : 6 `TransactionAccessTest`, +2 `PartnerSignatureVerifierTest`). Aucune régression.

**Différés (6, tous consignés au ledger comme nouvelles entrées) :** matcher `permitAll` sur sous-arbre `/api/v1/partner/**` (durcissement config) ; catch-all `DataIntegrityViolationException` + garde de longueur d'en-tête absente (robustesse, fail-safe) ; absence de test d'intégration vraie-base `CARRIER_PARTNER` vs `ck_evidence_attribution` (medium) ; absence de test de concurrence réel de l'arbitrage de nonce (medium) ; double chargement `User` dans le garde d'appartenance (perf) ; message `AUTH_FAILED` dupliqué (maintenabilité).

**Follow-up review recommandé (mis à jour) :** false — cette passe n'a ajouté que de la couverture de test verrouillant du comportement déjà livré ; aucun changement de code de production, d'API, de sécurité ou de données ne justifie une passe indépendante supplémentaire.
