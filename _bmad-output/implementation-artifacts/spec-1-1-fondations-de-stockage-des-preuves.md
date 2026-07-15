---
title: 'Story 1.1 — Fondations de stockage des preuves'
type: 'feature'
created: '2026-07-16'
status: 'done'
baseline_revision: 'f27bf505ff93553fbcbfd2712dfffb32d0bf9cce'
final_revision: '1ca0469'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** Aucune preuve ne peut être persistée : il n'existe ni table `evidence_files`, ni abstraction de stockage binaire, ni backend objet. Les stories 1.2 à 1.5 et les epics 2-4 sont tous bloqués sur ce socle.

**Approach:** Livrer les fondations et rien d'autre : migration Flyway `V2`, entité/repository JPA, un port `EvidenceStorage` isolant le binaire derrière une clé opaque, un adaptateur MinIO (AWS SDK v2), le service MinIO dans docker-compose, et un test de round-trip Testcontainers prouvant l'identité octet-pour-octet.

## Boundaries & Constraints

**Always:**
- La clé de stockage est **générée** au format `{transactionId}/{uuid}`, **jamais** dérivée du nom de fichier fourni (anti-path-traversal).
- Aucun type MinIO/S3/AWS ne fuit hors de `service/storage/` — le port ne parle que `byte[]`, `String`, `InputStream`. Direction des dépendances : `web → service → {repository, port}`.
- Le schéma est détenu par Flyway (`ddl-auto: none`). Idiomes de `V1__init.sql` : `BIGSERIAL PRIMARY KEY`, `TIMESTAMPTZ NOT NULL DEFAULT now()`, FK inline `BIGINT REFERENCES table (id)` sans contrainte nommée, index `idx_<entité>_<col>`.
- Conventions du code existant, à ratifier sans inventer : **pas de Lombok** (getters/setters écrits à la main), **pas de `@ManyToOne`** (les FK sont des scalaires `Long`), injection par constructeur sans `@Autowired`, `@Value` sur paramètre de constructeur, `Instant` + `@PrePersist`, enums `@Enumerated(EnumType.STRING)`.
- Aucun secret en dur : endpoint/credentials/bucket injectés en `${ENV:default}`.
- Rétention illimitée : aucune purge, aucun TTL, aucune règle de cycle de vie sur le bucket ni en base.

**Block If:**
- Le round-trip octet-pour-octet ne peut pas être prouvé contre un vrai MinIO (Docker indisponible, module Testcontainers `minio` inopérant) → HALT `blocked`, la preuve observable est non négociable.
- Faire passer le round-trip exigerait de modifier une migration existante (`V1__init.sql`) ou de casser un test existant.

**Never:**
- Aucun endpoint REST, aucun contrôleur, aucun DTO, aucun `EvidenceService`.
- Aucune validation de contenu (Tika/content-sniffing), aucun contrôle de taille, aucune écriture d'audit, aucun contrôle d'appartenance — tout cela arrive en Story 1.2.
- Aucun scan antivirus (risque accepté pour le POC). Aucune suppression physique.
- Ne pas mocker le round-trip. Ne pas toucher à `web/`, ni à la machine à états.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Round-trip nominal | `store(42L, bytes, "image/jpeg")` sur MinIO éphémère, puis `load(clé)` | Le binaire relu est **identique octet pour octet** à l'original | Aucune erreur attendue |
| Format de clé | `store(42L, bytes, "application/pdf")` | La clé retournée matche `^42/[0-9a-f-]{36}$` | Aucune erreur attendue |
| Unicité des clés | Deux `store` successifs, même transaction, même contenu | Deux clés **distinctes**, les deux objets relisibles | Aucune erreur attendue |
| Opacité de la clé | `store` avec un `originalFilename` hostile (`../../etc/passwd`) — non passé au port | Le port n'accepte pas de nom de fichier : la signature l'interdit structurellement | N/A (garanti par le type) |
| Clé inconnue | `load("42/inexistant")` | Propagation de l'exception S3 (`NoSuchKeyException`) | Non gérée ici — mappée en 404 par la Story 1.2 |
| `contentType` conservé | `store(42L, bytes, "application/pdf")` | Le `Content-Type` est stocké sur l'objet MinIO | Aucune erreur attendue |

</intent-contract>

## Code Map

