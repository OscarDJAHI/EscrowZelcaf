# Story 1.1: Fondations de stockage des preuves

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a développeur de la plateforme Escrow,
I want une table `evidence_files`, un port de stockage isolé (`EvidenceStorage`) et un backend objet MinIO opérationnel,
so that toute preuve puisse être persistée durablement et relue par une clé opaque, sans coupler le reste du code au stockage — socle des stories 1.2+.

> **Périmètre strict** : cette story ne livre **que** les fondations (schéma + port + adaptateur + infra + config + test de round-trip). **Aucun endpoint REST, aucune validation Tika, aucun audit** — ils arrivent en Story 1.2. Ne pas les implémenter ici.

## Acceptance Criteria

1. **Migration `V2__evidence_files.sql`** — La table `evidence_files` existe après migration Flyway, avec toutes les colonnes de l'ERD (voir Dev Notes), un index `(transaction_id, created_at)`, et respecte les idiomes de `V1` (`BIGSERIAL PRIMARY KEY`, `TIMESTAMPTZ`, `REFERENCES`, colonnes `snake_case`). `ddl-auto` reste `none` — le schéma est détenu par Flyway. *(AR-2, AD-11)*
2. **Entité + repository** — `EvidenceFile` (`@Entity @Table(name="evidence_files")`) mappe la table selon les conventions du projet (`@GeneratedValue(IDENTITY)`, `@Column(name=…)`, `@PrePersist` pour `createdAt`), avec les enums `UploaderType {BUYER, SELLER, ADMIN, CARRIER_PARTNER}` et `EvidenceStatus {ACTIVE, WITHDRAWN}`. `EvidenceFileRepository extends JpaRepository<EvidenceFile, Long>`.
3. **Port `EvidenceStorage`** — Interface exposant `String store(byte[] content, String contentType)` (retourne une clé opaque) et `InputStream load(String storageKey)`. La clé générée est `{transactionId}/{uuid}`, **jamais dérivée d'un nom de fichier fourni**. Aucun type de `web/` ou MinIO ne fuit dans la signature. *(AD-6)*
4. **Adaptateur `MinioEvidenceStorage`** — `@Component` implémentant `EvidenceStorage` via l'AWS SDK Java v2 (`software.amazon.awssdk:s3` 2.47.6) pointé sur MinIO (`endpointOverride`, `forcePathStyle(true)`, `StaticCredentialsProvider`, région factice). Bucket et credentials injectés par `${ENV:default}`.
5. **Service `minio` dans `infra/docker-compose.yml`** — Conteneur `minio/minio:RELEASE.2025-10-15T17-29-55Z` avec volume persistant, healthcheck, et provisionnement du bucket `escrow-evidence` (init container `mc` ou équivalent). Le service `backend` reçoit les variables d'environnement MinIO et `depends_on` minio sain.
6. **Test de round-trip automatisé** — Un test d'intégration (Testcontainers module `minio`) prouve que `store(bytes, contentType)` puis `load(storageKey)` restitue un binaire **identique octet pour octet**. Critère observable, pas « ça compile ». *(exigence durcie par la revue)*
7. **Rétention** — Aucune purge/TTL n'est configurée sur le bucket ni en base (rétention illimitée POC — FR-14).

## Tasks / Subtasks

