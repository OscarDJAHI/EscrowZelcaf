---
baseline_commit: f71976af1708d8bed004c8f3afa4a62f7b828dd4
---
# Story 1.2: Externalisation des secrets

Status: review

## Story

As a opérateur,
I want qu'aucun secret ne vive dans le code ou les fichiers versionnés,
So that une fuite du dépôt ne compromette pas la production.

## Acceptance Criteria

1. **Given** un démarrage sans variable critique (JWT, DB, MinIO, clés HMAC), **When** l'application boote, **Then** elle échoue explicitement en nommant la variable manquante (NFR-P1), **And** aucun secret en dur ne subsiste dans le code, les migrations ou docker-compose.
2. **Given** l'audit du dépôt git, **When** on recherche les motifs de secrets connus, **Then** aucune valeur sensible active n'est trouvée ; les valeurs historiques compromises sont révoquées/rotées.

## Tasks / Subtasks

- [x] Task 1 — Supprimer les défauts faibles d'application.yml (AC1) : `SPRING_DATASOURCE_PASSWORD`, `SPRING_RABBITMQ_PASSWORD`, `ESCROW_JWT_SECRET`, `ESCROW_STORAGE_SECRET_KEY` sans valeur par défaut (les identifiants non secrets gardent leurs défauts dev).
- [x] Task 2 — Échec de démarrage explicite et agrégé : `RequiredSecretsEnvironmentPostProcessor` (spring.factories) qui liste TOUTES les variables manquantes en une seule erreur, + tests unitaires (0/1/4 manquantes).
- [x] Task 3 — Tests : `src/test/resources/application.properties` fournit les 4 valeurs factices (les 238 tests ne dépendent plus des défauts) ; suite complète verte.
- [x] Task 4 — docker-compose : plus aucun secret en dur — interpolation `${VAR:?message}` (requis) / `${VAR:-défaut}` (non secret) ; `infra/.env.example` versionné, `infra/.env` gitignoré ; `minio-init` et pgAdmin paramétrés ; `docker compose config` échoue sans .env en nommant la variable.
- [x] Task 5 — Audit + rotation (AC2) : scan Trivy secrets (déjà en CI, 0 finding), grep des motifs connus, constat sur les valeurs historiques (dev-only, jamais déployées — rotation = exigence .env local + injection runtime en staging/prod, AR-P4/Story 11.2) documenté ici.
- [x] Task 6 — README quick-start mis à jour (étape `cp infra/.env.example infra/.env`).

## Dev Notes

- **État vérifié 2026-07-25** : `application.yml` lignes 8/27/43/52 = défauts faibles (`escrow`, `guest`, `change-me…`, `minioadmin`) ; compose = POSTGRES_PASSWORD, PGADMIN_DEFAULT_PASSWORD, RABBITMQ guest/guest, MINIO_ROOT_*, ESCROW_JWT_SECRET, SPRING_DATASOURCE_PASSWORD, ESCROW_STORAGE_SECRET_KEY en dur + `minio-init` avec creds inline dans l'entrypoint.
- **Clés HMAC partenaire** : provisionnées en DB (V4/V5, jamais dans la config) — l'AC les mentionne, rien à faire côté fichiers ; l'admin bootstrap (Story 1.1) lit déjà des env vars sans défaut (skip si absentes, by-design).
- **Contraintes** : ne PAS toucher au harnais de test Vitest ; ne pas casser les 238 tests backend (ils utilisaient les défauts → Task 3 d'abord en local) ; healthchecks compose utilisent l'utilisateur `escrow` (username garde son défaut) ; AR-P4 (gestion des secrets staging/prod) = Story 11.2, HORS périmètre — ici on externalise, on ne choisit pas l'outil.
- **CI** : la suite backend en CI n'a pas les 4 variables → Task 3 (test properties) doit suffire ; vérifier le run.

### References

- [Source: epics.md#Story-1.2] · [Source: ARCHITECTURE-SPINE.md NFR-P1, conventions] · [Source: project-context.md §stack/pièges]

## Dev Agent Record

### Agent Model Used

claude-fable-5, session du 2026-07-25

### Completion Notes List

- **AC1 prouvé en réel** : `mvnw spring-boot:run` sans variables → exit 1, UNE erreur listant les 4 variables (`RequiredSecretsEnvironmentPostProcessor`, enregistré via spring.factories, ordre post-ConfigData). Le validateur agrège (vs l'échec placeholder de Spring qui s'arrête à la première) ; valeur blanche = manquante ; placeholder non résolu = manquant.
- **Tests** : 244/244 verts (238 + 6 nouveaux tests unitaires du validateur) ; `src/test/resources/application.properties` fournit des valeurs factices clairement marquées (les Testcontainers priment via @DynamicPropertySource pour les connexions réelles).
- **Compose** : plus aucun secret en dur ; `${VAR:?message}` sur les 5 secrets (Postgres, pgAdmin, RabbitMQ, MinIO, JWT), `${VAR:-défaut}` sur les identifiants non secrets ; `minio-init` lit ses creds de l'environnement (échappement `$$` vérifié au rendu) ; `docker compose config` sans .env échoue en nommant la variable (vérifié) et rend correctement avec `.env.example` (vérifié). RabbitMQ passe de guest/guest à un utilisateur dédié (pas de volume, aucune migration).
- **AC2 audit** : `git grep` des motifs (password|secret)[:=] hors tests/lockfile → seuls des usages légitimes frontend (formulaire de login) ; scan Trivy secrets en CI = 0 finding. **Rotation des valeurs historiques** : `escrow`, `guest`, `minioadmin`, `change-me…`, `local-dev-secret…` n'ont JAMAIS servi hors dev local (aucun déploiement n'existe) ; leur « révocation » = suppression des défauts (un boot sans injection échoue désormais) + chaque poste régénère les siennes via infra/.env. Les secrets staging/prod seront injectés par la solution AR-P4 (Story 11.2) — hors périmètre ici.
- README quick-start : étape `cp infra/.env.example infra/.env` + contrat fail-fast documentés.

### File List

- backend/src/main/resources/application.yml (modifié — 4 défauts supprimés)
- backend/src/main/java/com/zlecaf/escrow/config/RequiredSecretsEnvironmentPostProcessor.java (nouveau)
- backend/src/main/resources/META-INF/spring.factories (nouveau)
- backend/src/test/java/com/zlecaf/escrow/config/RequiredSecretsEnvironmentPostProcessorTest.java (nouveau — 6 tests)
- backend/src/test/resources/application.properties (nouveau — valeurs factices de test)
- infra/docker-compose.yml (modifié — interpolation :?/:-)
- infra/.env.example (nouveau) ; .gitignore (infra/.env)
- README.md (quick-start)
- _bmad-output/implementation-artifacts/sprint-status.yaml + ce fichier

## Change Log

- 2026-07-25 : Story implémentée en une session — externalisation des 4 secrets critiques backend + 5 secrets compose, fail-fast agrégé prouvé, 244 tests verts, audit AC2 documenté.
