---
baseline_commit: 91c221d0618caa48d92bd8272035049cb60a50ca
---
# Story 11.1: Pipeline CI/CD avec gate de tests et scan de vulnérabilités

Status: done

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

- [x] Task 0 — PRÉREQUIS BLOQUANT : remote git + plateforme CI (AC: 1, 3)
  - [x] Le dépôt est **local-only** (aucun remote). Créer un dépôt GitHub **privé** et pousser `develop` + `main`. Décision Oscard actée : GitHub privé `OscarDJAHI/EscrowZelcaf` — créé via gh CLI, develop+main poussées.
  - [x] Vérifier que `main` existe comme branche par défaut (le repo local n'a peut-être que `develop` — créer `main` depuis l'état stable si absent).
- [x] Task 1 — Workflow CI backend (AC: 1, 3)
  - [x] `.github/workflows/ci.yml`, déclencheurs `pull_request` + `push` sur `develop`/`main`.
  - [x] Job `backend`: `actions/checkout` → `actions/setup-java` (Temurin 21, cache maven) → `mvn -B verify` dans `backend/`. Docker est présent sur `ubuntu-latest` → Testcontainers fonctionne sans service additionnel.
  - [x] Pas de wrapper Maven dans le repo (`backend/mvnw` absent) : wrapper 3.9.9 AJOUTÉ (backend/.gitignore corrigé — il ignorait `.mvn/`).
- [x] Task 2 — Workflow CI frontend (AC: 1, 3)
  - [x] Job `frontend`: `actions/setup-node` (Node 24 — version locale v24.16.0, pas de champ `engines` : documenter la contrainte Node dans `package.json` (rendue contraignante en revue via .npmrc engine-strict)) → `npm ci` → `npm run test` (Vitest run) → `npm run build` (le build Vite/PWA doit rester vert, précédent établi depuis Story 1.5 POC).
