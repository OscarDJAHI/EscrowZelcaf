# Story 11.1: Pipeline CI/CD avec gate de tests et scan de vulnérabilités

Status: ready-for-dev

## Story

As a équipe,
I want que chaque pull request soit buildée, testée et scannée avec un gate bloquant avant merge,
So that aucune régression ni vulnérabilité connue n'atteigne la branche principale, et que la dette de tests se résorbe en continu sous protection du gate (NFR-P11, NFR-P12).

## Acceptance Criteria

1. **Given** une pull request vers la branche principale, **When** la CI s'exécute, **Then** build + tests backend (Maven/Testcontainers) + tests frontend (Vitest) tournent automatiquement, **And** tout échec bloque le merge (gate obligatoire, non contournable par défaut).
2. **Given** le pipeline CI, **When** il s'exécute, **Then** un SBOM des dépendances (backend + frontend) et des images Docker est produit et archivé comme artefact, **And** le scan de vulnérabilités échoue le pipeline au-delà du seuil de sévérité convenu (CRITICAL/HIGH sans exemption documentée et datée).
3. **Given** les suites de tests existantes du dépôt (backend Testcontainers + frontend Vitest/fake-indexeddb), **When** la CI tourne pour la première fois sur `develop`, **Then** la suite complète passe verte dans l'environnement CI (Docker disponible pour Testcontainers), **And** la preuve d'un merge bloqué par un test rouge est apportée sur une PR de démonstration.

**Cadrage « piste zéro » (rapport IR 2026-07-24) :** livrer d'abord le **gate de tests** (AC1 + AC3) — c'est le prérequis de toutes les autres pistes. Le volet SBOM + scan (AC2) se livre en second dans la même story ; s'il s'avère trop lourd (tuning des seuils, faux positifs), le scinder en 11.1-bis plutôt que de retarder le gate.

## Tasks / Subtasks

- [ ] Task 0 — PRÉREQUIS BLOQUANT : remote git + plateforme CI (AC: 1, 3)
  - [ ] Le dépôt est **local-only** (aucun remote). Créer un dépôt GitHub **privé** et pousser `develop` + `main`. ⚠️ DÉCISION OSCARD REQUISE avant exécution : confirmation plateforme (GitHub supposé — `gh` CLI utilisable) et nom du dépôt. NE PAS pousser sans accord explicite.
  - [ ] Vérifier que `main` existe comme branche par défaut (le repo local n'a peut-être que `develop` — créer `main` depuis l'état stable si absent).
- [ ] Task 1 — Workflow CI backend (AC: 1, 3)
  - [ ] `.github/workflows/ci.yml`, déclencheurs `pull_request` + `push` sur `develop`/`main`.
  - [ ] Job `backend`: `actions/checkout` → `actions/setup-java` (Temurin 21, cache maven) → `mvn -B verify` dans `backend/`. Docker est présent sur `ubuntu-latest` → Testcontainers fonctionne sans service additionnel.
  - [ ] Pas de wrapper Maven dans le repo (`backend/mvnw` absent) : soit ajouter le wrapper (recommandé, versionne Maven), soit utiliser le Maven du runner — trancher et documenter.
- [ ] Task 2 — Workflow CI frontend (AC: 1, 3)
  - [ ] Job `frontend`: `actions/setup-node` (Node 24 — version locale v24.16.0, pas de champ `engines` : en fixer un dans `package.json` pour verrouiller la CI) → `npm ci` → `npm run test` (Vitest run) → `npm run build` (le build Vite/PWA doit rester vert, précédent établi depuis Story 1.5 POC).
- [ ] Task 3 — Gate bloquant (AC: 1, 3)
  - [ ] Branch protection sur `main` ET `develop` : required status checks = jobs backend + frontend, `strict` (à jour avec la base), pas de bypass admin par défaut. Via `gh api` ou UI (documenter la config appliquée).
  - [ ] PR de démonstration avec un test volontairement rouge → capturer la preuve du merge bloqué (URL/screenshot dans le Dev Agent Record) → fermer la PR sans merger.
- [ ] Task 4 — SBOM (AC: 2)
  - [ ] Backend : plugin `cyclonedx-maven-plugin` (goal `makeAggregateBom`) → `bom.json`.
  - [ ] Frontend : SBOM npm (ex. `npm sbom --sbom-format cyclonedx` natif npm ≥ 9, ou `@cyclonedx/cyclonedx-npm`).
  - [ ] Images Docker : `syft` ou `trivy sbom` sur les images buildées depuis `backend/Dockerfile` et `frontend/Dockerfile`.
  - [ ] Tous archivés via `actions/upload-artifact` (rétention par défaut OK).