- `backend/pom.xml` -- MODIFIER : pas de `<dependencyManagement>` aujourd'hui ; ajouter le BOM AWS SDK. Testcontainers est déjà géré par le parent Boot 3.3.5 (BOM 1.19.8).
- `backend/src/main/resources/db/migration/V1__init.sql` -- LIRE : idiomes de migration à imiter. `V2` est libre.
- `backend/src/main/resources/application.yml` -- MODIFIER : namespace custom existant = `escrow.*` (`escrow.jwt.*`), `ddl-auto: none`.
- `backend/src/main/java/com/zlecaf/escrow/domain/AuditLog.java` -- LIRE : conventions d'entité (FK scalaire, `@PrePersist`, pas de Lombok).
- `backend/src/main/java/com/zlecaf/escrow/repository/AuditLogRepository.java` -- LIRE : repository minimal, sans `@Repository`.
- `backend/src/main/java/com/zlecaf/escrow/config/AsyncConfig.java` -- LIRE : forme d'une `@Configuration` + `@Bean`.
- `backend/src/main/java/com/zlecaf/escrow/security/JwtService.java` -- LIRE : seul précédent de `@Value` sur paramètre de constructeur.
- `backend/src/test/java/com/zlecaf/escrow/service/EscrowStateMachineTest.java` -- LIRE : conventions de test (classe package-private, `@DisplayName`, AssertJ, aucun contexte Spring).
- `infra/docker-compose.yml` -- MODIFIER : style healthcheck + `depends_on: condition: service_healthy` + volumes nommés. Ports 9000/9001 libres.

## Tasks & Acceptance