- [ ] **T1 — Migration V2** (AC: 1)
  - [ ] Créer `backend/src/main/resources/db/migration/V2__evidence_files.sql`
  - [ ] Déclarer la table `evidence_files` (colonnes de l'ERD, cf. Dev Notes) sur le modèle de `V1__init.sql`
  - [ ] `CREATE INDEX idx_evidence_transaction ON evidence_files (transaction_id, created_at);`
  - [ ] FK `transaction_id → escrow_transactions(id)` NOT NULL, `uploaded_by_user_id → users(id)` nullable, `partner_company_id → companies(id)` nullable, `withdrawn_by_user_id → users(id)` nullable
- [ ] **T2 — Domaine JPA** (AC: 2)
  - [ ] `domain/EvidenceFile.java` (mêmes idiomes qu'`AuditLog`/`EscrowTransaction` : `jakarta.persistence`, `IDENTITY`, `@Column(name)`, `Instant createdAt` + `@PrePersist`)
  - [ ] `domain/UploaderType.java` et `domain/EvidenceStatus.java` (enums ; stockés en `VARCHAR` via `@Enumerated(EnumType.STRING)`)
  - [ ] `repository/EvidenceFileRepository.java`
- [ ] **T3 — Port de stockage** (AC: 3)
  - [ ] `service/storage/EvidenceStorage.java` (interface, signatures `store`/`load`, javadoc sur l'opacité de la clé)
- [ ] **T4 — Adaptateur MinIO** (AC: 4)
  - [ ] Ajouter la dépendance `software.amazon.awssdk:s3` (BOM `software.amazon.awssdk:bom` 2.47.6) au `pom.xml`
  - [ ] `config/StorageConfig.java` : `@Bean S3Client` configuré pour MinIO (voir snippet Dev Notes)
  - [ ] `service/storage/MinioEvidenceStorage.java` : `putObject` (clé `{tx}/{uuid}`), `getObject` ; le `contentType` est passé au `PutObjectRequest`
  - [ ] Propriétés `app.evidence.*` dans `application.yml` (endpoint, bucket, access/secret keys) en `${ENV:default}`
- [ ] **T5 — Infra compose** (AC: 5)
  - [ ] Ajouter le service `minio` (+ volume `miniodata`, healthcheck) à `infra/docker-compose.yml`
  - [ ] Ajouter un one-shot `minio/mc` provisionnant le bucket `escrow-evidence`
  - [ ] Câbler `backend.environment` (endpoint interne `http://minio:9000`, keys) + `depends_on`
- [ ] **T6 — Test round-trip** (AC: 6)
  - [ ] Ajouter Testcontainers (`org.testcontainers:junit-jupiter` + module `minio`) en scope `test` — **vérifier la dernière version stable du BOM Testcontainers** avant de figer
  - [ ] Test d'intégration : démarre MinIO conteneurisé, `store` un binaire connu, `load`, assert égalité octet-pour-octet + clé au format `{tx}/{uuid}`
- [ ] **T7 — Non-régression** (AC: tous)
  - [ ] `mvn test` vert (les tests existants `EscrowStateMachineTest`, `HmacSignerTest` ne doivent pas casser)
  - [ ] `docker compose -f infra/docker-compose.yml up` : backend `healthy`, MinIO `healthy`, bucket présent

## Dev Notes

### Schéma de la table (ERD → SQL)

Colonnes `evidence_files` (source : `ARCHITECTURE-SPINE.md` §Structural Seed ERD) :

| Colonne | Type SQL | Notes |
|---|---|---|
| `id` | `BIGSERIAL PRIMARY KEY` | |
| `transaction_id` | `BIGINT NOT NULL REFERENCES escrow_transactions(id)` | |
| `uploaded_by_user_id` | `BIGINT REFERENCES users(id)` | **nullable** (null si dépôt partenaire machine) |
| `uploader_type` | `VARCHAR(20) NOT NULL` | `BUYER`\|`SELLER`\|`ADMIN`\|`CARRIER_PARTNER` |
| `partner_company_id` | `BIGINT REFERENCES companies(id)` | nullable (renseigné pour un dépôt livreur) |
| `original_filename` | `VARCHAR(255)` | nom fourni, **assaini** — jamais utilisé pour la clé de stockage |
| `mime_type` | `VARCHAR(100)` | |
| `size_bytes` | `BIGINT` | |
| `storage_key` | `VARCHAR(500) NOT NULL` | clé opaque `{transaction_id}/{uuid}` |
| `comment` | `TEXT` | nullable ici (obligation applicative gérée en 1.2/2.1) |
| `status` | `VARCHAR(20) NOT NULL` | `ACTIVE`\|`WITHDRAWN` |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT now()` | **heure serveur = source de vérité** (AD-11) |
| `withdrawn_at` | `TIMESTAMPTZ` | nullable |
| `withdrawn_by_user_id` | `BIGINT REFERENCES users(id)` | nullable |

Index : `CREATE INDEX idx_evidence_transaction ON evidence_files (transaction_id, created_at);` (tri chronologique FR-10).

### Conventions à imiter (fichiers existants — LIRE avant de coder)

- **Migration** : suivre `backend/src/main/resources/db/migration/V1__init.sql` — `BIGSERIAL PRIMARY KEY`, `TIMESTAMPTZ NOT NULL DEFAULT now()`, `REFERENCES table(id)`, `CREATE INDEX idx_<table>_<col>`. `ddl-auto: none` (schéma détenu par Flyway, cf. `application.yml`).
- **Entité** : calquer `domain/AuditLog.java` — package `com.zlecaf.escrow.domain`, imports `jakarta.persistence.*`, `@Id @GeneratedValue(strategy = GenerationType.IDENTITY)`, `@Column(name = "snake_case")`, timestamp en `java.time.Instant` avec `@PrePersist void onCreate()`. Pour `@Version` optimiste, voir `domain/EscrowTransaction.java` (non requis ici mais cohérence). Enums → `@Enumerated(EnumType.STRING)`.
- **Config** : `application.yml` utilise partout le pattern `${ENV_VAR:default}` (voir blocs datasource/jwt/rabbitmq). Ajouter un bloc `app.evidence:` sur ce modèle.
- **Injection** : constructeur uniquement (pas de `@Autowired` sur champ). Adaptateur = `@Component`, config = `@Configuration @Bean`.

### Snippet — S3Client pour MinIO (AWS SDK v2)

À placer dans `config/StorageConfig.java`. Idiome vérifié pour l'API 2.47.x — MinIO exige le **path-style** (depuis 2.18.x le SDK bascule en virtual-hosted par défaut sur endpoint override) :

```java
@Bean
S3Client s3Client(
        @Value("${app.evidence.endpoint:http://localhost:9000}") String endpoint,
        @Value("${app.evidence.access-key:minioadmin}") String accessKey,
        @Value("${app.evidence.secret-key:minioadmin}") String secretKey) {
    return S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)))
        .region(Region.US_EAST_1)            // région factice, requise par le SDK
        .forcePathStyle(true)                // OBLIGATOIRE pour MinIO
        .build();
}
```

`store` : `PutObjectRequest.builder().bucket(bucket).key(txId + "/" + UUID.randomUUID()).contentType(contentType).build()` + `RequestBody.fromBytes(content)` → retourner la `key`. `load` : `getObject(GetObjectRequest…)` → `InputStream`.

### Service `minio` (docker-compose)

Modèle (aligné sur le style de `infra/docker-compose.yml` : healthcheck + volume nommé) :

```yaml
  minio:
    image: minio/minio:RELEASE.2025-10-15T17-29-55Z
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports: ["9000:9000", "9001:9001"]
    volumes: [ "miniodata:/data" ]
    healthcheck:
      test: ["CMD", "mc", "ready", "local"]
      interval: 5s
      timeout: 3s
      retries: 10
  minio-init:
    image: minio/mc
    depends_on:
      minio: { condition: service_healthy }
    entrypoint: >
      /bin/sh -c "mc alias set local http://minio:9000 minioadmin minioadmin &&
                  mc mb -p local/escrow-evidence || true"