- [ ] Task 5 — Scan de vulnérabilités avec seuil (AC: 2)
  - [ ] `trivy` (fs + image) avec `--severity CRITICAL,HIGH --exit-code 1` + fichier `.trivyignore` versionné pour les exemptions (chaque entrée = CVE + justification + date, revue périodique).
  - [ ] Première exécution : trier les findings existants — corriger les triviaux (bump de version), exempter avec date le reste. La stack a une dette EOL CONNUE (Boot 3.3.5, cf. Story 11.9) : des CVE HIGH sont probables ; les exemptions datées sont le mécanisme prévu, ne pas « verdir » en désactivant le scan.
  - [ ] Ajouter les jobs scan aux required status checks une fois stabilisés (pas avant — le gate de tests ne doit pas attendre le tuning du scan).
- [ ] Task 6 — Vérification finale (AC: 3)
  - [ ] CI verte sur `develop` : suite backend complète (238 tests, Docker/Testcontainers) + frontend (170 tests Vitest) + build.
  - [ ] README : badge CI + section « comment lire un échec CI ».

## Dev Notes

### Contraintes d'architecture (spine 2026-07-24, contrat liant)

- **NFR-P11/P12** portés par cette story (spine §Opérabilité : « CI, stack déployée, observabilité »).
- La CI créée ici est le **socle que d'autres stories étendront** — ne pas la sur-spécialiser : Story 2.1 y ajoutera la garde i18n « clé manquante = échec CI » (spine ligne Rule i18n), la garde de taille de bundle par espace est une `[ASSUMPTION]` du spine (frontend), Story 11.7 s'appuiera sur le gate pour résorber la dette de tests. Structure en jobs séparés backend/frontend pour permettre ces ajouts.
- **Aucun secret dans le workflow** : la CI de cette story ne déploie rien (le déploiement = 11.2/11.3, spikes AR-P4/AR-P5 non tranchés). Build + test + scan uniquement. Ne pas anticiper le CD.
- Enums/codes machine en anglais (convention spine) — s'applique aux éventuels scripts/outils ajoutés.

### État du dépôt (vérifié 2026-07-24)

- **Pas de remote git** (`git remote -v` vide) → Task 0 bloquante. Branche courante : `develop` ; `main` = branche PR par défaut déclarée mais vérifier son existence.
- `backend/` : Spring Boot 3.3.5 (parent), Java 21, Maven, PAS de `mvnw`. Tests = 238, **exigent Docker** (Testcontainers `postgres:16-alpine`, forcé en 1.21.4 dans le pom pour compat Docker Engine 29+ — ne pas downgrader).
- `frontend/` : Vite + vue, `npm run test` = `vitest run` (170 tests, harnais `vitest.config.js` séparé + `vitest.setup.js` avec swap Blob node:buffer/FormData undici — NE PAS toucher, piège fake-indexeddb documenté), `npm run build` = build PWA (VitePWA). Node local v24.16.0, pas de champ `engines`.
- `infra/docker-compose.yml` existe (Postgres+MinIO+RabbitMQ+backend+frontend) — PAS nécessaire en CI (Testcontainers gère ses conteneurs) ; les Dockerfiles servent pour le SBOM/scan d'images.
- Aucune CI existante (`.github/` absent).

### Pièges connus (mémoire projet)

- Testcontainers en CI : premier run lent (pull d'images) — activer le cache maven ; prévoir `timeout-minutes` généreux (~20 min) sur le job backend.
- Le build frontend n'a AUCUN lint configuré — ne pas inventer un job lint dans cette story (hors AC).
- Ports 8080/5432 : conflits locaux documentés, non pertinents sur runner CI éphémère.

### Testing standards

- La CI ne crée pas de nouveaux tests applicatifs : elle EXÉCUTE les suites existantes telles quelles. Tout test rouge en CI qui passe en local = problème d'environnement CI à corriger côté workflow, pas côté test (sauf flakiness avérée, à documenter).
- La « preuve » AC3 est un artefact de process (PR démo bloquée), pas un test automatisé.

### Project Structure Notes

- Workflows dans `.github/workflows/ci.yml` (un seul fichier, jobs multiples).
- Exemptions scan dans `.trivyignore` à la racine (versionné, revu).
- Modifs `backend/pom.xml` limitées à l'ajout du plugin SBOM (aucun bump de dépendance applicative — c'est le territoire de 11.9).

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-11.1] (AC verbatim)
- [Source: _bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md#Opérabilité + conventions frontend/i18n]
- [Source: _bmad-output/planning-artifacts/implementation-readiness-report-2026-07-24.md#Prochaines-étapes] (11.1-lite = premier candidat piste zéro)
- [Source: _bmad-output/implementation-artifacts/sprint-status.yaml] (contraintes d'ordonnancement, action item « prioriser 11-1 »)

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
