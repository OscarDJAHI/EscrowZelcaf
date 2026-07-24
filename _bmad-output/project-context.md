---
project_name: 'Escrow_claude'
user_name: 'Oscard'
date: '2026-07-25'
sections_completed:
  [
    'technology_stack',
    'language_rules',
    'framework_rules',
    'testing_rules',
    'quality_rules',
    'workflow_rules',
    'critical_rules',
  ]
existing_patterns_found: 32
status: 'complete'
rule_count: 58
optimized_for_llm: true
---

# Project Context for AI Agents — Escrow ZLECAf

_Règles critiques et patterns que tout agent IA DOIT suivre en implémentant du code dans ce projet. Focalisé sur les détails non évidents. Source d'autorité architecturale : `_bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md` (AD-1..AD-30 normatifs — en cas de conflit, le spine gagne)._

---

## Technology Stack & Versions

| Composant | Version exacte | Notes critiques |
| --- | --- | --- |
| Java | 21 | `backend/` Maven, parent `spring-boot-starter-parent` |
| Spring Boot | 3.3.5 | Migration 4.1.x = exigence de lancement (EOL 3.x 30/06/2026) — ne pas migrer au détour d'une story |
| PostgreSQL | 16 | Schéma possédé par Flyway (`ddl-auto: none`), `open-in-view: false`, TZ UTC |
| Flyway | via BOM Boot | V1..V5 existantes ; `V<n>__desc.sql`, une migration par story qui en a besoin |
| Testcontainers | **1.21.4 épinglé** dans `<properties>` du pom | NE PAS retirer l'override : la 1.19.8 du parent parle Docker API v1.32, rejetée par Docker Engine 29+ |
| AWS SDK v2 (S3) | BOM 2.47.6 | + `httpclient5 5.6.2`/`httpcore5 5.4.3` épinglés (le parent Boot les rétrograderait et le client S3 échoue à la construction) |
| Apache Tika | 3.3.1 (`tika-core` seul) | Sniffing MIME magic-bytes ; version explicite, non gérée par le BOM |
| jjwt | 0.12.6 | api + impl + jackson (runtime) |
| hypersistence-utils-hibernate-63 | 3.9.0 | Mapping JSONB (audit_logs) |
| springdoc-openapi | 2.6.0 | Swagger UI sur `:8080/swagger-ui.html` |
| RabbitMQ | 3.13 | `connection-timeout: 3000ms` (API reste vivante si broker down) ; migration 4.2 LTS = exigence de lancement |
| MinIO / S3 | via port `EvidenceStorage` | Binaire MinIO orphelin ; backend S3 définitif = exigence de lancement — coder uniquement contre le port |
| Vue / Pinia / Vite | 3.5 / 2.3 / 6 | `frontend/`, Node >= 22, `"type": "module"` |
| Tailwind CSS | 4.0 (`@tailwindcss/vite`) | À configurer SUR les tokens CSS de DESIGN.md — jamais de valeur brute |
| vite-plugin-pwa | 0.21.1 | Précache du shell versionné, mise à jour contrôlée (pas de reload silencieux) |
| Vitest | 4.1.10 + jsdom 29 + fake-indexeddb 6 + undici 8 | `npm run test` = `vitest run` ; voir piège Blob ci-dessous |
| idb | 8.0.3 | File offline IndexedDB (`offlineQueue.idb.js`) |
| axios | 1.7.9 | Clients API sous `src/api/` |

## Critical Implementation Rules

### Language-Specific Rules (Java / Backend)

- Paquet racine `com.zlecaf.escrow`, **package-by-layer** : `web/` (+`web/dto/`) → `service/` → `{repository/, ports}` ; `scheduler/` appelle `service/`. Jamais de dépendance remontante.
- DTOs = **records Java** avec fabrique `static from(entity)`, regroupés par domaine dans un seul fichier (`AuthDtos`, `EscrowDtos`, `EvidenceDtos`…).
- Enums et codes machine **en anglais** (`PENDING_RECONCILIATION`, `EXECUTED`…) ; les libellés français des stories sont des libellés d'affichage i18n, jamais des valeurs stockées.
- Ids `IDENTITY` ; `TIMESTAMPTZ` partout, **heure serveur = source de vérité** (AD-11) ; montants `NUMERIC(19,2)` + devise en FK ISO-4217 — **jamais de flottants** (AD-14).
- Secrets : uniquement via variables d'environnement (`${VAR:default}` dans `application.yml`) ; champs secrets JPA annotés `@JsonProperty(access = WRITE_ONLY)` ; comparaison HMAC **constant-time** via `HmacSigner` existant.
- Erreurs API : enveloppe globale unique `{timestamp, status, error, message, code}` construite dans `GlobalExceptionHandler` ; `code` = token machine de l'enum `ErrorCode`, obligatoire (un `requireNonNull` transforme l'oubli en échec immédiat). Ne jamais créer de format d'erreur parallèle.