```

Ajouter `miniodata:` sous `volumes:`. Câbler dans `backend.environment` :
`APP_EVIDENCE_ENDPOINT: http://minio:9000`, `APP_EVIDENCE_ACCESS_KEY: minioadmin`, `APP_EVIDENCE_SECRET_KEY: minioadmin`, `APP_EVIDENCE_BUCKET: escrow-evidence`, et `depends_on: minio (service_healthy)`.

### Project Structure Notes

Arbre cible (source : `ARCHITECTURE-SPINE.md` §Structural Seed) :

```
backend/src/main/java/com/zlecaf/escrow/
  domain/       EvidenceFile.java, UploaderType.java, EvidenceStatus.java   (NEW)
  repository/   EvidenceFileRepository.java                                 (NEW)
  config/       StorageConfig.java                                          (NEW)
  service/storage/  EvidenceStorage.java, MinioEvidenceStorage.java         (NEW)
backend/src/main/resources/db/migration/  V2__evidence_files.sql            (NEW)
backend/src/main/resources/application.yml                                  (UPDATE: bloc app.evidence)
backend/pom.xml                                                             (UPDATE: awssdk s3 BOM + testcontainers)
infra/docker-compose.yml                                                    (UPDATE: services minio + minio-init, volume)
```

Aucun fichier `web/`, `EvidenceService`, ni test de la machine à états n'est touché par cette story.

### Testing standards

- Le round-trip (AC-6) est un **test d'intégration Testcontainers** (module `minio`), pas un mock — la revue exige la preuve octet-pour-octet contre un vrai MinIO éphémère.
- Les tests existants utilisent H2 pour les slices (`EscrowStateMachineTest`) et JUnit 5 + `spring-boot-starter-test`. Rester sur JUnit 5.
- Ne pas introduire de régression : `mvn test` doit rester vert.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 1.1] — user story + AC d'origine
- [Source: _bmad-output/planning-artifacts/architecture/architecture-Escrow-2026-07-15/ARCHITECTURE-SPINE.md#AD-6] — port EvidenceStorage, clé opaque
- [Source: …/ARCHITECTURE-SPINE.md#AD-11] — created_at heure serveur
- [Source: …/ARCHITECTURE-SPINE.md#Stack] — versions Tika 3.3.1 / AWS SDK s3 2.47.6 / MinIO RELEASE.2025-10-15
- [Source: …/ARCHITECTURE-SPINE.md#Structural Seed] — ERD + arbre source
- [Source: backend/src/main/resources/db/migration/V1__init.sql] — idiomes de migration
- [Source: backend/src/main/java/com/zlecaf/escrow/domain/AuditLog.java] — conventions d'entité JPA (JSONB, @PrePersist, Instant)
- [Source: backend/src/main/resources/application.yml] — pattern de config ${ENV:default}
- [Source: infra/docker-compose.yml] — style de service (healthcheck, volume)
- [Web: docs.aws.amazon.com/sdk-for-java/latest/developer-guide/examples-s3.html] — forcePathStyle(true) pour endpoint override (MinIO)

### Latest tech information (vérifié 2026-07-16)

- `software.amazon.awssdk:s3` **2.47.6** (AWS SDK Java v2 ; v1 EOL 31/12/2025). Utiliser le BOM `software.amazon.awssdk:bom` pour aligner les versions.
- MinIO `RELEASE.2025-10-15T17-29-55Z`. ⚠️ Le dépôt OSS MinIO est archivé (avr. 2026) ; le port `EvidenceStorage` + client S3 standard permettent une bascule ultérieure sans toucher les endpoints (dette notée en `Deferred`).
- Depuis SDK v2 **2.18.x**, `endpointOverride` bascule en virtual-hosted-style par défaut → `forcePathStyle(true)` est **obligatoire** pour MinIO.
- Testcontainers : vérifier la dernière version stable du BOM avant de figer (module `minio` disponible).

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

Ultimate context engine analysis completed - comprehensive developer guide created.

### File List
