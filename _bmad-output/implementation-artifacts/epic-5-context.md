# Epic 5 Context: Durcissement transverse (contrat d'API preuves)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal
Epic transverse, exécuté entre l'Epic 2 et l'Epic 3 (l'ordre d'identifiant ne suit pas l'ordre d'exécution). Il n'apporte aucune valeur utilisateur nouvelle : il solde le backlog de reports différés accumulé pendant les Epics 1 et 2, en bornant et durcissant le contrat d'API des preuves et en refermant des trous de couverture. L'objectif est de rendre l'API robuste et cohérente sous volume et sous erreur avant que l'Epic 3 (partenaire) et l'Epic 4 (offline / volume) n'amplifient la charge.

## Stories
- Story 5.1 : Durcir le contrat d'API des preuves (bundle de reports)

## Requirements & Constraints
Story 5.1 regroupe les six reports encore ouverts du ledger de travail différé (les autres sont RÉSOLU ou ACCEPTÉ). Chaque contrainte est décrite par sa finalité :

- **Bornage des listes** — La liste des preuves et la lecture du trail d'audit du détail transaction matérialisent aujourd'hui toutes les lignes sans limite. Appliquer un plafond ou une pagination `Pageable` de façon **cohérente sur les deux endpoints**, pour un contrat de liste uniforme sur la plateforme (décision à prendre de concert, pas endpoint par endpoint).
- **Plafond du nombre de fichiers** — `files[]` est aujourd'hui borné seulement par la limite de requête (~60 Mo) et tous les octets sont bufférisés en mémoire avant validation. Rejeter en `400`, avant toute bufférisation massive, toute requête dépassant le plafond (≤ 20 fichiers, limite souple indicative héritée d'une hypothèse produit). S'applique au dépôt simple **et** à l'ouverture composite de litige (même contrat multipart partagé).
- **Audit du téléchargement** — Le chemin de lecture du binaire n'écrit aucune entrée d'audit alors que le dépôt en écrit une par pièce : trou de non-répudiation. Écrire un audit `EVIDENCE_DOWNLOADED` (acteur, rôle, id de pièce). Attention : émettre un audit depuis une transaction `readOnly` change la sémantique de la lecture.
- **Mapping des erreurs de stockage** — Toute défaillance du stockage objet autre que « objet absent » (MinIO indisponible, timeout, `S3Exception`, `403` de politique de bucket) remonte non capturée et produit une page whitelabel `500`. La mapper dans l'enveloppe d'erreur JSON de la plateforme (ex. `502`), de concert pour le dépôt et le téléchargement.
- **Exposition de l'attribution du retrait** — `EvidenceDto` s'arrête à `createdAt` : aucune réponse ne révèle qui a retiré une pièce ni quand. Exposer `withdrawnAt` et `withdrawnByUserId` (les valeurs existent déjà en base et en audit).
- **Cohérence du store PWA au retrait** — Voir section UX ci-dessous.

Chaque durcissement s'accompagne de nouveaux tests : plafond de liste, rejet `files[]` au-delà du plafond, audit de download, mapping d'erreur stockage, nouveaux champs du DTO ; toute la suite (`mvn test` + front) doit rester verte.

## Technical Decisions
- **Enveloppe d'erreur unique** — Contrat `{timestamp, status, error, message}` servi par `GlobalExceptionHandler` + `ApiExceptions` (404 / 400 / 403 / 409). Convention : lever les exceptions applicatives existantes, ne pas multiplier les handlers ; les limites d'infrastructure (multipart trop gros, etc.) sont mappées vers cette même enveloppe, jamais vers un code brut (413/500). Les échecs stockage/infra doivent y entrer sous un 5xx propre.
- **Audit** — Writer unique `AuditService`, table `audit_logs`, `payload` JSONB schemaless (pas de colonne `action_type`). Deux propagations : `recordSuccess` en `MANDATORY` (commit atomique avec l'opération), `recordFailure` en `REQUIRES_NEW` (trace durable même en cas de rollback). Le champ `action` porte la valeur (`EVIDENCE_ADDED`, `EVIDENCE_WITHDRAWN`, et à ajouter `EVIDENCE_DOWNLOADED`).
- **DTOs** — Records imbriqués dans un holder `EvidenceDtos`, moule `XxxRequest` / `XxxDto` avec fabrique `static from()`. Les nouveaux champs de retrait suivent ce moule.
- **Port de stockage** — Le binaire n'est manipulé que via l'interface `EvidenceStorage` (`store` / `load` / `delete`, clé opaque `{transaction_id}/{uuid}`). Seul l'adaptateur `MinioEvidenceStorage` connaît S3/MinIO ; aucun code de service/web ne doit référencer le SDK S3. Implémentation POC : MinIO S3-compatible, bucket `escrow-evidence`, endpoint et identifiants injectés au backend via variables d'environnement. Le mapping d'erreur doit donc traduire les exceptions du SDK (`S3Exception`, `SdkClientException`) au niveau service/handler sans faire fuiter de détail interne.
- **Règle de dépendance** — `web → service → {repository, AuditService, port}` ; jamais de dépendance remontante.

## UX & Interaction Patterns
Le store `evidence` de la PWA remplace tout `items` sous garde d'un jeton de séquence `loadSeq` lors d'un `loadEvidence`, mais le retrait de pièce mute `items` en place **sans** participer à ce jeton. Un `loadEvidence` en vol (montage ou `escrow:sync` concurrent) résolu après le commit du retrait peut donc réafficher brièvement la pièce en `ACTIVE`. Faire participer le retrait au jeton `loadSeq` (ou refetch ciblé), en cohérence avec une politique unique de cohérence optimiste du store (dépôt + retrait). Fenêtre étroite et auto-corrigée, serveur toujours cohérent — durcissement d'UX, pas correction de perte de données.

## Cross-Story Dependencies
- **Séquencement** — Epic 5 précède délibérément l'Epic 3 (partenaire, HMAC) et l'Epic 4 (offline / rejeu de volume) : ces épics amplifient la charge sur exactement les endpoints ici bornés (liste, dépôt multipart, stockage objet). Le durcissement doit être en place avant.
- **Contrat multipart partagé** — Le plafond `files[]` touche une surface commune au dépôt simple, à l'ouverture composite de litige (Epic 2) et au rejeu offline (Epic 4) : les noms de champs (`files`, `comment`, `clientCapturedAt`) et les bornes sont figés et identiques sur tous ces chemins.