- [x] Task 3 — Gate bloquant (AC: 1, 3)
  - [x] Branch protection sur `main` ET `develop` : **IMPOSSIBLE sur le plan GitHub Free avec un dépôt privé** (HTTP 403 « Upgrade to GitHub Pro », vérifié sur l'API branch-protection ET l'API rulesets). Limitation documentée + commandes prêtes dans le Dev Agent Record ; décision Oscard requise (Pro ~4$/mois ou passage en public). En attendant, le gate est *visible* (croix rouge sur PR) mais pas *inviolable*.
  - [x] PR de démonstration avec un test volontairement rouge → preuve capturée (PR #1, job frontend fail, mergeStateStatus UNSTABLE — détails au Dev Agent Record) → fermée sans merge.
- [x] Task 4 — SBOM (AC: 2)
  - [x] Backend : plugin `cyclonedx-maven-plugin` 2.9.1 (goal `makeAggregateBom`) → `bom.json`.
  - [x] Frontend : `npm sbom --sbom-format cyclonedx --package-lock-only --omit optional` (le mode lockfile-only échoue en ESBOMPROBLEMS sur les deps bundled de @tailwindcss/oxide-wasm32-wasi sans --omit optional).
  - [x] Images Docker : `trivy image --format cyclonedx` sur les deux images buildées en CI.
  - [x] Tous archivés via `actions/upload-artifact` (artefact `sbom`, 4 fichiers .cdx.json).
- [x] Task 5 — Scan de vulnérabilités avec seuil (AC: 2)
  - [x] Trivy `--severity CRITICAL,HIGH --exit-code 1` + `.trivyignore` versionné. Backend scanné via son SBOM (trivy fs sur un pom résout l'arbre à distance → 429 Maven Central, appris à nos dépens), frontend via lockfile, images via trivy image.
  - [x] Triage fait : 44 CVE backend CRITICAL/HIGH, TOUTES transitives du parent Boot 3.3.5 → exemptées datées 2026-07-24 avec purge obligatoire à la 11.9 (aucun bump applicatif, conforme au cadrage). Corrigés (triviaux) : postcss 8.5.16→8.5.23 (seul finding frontend), apk upgrade au build des 2 images (5 HIGH OS libexpat/p11-kit → 0).
  - [x] Job supply-chain stabilisé (vert en CI) mais requis-checks impossibles sans Pro — même décision que Task 3 ; le README documente son statut advisory.
- [x] Task 6 — Vérification finale (AC: 3)
  - [x] CI verte sur `develop` (run 30125261303, 3 jobs verts) : « Tests run: 238, Failures: 0 » confirmé dans le log CI + 170 tests Vitest + build PWA.
  - [x] README : badge CI + tableau des jobs + section « comment lire un échec CI ».


### Review Findings (code review 2026-07-25, 3 relecteurs adversariaux)

- [x] [Review][Decision] D1 — RÉSOLU (Oscard 2026-07-25) : option (b) main dur (enforce_admins) + develop souple (checks requis, bypass admin) — boucle de pose automatique armée en attendant la propagation du plan Pro. Détail initial : Stratégie de protection & flux post-Pro (AC1) : une fois la protection posée (checks requis + strict + enforce_admins sur develop ET main), tout push direct sur develop sera rejeté — le flux actuel (commits directs develop) meurt. Choisir : (a) protection complète 2 branches → tout via PR ; (b) main complet + develop avec enforce_admins=false (recommandé en solo) ; (c) main seul.
- [x] [Review][Patch] P1 — Épingler Trivy (version + script d'install par tag, pas main) [.github/workflows/ci.yml]
- [x] [Review][Patch] P2 — Ignorefiles scopés par cible + dates d'expiration machine exp: [.trivyignore]
- [x] [Review][Patch] P3 — Épingler les actions GitHub par SHA de commit [.github/workflows/ci.yml]
- [x] [Review][Patch] P4 — distributionSha256Sum dans maven-wrapper.properties [backend/.mvn/wrapper/maven-wrapper.properties]
- [x] [Review][Patch] P5 — Déclencheurs schedule hebdo + workflow_dispatch [.github/workflows/ci.yml]
- [x] [Review][Patch] P6 — Cache de la base de vulnérabilités Trivy (résilience rate-limit ghcr) [.github/workflows/ci.yml]
- [x] [Review][Patch] P7 — Découpler les scans/builds multi-commandes (le 2e n'est plus masqué par le fail du 1er) [.github/workflows/ci.yml]
- [x] [Review][Patch] P8 — Bloc concurrency + cancel-in-progress [.github/workflows/ci.yml]
- [x] [Review][Patch] P9 — SBOM frontend complet (npm ci --ignore-scripts + npm sbom sur arbre installé, cohérent avec la surface scannée) [.github/workflows/ci.yml]
- [x] [Review][Patch] P10 — .npmrc engine-strict=true (rend engines contraignant) [frontend/.npmrc]
- [x] [Review][Patch] P11 — Scan de secrets Trivy sur le dépôt (0 bruit vérifié localement) [.github/workflows/ci.yml]
- [x] [Review][Patch] P12 — timeout-minutes supply-chain 30→40 (build Maven Docker à froid) [.github/workflows/ci.yml]
- [x] [Review][Patch] P13 — Ré-ignorer .mvn/wrapper/maven-wrapper.jar (wrapper only-script, pas de jar versionné) [backend/.gitignore]
- [x] [Review][Patch] P14 — Doc : colonne Gate du README alignée sur l'état réel ; File List complété (nanoid 3.3.15→3.3.16 entraîné par postcss) ; claim « verrouille la CI » de Task 2 reformulé [README.md + ce fichier]
- [x] [Review][Defer] W1 — apk upgrade rend les images non reproductibles (SBOM du run ≠ image rebuildée ailleurs) — trade-off assumé pré-prod (sécurité > reproductibilité) ; à retraiter en Story 11.3 (pinning par digest + refresh orchestré)
- [x] [Review][Defer] W2 — RÉSOLU 2026-07-25 : protection posée (repo passé public — le 403 Pro est tombé) et exports JSON versionnés (branch-protection-{main,develop}-2026-07-25.json). main: checks requis+strict+enforce_admins ; develop: idem sans enforce_admins (D1)

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

claude-fable-5 (Claude Fable 5) — session dev-story du 2026-07-24

### Debug Log References

- Run CI vert final sur develop : https://github.com/OscarDJAHI/EscrowZelcaf/actions/runs/30125261303 (3 jobs verts ; log backend : « Tests run: 238, Failures: 0 »)
- Premier run vert gate (backend+frontend) : run 30124023513 (1m52 — runner rapide, comptage vérifié dans le log)
- Échec intermédiaire supply-chain #1 : npm sbom ESBOMPROBLEMS (deps bundled @tailwindcss/oxide-wasm32-wasi en lockfile-only) → --omit optional (reproduit et validé localement)
- Échec intermédiaire supply-chain #2 : 5 HIGH OS sur l'image backend (libexpat 2.8.1, p11-kit 0.25.5) → apk upgrade au build ; image frontend re-scannée localement = 0 vuln
- Rate-limit appris : trivy fs sur pom.xml résout l'arbre via Maven Central → 429 (blocage IP 30 min) ; scan backend basculé sur le SBOM CycloneDX

### Completion Notes List

- **Dépôt GitHub créé (décision Oscard)** : privé `OscarDJAHI/EscrowZelcaf`, branches develop+main poussées, main = défaut. Premier push en HTTP 400 → `http.postBuffer` monté à 500 Mo (config locale).
- **AC1 partiellement satisfait — SEUL point en écart** : la CI tourne sur chaque PR/push (AC1 partie 1 ✅) mais le caractère « non contournable » du gate est IMPOSSIBLE sur plan GitHub Free + dépôt privé : branch protection ET rulesets renvoient 403 « Upgrade to GitHub Pro ». Le gate est visible (croix rouge, mergeStateStatus UNSTABLE) mais le bouton merge reste cliquable. **Décision Oscard requise : GitHub Pro (~4 $/mois) ou dépôt public.** Dès que débloqué, appliquer (commandes prêtes) :
  `gh api -X PUT repos/OscarDJAHI/EscrowZelcaf/branches/{main,develop}/protection` avec required_status_checks strict = [« Backend (Maven + Testcontainers) », « Frontend (Vitest + build PWA) »], enforce_admins=true (payload exact dans l'historique de session ; ajouter le job supply-chain aux checks requis dans un second temps).
- **Preuve AC3 (PR démo)** : PR #1 https://github.com/OscarDJAHI/EscrowZelcaf/pull/1 — test Vitest volontairement rouge, run final 30125560469 : frontend FAIL (seul rouge), backend PASS, supply-chain PASS, mergeStateStatus UNSTABLE. Fermée sans merge, branche supprimée.
- **AC2 satisfait** : 4 SBOM CycloneDX archivés (artefact `sbom` : deps backend via plugin Maven 2.9.1, deps frontend via npm sbom, 2 images via trivy) ; scans Trivy CRITICAL/HIGH bloquants avec `.trivyignore` daté. Triage initial : 44 CVE backend exemptées (100 % transitives du parent Boot 3.3.5, purge obligatoire Story 11.9 — aucun bump applicatif conformément au cadrage) ; postcss bumpé 8.5.16→8.5.23 ; `apk upgrade` au build des 2 images (5 HIGH OS → 0).
- Wrapper Maven 3.9.9 ajouté ; `backend/.gitignore` corrigé (ignorait `.mvn/`, la négation `!maven-wrapper.jar` était morte sous un répertoire ignoré).
- `engines: node >=22` ajouté au package.json (image Docker builde en node:22, CI épinglée à 24).
- Actions épinglées aux majors courants (checkout v7, setup-java v5, setup-node v7, upload-artifact v7) — les v4 tournaient en mode dépréciation Node 20.
- **Périmètre non traité (assumé)** : job supply-chain hors required-checks (même blocage plan Free ; statut advisory documenté au README).

### File List

- .github/workflows/ci.yml (nouveau)
- .trivyignore (nouveau)
- backend/mvnw, backend/mvnw.cmd, backend/.mvn/wrapper/maven-wrapper.properties (nouveaux)
- backend/.gitignore (modifié — .mvn/ n'est plus ignoré)
- backend/pom.xml (modifié — plugin cyclonedx-maven 2.9.1)
- backend/Dockerfile (modifié — apk upgrade)
- frontend/Dockerfile (modifié — apk upgrade)
- frontend/package.json (modifié — engines node >=22)
- frontend/package-lock.json (modifié — postcss 8.5.23 + nanoid 3.3.16 entraîné)
- frontend/.npmrc (nouveau — engine-strict=true)
- .trivyignore-backend (renommé depuis .trivyignore, scope backend + exp:2026-10-31)
- README.md (modifié — badge CI + section CI)
- _bmad-output/implementation-artifacts/sprint-status.yaml (suivi)
- _bmad-output/implementation-artifacts/11-1-pipeline-ci-cd-gate-tests.md (ce fichier)

## Change Log

- 2026-07-25 (AC1 clos) : repo rendu public par Oscard → protection de branches posée et vérifiée (D1 : main dur, develop souple), exports JSON versionnés. Le gate est désormais réellement non contournable sur main.

- 2026-07-25 (clôture) : run CI 30131669761 entièrement vert avec les 14 patchs (le gate agrégé a démontré son utilité dès le run précédent en isolant le seul scan rouge). Statut → done. Reste hors-repo : pose de la protection de branches (boucle automatique armée, politique D1 main dur/develop souple — en attente propagation plan Pro) ; à vérifier sur la première PR réelle.

- 2026-07-25 : Code review (3 relecteurs) — 14 patchs appliqués : Trivy épinglé v0.72.0 (script par tag), exemptions scopées backend + exp:2026-10-31, actions épinglées par SHA, distributionSha256Sum wrapper (vérifié par re-téléchargement forcé), cron hebdo + workflow_dispatch, cache DB Trivy, scans découplés + gate agrégé, concurrency, SBOM frontend sur arbre installé, engine-strict, scan secrets dépôt (0 finding), timeout 40 min, jar wrapper ré-ignoré, README/File List corrigés. D1 tranché : main dur / develop souple. W1/W2 au ledger.

- 2026-07-24 : Story implémentée en une session. Dépôt GitHub privé EscrowZelcaf créé (Task 0), CI gate de tests backend/frontend verte (238+170 tests), SBOM+scan Trivy verts après triage (44 exemptions datées → 11.9, postcss bumpé, images patchées apk upgrade), PR démo #1 rouge fermée sans merge. Écart AC1 documenté : protection de branche impossible en plan Free/privé — décision Pro-ou-public à prendre.