**Execution:**
- [x] `backend/pom.xml` -- Créer un `<dependencyManagement>` important `software.amazon.awssdk:bom:2.47.6` (pom/import) ; ajouter `software.amazon.awssdk:s3` (sans version) ; ajouter `org.testcontainers:junit-jupiter` et `org.testcontainers:minio` en scope `test` **sans version** (gérés par le parent Boot) -- socle des dépendances.
- [x] `backend/src/main/resources/db/migration/V2__evidence_files.sql` -- Créer la table `evidence_files` (colonnes ci-dessous) + `CREATE INDEX idx_evidence_transaction ON evidence_files (transaction_id, created_at);` -- schéma détenu par Flyway.
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/UploaderType.java` + `EvidenceStatus.java` -- Créer les enums `{BUYER, SELLER, ADMIN, CARRIER_PARTNER}` et `{ACTIVE, WITHDRAWN}` -- `WITHDRAWN` est modélisé dès maintenant mais écrit seulement en Epic 2.
- [x] `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` -- Créer l'entité mappant la table, FK en `Long` scalaires, `Instant createdAt` via `@PrePersist` -- calquée sur `AuditLog`.
- [x] `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` -- Créer `extends JpaRepository<EvidenceFile, Long>` (aucune méthode custom : la liste chronologique est Story 1.3) -- accès données.
- [x] `backend/src/main/java/com/zlecaf/escrow/service/storage/EvidenceStorage.java` -- Créer le port : `String store(Long transactionId, byte[] content, String contentType)` et `InputStream load(String storageKey)`, javadoc sur l'opacité de la clé -- isole le binaire du reste du code.
- [x] `backend/src/main/java/com/zlecaf/escrow/config/StorageConfig.java` -- Créer le `@Bean S3Client` pointé sur MinIO : `endpointOverride`, `forcePathStyle(true)`, `StaticCredentialsProvider`, `Region.US_EAST_1` -- `forcePathStyle` obligatoire depuis le SDK 2.18.
- [x] `backend/src/main/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorage.java` -- Créer l'adaptateur `@Component` : `putObject` avec clé `{transactionId}/{UUID.randomUUID()}` + `contentType`, `getObject` → `InputStream` -- seul point du code qui connaît S3.
- [x] `backend/src/main/resources/application.yml` -- Ajouter le bloc `escrow.storage.*` (endpoint, bucket, access-key, secret-key) en `${ESCROW_STORAGE_*:défaut}` -- aligné sur `escrow.jwt.*`.
- [x] `infra/docker-compose.yml` -- Ajouter le service `minio` (healthcheck `mc ready local`, volume `miniodata`), le one-shot `minio-init` (`minio/mc`) créant le bucket `escrow-evidence`, le volume nommé, et câbler `backend.environment` + `depends_on: minio (service_healthy)` -- stack locale complète.
- [x] `backend/src/test/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorageTest.java` -- Créer le test Testcontainers couvrant **chaque ligne de la matrice I/O** : round-trip octet-pour-octet, format de clé, unicité, `contentType`, clé inconnue -- preuve observable contre un vrai MinIO.

**Acceptance Criteria:**
- Given la base migrée par Flyway, when on inspecte le schéma, then la table `evidence_files` existe avec toutes les colonnes de l'ERD, l'index `(transaction_id, created_at)`, et `V1__init.sql` est inchangé.
- Given l'application démarrée, when le contexte Spring se charge, then `MinioEvidenceStorage` est injectable en tant que `EvidenceStorage` et aucune classe hors de `service/storage/` + `config/StorageConfig` n'importe `software.amazon.awssdk`.
- Given `docker compose -f infra/docker-compose.yml up`, when la stack converge, then `minio` est `healthy`, le bucket `escrow-evidence` existe, et `backend` devient `healthy`.
- Given le code livré, when on exécute `mvn test`, then le test de round-trip passe **et** `EscrowStateMachineTest` / `HmacSignerTest` restent verts.
- Given le périmètre de la story, when on relit le diff, then il ne contient aucun contrôleur, aucune validation Tika, aucune écriture d'audit.

### Colonnes de `evidence_files`

`id BIGSERIAL PRIMARY KEY` · `transaction_id BIGINT NOT NULL REFERENCES escrow_transactions (id)` · `uploaded_by_user_id BIGINT REFERENCES users (id)` (nullable) · `uploader_type VARCHAR(20) NOT NULL` · `partner_company_id BIGINT REFERENCES companies (id)` (nullable) · `original_filename VARCHAR(255)` · `mime_type VARCHAR(100)` · `size_bytes BIGINT` · `storage_key VARCHAR(500) NOT NULL` · `comment TEXT` · `status VARCHAR(20) NOT NULL` · `created_at TIMESTAMPTZ NOT NULL DEFAULT now()` · `withdrawn_at TIMESTAMPTZ` (nullable) · `withdrawn_by_user_id BIGINT REFERENCES users (id)` (nullable)

## Spec Change Log

## Review Triage Log

### 2026-07-16 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 5: (high 0, medium 2, low 3)
- defer: 2: (high 0, medium 2, low 0)
- reject: 14: (high 0, medium 3, low 11)
- addressed_findings:
  - `[medium]` `[patch]` Le port fuyait `NoSuchKeyException` (type AWS) hors de `service/storage/` : la Story 1.2 aurait dû importer le SDK pour mapper un 404, en violation directe de l'invariant « aucun type S3 ne franchit la frontière » (AD-6). Ajout de `EvidenceNotFoundException` (storage-neutre) ; `load` traduit désormais l'exception S3. Comportement du contrat I/O préservé : clé inconnue → exception non gérée ici, mappée en 404 par 1.2 ; seul le type concret change.
  - `[medium]` `[patch]` `infra/docker-compose.yml` : `backend` ne dépendait que de `minio: service_healthy`, pas de la fin de `minio-init`. Sur volume neuf, le backend pouvait démarrer avant la création du bucket → `NoSuchBucket` au premier upload. Remplacé par `minio-init: condition: service_completed_successfully`. Vérifié à froid : `minio-init` exit 0 à 23:23:14, backend démarré à 23:23:19.
  - `[low]` `[patch]` `MinioEvidenceStorage.store` : un `transactionId` null produisait silencieusement une clé `null/<uuid>` (objet inattribuable). Ajout de `Objects.requireNonNull` sur `transactionId` et `content` + test dédié.
  - `[low]` `[patch]` `backend/pom.xml` : deux commentaires affirmaient que Testcontainers venait du parent Boot alors que la version est justement surchargée en 1.21.4 — un mainteneur suivant le commentaire aurait retiré la surcharge et réintroduit l'incompatibilité API Docker. Commentaires corrigés et renvoi explicite à la note des `<properties>`.
  - `[low]` `[patch]` `MinioEvidenceStorageTest` : `randomBytes()` n'était ni aléatoire ni variable (`new Random(42)` re-seedé à chaque appel). Renommé `fixedBytes()` + javadoc expliquant que le déterminisme est voulu et dont dépend `keysAreUniquePerStore`.

<!-- Rejets notables : les trois « déviations de spec » signalées par le relecteur adverse (`escrow.storage.*` au lieu d'`app.evidence.*`, tag MinIO `RELEASE.2025-09-07` au lieu de `2025-10-15`, `store(Long transactionId, …)`) sont des décisions délibérées, vérifiées et consignées dans les Design Notes de cette spec. Les relecteurs opèrent sans ce contexte par construction. Également rejetés : `assumeTrue(dockerAvailable)` sur le test (la spec exige une preuve réelle non contournable — la rendre skippable la viderait de son sens) ; défaut `status = ACTIVE` sur l'entité (aucun chemin d'écriture dans cette story ; 1.2 possède la sémantique de création) ; fail-fast sur credentials par défaut (idiome POC pré-existant, cf. `escrow.jwt.secret`). -->

## Design Notes

**Trois écarts assumés vis-à-vis des artefacts de planification, tous fondés sur une vérification factuelle :**

1. **Signature de `store` — contradiction résolue.** La story et l'architecture décrivent `store(byte[], String) → clé` *tout en* imposant une clé `{transaction_id}/{uuid}`. Les deux sont incompatibles : le port ne peut pas fabriquer ce préfixe sans connaître la transaction. Le format de clé est affirmé deux fois (ERD + critère de test), la liste de paramètres une seule → on ajoute `Long transactionId` en premier paramètre. La clé reste opaque pour l'appelant.
2. **Image MinIO — le tag figé n'existe pas.** `minio/minio:RELEASE.2025-10-15T17-29-55Z` renvoie 404 sur Docker Hub (vérifié : `docker manifest inspect` + API Hub). La dernière release publiée est **`RELEASE.2025-09-07T16-13-09Z`** (= `latest`, 2025-09-07), cohérent avec l'archivage du dépôt OSS. On épingle cette version réelle, et `minio/mc:RELEASE.2025-08-13T08-35-41Z` pour le one-shot. Vérifié : l'image contient `/usr/bin/mc`, donc le healthcheck `mc ready local` fonctionne.
3. **Namespace de config — `escrow.storage.*` et non `app.evidence.*`.** Le code n'a qu'un seul namespace custom (`escrow.*`) et la règle du projet est de ratifier les conventions existantes, pas d'en inventer. Les variables deviennent `ESCROW_STORAGE_ENDPOINT/BUCKET/ACCESS_KEY/SECRET_KEY`.

**Testcontainers : aucun BOM à ajouter.** Le parent `spring-boot-starter-parent:3.3.5` importe déjà `testcontainers-bom:1.19.8`, qui contient `MinIOContainer` (vérifié dans le jar). Seul le BOM AWS SDK (2.47.6, vérifié présent sur Maven Central) est à importer — ce sera le premier `<dependencyManagement>` du pom.

**Forme du test.** Aucun test ne boote actuellement le contexte Spring, et un `@SpringBootTest` complet tenterait de joindre RabbitMQ et Postgres. Le test construit donc l'adaptateur **directement** contre un `MinIOContainer`, sans contexte Spring — plus rapide et sans dépendance au broker. Suffixe `*Test` (et non `*IT`) car aucun failsafe n'est configuré : c'est `mvn test` qui doit l'exécuter.

```java
@Testcontainers
class MinioEvidenceStorageTest {
    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");
    // beforeAll : S3Client vers MINIO.getS3URL(), createBucket, new MinioEvidenceStorage(s3, bucket)
}
```

## Verification

**Commands:**
- `cd backend && mvn -q test` -- expected : BUILD SUCCESS ; le round-trip passe, `EscrowStateMachineTest` et `HmacSignerTest` restent verts.
- `grep -rl "software.amazon.awssdk" backend/src/main/java` -- expected : uniquement `service/storage/MinioEvidenceStorage.java` et `config/StorageConfig.java`.
- `docker compose -f infra/docker-compose.yml up -d --build` puis `docker compose -f infra/docker-compose.yml ps` -- expected : `minio` et `backend` en `healthy`, `minio-init` sorti en code 0.
- `docker compose -f infra/docker-compose.yml exec minio mc ls local/` -- expected : le bucket `escrow-evidence` est listé.
- `docker compose -f infra/docker-compose.yml exec postgres psql -U escrow -d escrow -c "\d evidence_files"` -- expected : les 14 colonnes de l'ERD + l'index `idx_evidence_transaction`.

## Auto Run Result

Status: done
Blocking condition: aucune

### Changement implémenté

Socle de stockage des preuves : la table `evidence_files` (Flyway `V2`), son entité/repository JPA, le port `EvidenceStorage` qui isole le binaire derrière une clé opaque `{transactionId}/{uuid}`, son adaptateur MinIO (AWS SDK v2), le service MinIO dans la stack locale, et un test Testcontainers prouvant le round-trip octet-pour-octet contre un vrai MinIO. Aucun endpoint, aucune validation, aucun audit : le périmètre « fondations » est tenu.

### Fichiers

**Créés**
- `backend/src/main/resources/db/migration/V2__evidence_files.sql` — table `evidence_files` + index `(transaction_id, created_at)`, aux idiomes de `V1`.
- `backend/src/main/java/com/zlecaf/escrow/domain/EvidenceFile.java` — entité (FK scalaires, `@PrePersist`, sans Lombok).
- `backend/src/main/java/com/zlecaf/escrow/domain/UploaderType.java` / `EvidenceStatus.java` — enums du domaine.
- `backend/src/main/java/com/zlecaf/escrow/repository/EvidenceFileRepository.java` — `JpaRepository`, sans méthode custom.
- `backend/src/main/java/com/zlecaf/escrow/service/storage/EvidenceStorage.java` — le port : `store(Long, byte[], String)` / `load(String)`.
- `backend/src/main/java/com/zlecaf/escrow/service/storage/EvidenceNotFoundException.java` — exception storage-neutre (issue de la revue).
- `backend/src/main/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorage.java` — adaptateur `@Component`, seul point du code qui connaît S3.
- `backend/src/main/java/com/zlecaf/escrow/config/StorageConfig.java` — `@Bean S3Client` (`forcePathStyle`, `endpointOverride`).
- `backend/src/test/java/com/zlecaf/escrow/service/storage/MinioEvidenceStorageTest.java` — 6 tests Testcontainers.

**Modifiés**
- `backend/pom.xml` — BOM AWS SDK 2.47.6 (premier `<dependencyManagement>`), `s3`, Testcontainers `junit-jupiter`+`minio`, 3 surcharges de version documentées.
- `backend/src/main/resources/application.yml` — bloc `escrow.storage.*` en `${ESCROW_STORAGE_*:défaut}`.
- `infra/docker-compose.yml` — services `minio` + `minio-init`, volume `miniodata`, env et `depends_on` du backend.

### Revue

5 correctifs appliqués (2 medium, 3 low), 2 points reportés, 14 rejetés. Détail et justification des rejets dans le *Review Triage Log* ; reports dans `deferred-work.md`. Aucun `intent_gap`, aucun `bad_spec` : zéro boucle de reprise.

### Vérification

- `mvn test` → **22 tests, 0 échec** (6 round-trip + les 16 existants intacts). Rejoué après correctifs.
- `grep -rl software.amazon.awssdk backend/src/main/java` → **2 fichiers** (`MinioEvidenceStorage`, `StorageConfig`) : la frontière du port tient.
- Démarrage **à froid** (volume `miniodata` supprimé) : `minio` healthy, `minio-init` exit 0 à 23:23:14, `backend` démarré à 23:23:19 **après** lui, bucket `escrow-evidence` présent, `backend` healthy.
- `\d evidence_files` sur Postgres réel → 14 colonnes de l'ERD, `idx_evidence_transaction`, 4 FK. `V1__init.sql` intact (diff vide).

### Risques résiduels

- **`mvn test` exige désormais Docker** : la suite était purement unitaire. C'est le prix de la preuve réelle exigée par la spec — la rendre skippable (`assumeTrue`) la viderait de son sens. À arbitrer si une CI sans Docker apparaît.
- **Surcharges de version forcées** : Testcontainers 1.21.4 (le 1.19.8 du parent Boot parle l'API Docker v1.32, refusée par Docker Engine 29+) et httpclient5 5.6.2/httpcore5 5.4.3 (l'AWS SDK 2.47.6 exige `TlsSocketStrategy`, absent du 5.3.1 imposé par Boot). Documentées dans le `pom.xml` ; à revisiter à chaque montée de Spring Boot.
- **Intégrité de la table** : au-delà des NOT NULL/FK, aucune contrainte ne tient les invariants d'attribution et de retrait (reporté — décision de niveau ERD, à trancher avant Epic 2/3).
- **MinIO OSS est archivé** : le tag pinné par l'architecture (`RELEASE.2025-10-15T17-29-55Z`) n'existe pas ; la dernière release publiée (`RELEASE.2025-09-07T16-13-09Z`) est utilisée. Le port `EvidenceStorage` est précisément ce qui rendra la bascule vers un autre backend S3 peu coûteuse.