### Framework-Specific Rules (Spring / Vue)

**Spring :**

- `@Transactional` **dans le service uniquement** — jamais sur controllers ni repositories.
- Audit obligatoire **même transaction** : `AuditService` writer unique, méthodes `Propagation.MANDATORY` (échecs en `REQUIRES_NEW`). Aucun service n'écrit `audit_logs` directement (AD-5).
- Toute écriture comptable passe par le **seul** `LedgerService` (writer unique, partie double, solde dérivé — AD-13) ; wallets créés uniquement par `WalletService.getOrCreate`.
- Transitions d'état : **uniquement** via la matrice whitelist d'`EscrowStateMachine` (composant pur, source unique). États figés `INITIATED, FUNDS_LOCKED, SHIPPED, RELEASED, DISPUTED, REFUNDED` — aucun nouvel état ; toute transition nouvelle non prévue par AD-19 = conflit à remonter.
- Ports hexagonaux nommés `XxxProvider` / `XxxGateway` / `XxxSender` / `EvidenceStorage`, sous `service/<domaine>/` ; adaptateurs `<Fournisseur>Xxx`. Seuls les adaptateurs connaissent les fournisseurs externes.
- Gardes centralisées AVANT toute opération : appartenance transaction (`TransactionAccess`, AD-3), gating KYB/suspension (AD-20), rôle interne MANAGER/MEMBER (AD-30). Ne jamais recoder un contrôle par endpoint ; 403/404 uniformisés (anti-énumération).
- REST sous `/api/v1/...`, ressources imbriquées sous la transaction escrow ; admin sous `/api/v1/admin/...` ; notifications découplées via outbox transactionnel + RabbitMQ (AD-22) — aucun producteur n'appelle un fournisseur en direct.

**Vue :**

- Composition API + Pinia (stores sous `src/stores/`), clients API sous `src/api/`, alias `@` → `src/`.
- Offline = **deux briques distinctes** (AD-27) : cache de lecture horodaté (lecture seule, aucun optimisme financier) + file de rejeu IndexedDB (`offlineQueue.idb.js`, via `idb`) limitée à une **whitelist non financière** (preuve, litige). Toute action financière exige la connexion, jamais mise en file. Le rejeu porte la clé d'idempotence (AD-18).
- Toute chaîne UI par clé i18n EN/FR (AD-23) — clé manquante = échec CI ; le backend ne renvoie jamais de texte utilisateur, seulement des codes.
- Mapping état→couleur centralisé unique (`stateColors`/`StateBadge`) ; composant `OperatorQueue` unique étendu par slots/props, jamais réimplémenté ; annonces a11y via canal `aria-live` centralisé ; plancher WCAG 2.2 AA ; pagination sur toute donnée financière.

### Testing Rules

- Backend : ~238 tests, `./mvnw test` — **Docker requis** (Testcontainers : Postgres réel + MinIO réels). H2 réservé aux slices rapides (state machine). Les preuves de concurrence (nonce, retrait, solde) se font sur **base réelle**, pas sur mocks.
- Tests dans le même paquet `com.zlecaf.escrow`, suffixe `*Test.java` ; intégration = `*IntegrationTest.java`.
- Frontend : ~170 tests Vitest (`npm run test`), env jsdom, `testTimeout: 30000` (Blobs multi-Mo dans le structured clone), tests colocalisés en `__tests__/`.
- **Piège Blob prouvé (ne pas « nettoyer » `vitest.setup.js`)** : fake-indexeddb aplatit silencieusement le Blob jsdom en `{}` → swap `globalThis.Blob/File` depuis `node:buffer`, puis `FormData` d'`undici` importé **dynamiquement APRÈS** le swap (un import statique hoisté capturerait le Blob jsdom et casserait le brand-check). Ordre et dynamisme de ces imports sont load-bearing.
- `vitest.config.js` séparé de `vite.config.js` volontairement (pas de plugin PWA dans la suite unitaire) — ne pas fusionner.
- Toute contrainte élevée en AD (unicité sous concurrence, décision unique, équilibre comptable, solde jamais négatif) doit être **prouvée par test**, idéalement de concurrence sur base réelle.

### Code Quality & Style Rules

- Entités `PascalCase` → tables `snake_case_plural` ; services `XxxService` ; migrations `V<n>__desc.sql` séquentielles (prochaine = V6).
- Javadoc/commentaires existants expliquent le **pourquoi** (décisions, pièges) — maintenir ce style ; ne pas supprimer les commentaires load-bearing (pom, vitest.setup, application.yml).
- Limites métier arbitrées **dans le service** (ex. 10 Mo/fichier), le conteneur ne porte qu'un garde-fou supérieur (multipart 15/60 Mo) — ne pas aligner les deux.
- Append-only/WORM : aucun DELETE/UPDATE applicatif sur preuves, messages, `audit_logs`, écritures comptables ; correction = contre-passation via `LedgerService`, retrait = logique (AD-4/AD-25).
- Configuration produit versionnée append-only ; conditions **copiées sur la transaction à la création** (AD-24), jamais de lookup à la volée.

### Development Workflow Rules

- Branche courante `develop`, PRs vers `main` ; documents et commits descriptifs en pratique préfixés par la story (`Story X.Y: …`). Communication et documents en **français** (config `_bmad/bmm/config.yaml`), code/enums/API en anglais.
- Artefacts de planification sous `_bmad-output/planning-artifacts/` ; ce fichier et le spine sont à relire avant toute story.
- Stack locale docker-compose (`infra/`) ; **conflits de ports connus** : nginx local :8080 et postgres local :5432 peuvent entrer en collision avec compose ; Adminer :8081, pgAdmin :5050, Swagger :8080/swagger-ui.html. Attention aux **builds de conteneurs périmés** lors des E2E.
- SBOM CycloneDX : plugin invoqué explicitement par la CI (`cyclonedx:makeAggregateBom`), pas lié au lifecycle — ne pas l'attacher au build local.

### Critical Don't-Miss Rules

- **JAMAIS** de mutation de solde hors `LedgerService` ; jamais de colonne solde autoritative (solde = dérivé des écritures) ; idempotence par référence métier unique ; verrou pessimiste `SELECT … FOR UPDATE` sur la ligne wallet (AD-18).
- **JAMAIS** de transition d'état hors matrice `EscrowStateMachine` ; opération financière = écritures + transition + audit dans **une seule transaction DB**, rollback complet en cas d'échec.
- **JAMAIS** de donnée carte côté plateforme : parcours hébergé PSP uniquement ; webhooks PSP signés + idempotents par référence PSP (rejeu = aucun effet, journalisé).
- Rôles `ADMIN`/`ARBITRATOR` jamais attribuables à l'inscription (whitelist Story 1.1) ; acteur `SYSTEM` réservé aux jobs `scheduler/`.
- HMAC partenaire entrant : canonical string + fenêtre ±5 min + nonce anti-replay par key-id (store distinct du secret webhook sortant) ; secret ≥ 32 octets (CHECK en base) ; purge de nonces planifiée et bornée.
- Chiffrement au repos obligatoire pour toute nouvelle donnée sensible (coordonnées bancaires, TOTP, screening AML…) — statuer explicitement avant de persister (AD-29).
- Dépôts manuels et payouts : décision d'approbation opérateur **préalable, distincte, persistée et unique** (prouvée sous concurrence) — jamais approbation+exécution en un geste (AD-28).
- Uploads : validation contenu par Tika (magic bytes), téléchargements servis en `attachment` ; cleanup storage sur rollback (port `EvidenceStorage`, AD-6/AD-7).
- Réponses transitoires vs permanentes côté client réconciliées via le champ `code` de l'enveloppe d'erreur (AD-10) — ne pas classifier sur le status HTTP seul ni sur le message.
- Toute contradiction entre une story et un AD du spine = **conflit à remonter**, pas à résoudre localement.

---

## Usage Guidelines

**Pour les agents IA :**

- Lire ce fichier avant d'implémenter le moindre code
- Suivre TOUTES les règles exactement comme documentées
- En cas de doute, préférer l'option la plus restrictive ; en cas de conflit, le spine architecture fait foi
- Mettre à jour ce fichier si de nouveaux patterns émergent

**Pour les humains :**

- Garder ce fichier lean et centré sur les besoins des agents
- Mettre à jour à chaque changement du stack technique
- Revoir trimestriellement pour retirer les règles devenues obsolètes ou évidentes

Last Updated: 2026-07-25
