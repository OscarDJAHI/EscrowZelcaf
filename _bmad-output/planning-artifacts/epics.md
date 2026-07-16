---
stepsCompleted: ["step-01-validate-prerequisites", "step-02-design-epics", "step-03-create-stories"]
inputDocuments:
  - _bmad-output/planning-artifacts/prds/prd-Escrow-2026-07-15/prd.md
  - _bmad-output/planning-artifacts/prds/prd-Escrow-2026-07-15/addendum.md
  - _bmad-output/planning-artifacts/architecture/architecture-Escrow-2026-07-15/ARCHITECTURE-SPINE.md
  - Docs/backend_schema_escrow.md
  - Docs/tech_stack_escrow.md
---

# Escrow — Découpage en Epics (Evidence Upload)

## Overview

Ce document décompose les exigences du PRD « Dépôt de preuves de transaction & de litige » et les 11 décisions d'architecture (AD) en stories implémentables, organisées par valeur utilisateur. Feature greffée sur la plateforme Escrow B2B ZLECAf existante (brownfield).

## Requirements Inventory

### Functional Requirements

**A. Dépôt de pièces**
- **FR-1** — Un utilisateur autorisé (acheteur, vendeur ou arbitre de la transaction) peut attacher 1..N pièces dès l'état `FUNDS_LOCKED` (jusqu'au verrou FR-8).
- **FR-2** — Chaque pièce porte un commentaire du déposant ; obligatoire (≥ 10 caractères) à l'ouverture d'un litige, optionnel ensuite.
- **FR-3** — Seuls JPG, PNG, PDF acceptés ; contrôle sur le type réel (content-sniffing), pas l'extension.
- **FR-4** — Taille max 10 Mo/fichier ; fichier vide (0 octet) ou au-delà rejeté.
- **FR-5** — Un partenaire logistique dépose via endpoint API dédié (HMAC-SHA256) ; uniquement sur les transactions de sa propre société (sinon 403).

**B. Litige**
- **FR-6** — `OPEN_DISPUTE` exige au moins une pièce : pas de litige « à vide ».
- **FR-7** — Tant que le litige n'est pas tranché, acheteur, vendeur et arbitre peuvent déposer.
- **FR-8** — Dépôt/retrait verrouillés dès que `state ∈ {RELEASED, REFUNDED}` (verrou basé sur l'état).

**C. Consultation & visibilité**
- **FR-9** — Visibilité contradictoire : acheteur, vendeur et arbitre voient toutes les pièces.
- **FR-10** — Pièces listées par ordre chronologique (déposant, date/heure, type, taille, commentaire, statut).
- **FR-11** — Téléchargement du fichier original avec contrôle d'appartenance (pièce ∈ transaction ET demandeur partie prenante — anti-IDOR).

**D. Cycle de vie & intégrité**
- **FR-12** — Retrait logique de sa propre pièce uniquement ; jamais celle d'un tiers ; refus si passe sous le plancher FR-6 en `DISPUTED`.
- **FR-13** — Chaque dépôt/retrait génère une entrée immuable dans `audit_logs`.
- **FR-14** — Rétention illimitée (POC).

**E. Hors-ligne (PWA)**
- **FR-15** — Ouverture de litige + preuves hors-ligne, mises en file atomiquement, synchronisées à la reconnexion, avec affichage optimiste de `DISPUTED`.
- **FR-16** — Réconciliation à la synchro : rejet serveur ⇒ conserver l'entrée + binaire, annuler l'optimiste, notifier avec motif + état réel ; aucun fichier perdu silencieusement.

### NonFunctional Requirements

- **NFR-1 (Stockage)** — Stockage objet MinIO (S3-compatible) dès le POC, bucket configurable, derrière la couche isolée `EvidenceStorage` (clé opaque).
- **NFR-2 (Sécurité)** — Validation serveur du type réel + taille ; assainissement des noms + prévention path traversal (nom généré) ; accès binaire contrôlé par autorisation ; restitution `Content-Disposition: attachment`.
- **NFR-3 (Intégrité & audit)** — Immuabilité (retrait logique) + cohérence transactionnelle ACID avec l'écriture d'audit.
- **NFR-4 (Résilience hors-ligne)** — Fonctionne sur réseau instable (file d'attente, payload maîtrisé).
- **NFR-5 (Partenaire)** — HMAC-SHA256 clé dédiée par partenaire (key-id), signature couvrant corps + timestamp, nonce anti-rejeu.
- **NFR-6 (Absence de scan malveillant)** — Aucun scan antivirus ; risque explicitement accepté (POC).

### Additional Requirements

_Source : `ARCHITECTURE-SPINE.md` (11 AD) — exigences techniques qui contraignent l'implémentation. **Contexte brownfield : aucun starter template** ; la plateforme Spring Boot + Vue existe déjà. Voici les travaux d'infrastructure/technique induits._

- **AR-1 (Setup infra)** — Ajouter un service **MinIO** à `infra/docker-compose.yml` (volume + bucket `escrow-evidence` provisionné, credentials via `${ENV:default}`). *(AD-6, NFR-1)*
- **AR-2 (Migration)** — Migration Flyway **`V2__evidence_files.sql`** (idiomes de V1 : BIGSERIAL PK, TIMESTAMPTZ, FK, index `(transaction_id, created_at)`). Prévoir aussi le stockage des **clés HMAC entrantes** et des **nonces** partenaire. *(AD-5, AD-8, AD-11)*
- **AR-3 (Port stockage)** — Interface `EvidenceStorage` (clé opaque `{tx}/{uuid}`) + adaptateur `MinioEvidenceStorage` (AWS SDK Java v2 S3 `2.47.6`). *(AD-6)*
- **AR-4 (Endpoint composite atomique)** — `POST /escrow/{id}/dispute` (multipart `files[]` 1..N + `comment` + `clientCapturedAt`) ouvre le litige ET attache la/les pièce(s) dans une seule transaction ; test de rollback exigé. *(AD-1)*
- **AR-5 (Fenêtre & verrou d'état)** — Garde serveur : dépôt/retrait ssi `state ∈ {FUNDS_LOCKED, SHIPPED, DISPUTED}` ; étend `EscrowStateMachineTest`. *(AD-2)*
- **AR-6 (Contrôle d'appartenance)** — Réutiliser le contrôle unique `resolveRole`/`authorizeView` sur tous les endpoints ; le download vérifie `{eid}.transaction_id == {id}`. *(AD-3)*
- **AR-7 (Retrait logique + plancher)** — `ACTIVE → WITHDRAWN`, sa propre pièce seulement, refus 409 si total actif toutes parties < 1 en `DISPUTED`. *(AD-4)*
- **AR-8 (Audit)** — Écriture `audit_logs` en propagation MANDATORY, payload JSONB enrichi (`EVIDENCE_ADDED`/`EVIDENCE_WITHDRAWN`, `evidenceId`, `sha256`, heure client). *(AD-5)*
- **AR-9 (Validation ingestion)** — Content-sniffing **Apache Tika 3.3.1** + confront. extension/Content-Type ; taille arbitrée par le service (multipart Spring > 10 Mo, `MaxUploadSizeExceededException` → 400) ; `Content-Disposition: attachment`. *(AD-7, NFR-2)*
- **AR-10 (HMAC partenaire durci)** — `PartnerEvidenceController` : clé dédiée entrante, signature couvrant corps + timestamp, fenêtre ±5 min, nonce unique par key-id (rétention ≥ fenêtre), vérif société impliquée. *(AD-8, NFR-5)*
- **AR-11 (Offline IndexedDB)** — Refonte du store `offlineQueue` (localStorage → IndexedDB) pour porter des `Blob` ≤ 10 Mo ; une entrée atomique portant action + binaire(s), rejeu multipart. *(AD-9)*
- **AR-12 (Réconciliation synchro)** — Classement de l'échec par code applicatif (transitoire vs permanent), annulation optimiste, conservation du binaire, notification. Test front du store. *(AD-10)*
- **AR-13 (Contrat multipart figé)** — Noms de champs partagés `files[]` / `comment` / `clientCapturedAt` identiques sur composite / plain / partenaire / rejeu offline. *(convention)*

### UX Design Requirements

_Aucun document UX dédié (`DESIGN.md`/`EXPERIENCE.md`) n'existe. Les parcours PWA (UJ-1..4) sont décrits narrativement dans le PRD §4. Travaux front-end **dérivés** des parcours + architecture (à préciser avec l'utilisateur, pas issus d'un contrat UX formel) :_

- **UX-DR1** — Composant de **dépôt de pièces** (sélection fichier, aperçu type/taille, champ commentaire) sur le détail transaction, disponible dès `FUNDS_LOCKED`.
- **UX-DR2** — **Liste chronologique des preuves** (déposant, horodatage, type, taille, statut actif/retiré, action télécharger/retirer selon droits) — vue contradictoire.
- **UX-DR3** — Parcours **« Ouvrir un litige »** exigeant ≥ 1 pièce + commentaire (validation front miroir de FR-6/FR-2), avec état optimiste `DISPUTED`.
- **UX-DR4** — Intégration **hors-ligne** : bannière/indicateur de pièces en attente, notification de réconciliation en cas de rejet serveur (FR-16).
- **UX-DR5** — Écran de **récupération après rejet définitif** : preuves conservées et réutilisables, motif du rejet, état réel de la transaction, parcours de sortie sans perte silencieuse (FR-16).

### FR Coverage Map

- **FR-1** : Epic 1 — dépôt de pièces dès `FUNDS_LOCKED`
- **FR-2** : Epic 1 (commentaire optionnel post-dépôt) + Epic 2 (obligatoire ≥10 car. à l'ouverture)
- **FR-3** : Epic 1 — content-sniffing des types autorisés
- **FR-4** : Epic 1 — bornes de taille (0 < s ≤ 10 Mo)
- **FR-5** : Epic 3 — dépôt partenaire HMAC restreint à sa société
- **FR-6** : Epic 2 — preuve obligatoire à l'ouverture (endpoint composite)
- **FR-7** : Epic 2 — dépôt tant que le litige n'est pas tranché
- **FR-8** : Epic 2 — verrou à l'état terminal
- **FR-9** : Epic 1 — visibilité contradictoire
- **FR-10** : Epic 1 — liste chronologique
- **FR-11** : Epic 1 — téléchargement contrôlé (anti-IDOR)
- **FR-12** : Epic 2 — retrait logique + plancher
- **FR-13** : Epic 1 — audit immuable de chaque dépôt/retrait
- **FR-14** : Epic 1 — rétention illimitée (POC)
- **FR-15** : Epic 4 — ouverture + dépôt hors-ligne atomiques
- **FR-16** : Epic 4 — réconciliation à la synchro

_NFR : NFR-1/2/3 → Epic 1 · NFR-5/6 → Epic 3 · NFR-4 → Epic 4._

## Epic List

### Epic 1 : Socle — dépôt & consultation contradictoire des preuves
Toute partie d'une transaction peut attacher des pièces (dès `FUNDS_LOCKED`) et consulter/télécharger **toutes** les pièces dans un dossier contradictoire tracé. Cet epic embarque l'infrastructure d'ingestion (service MinIO + bucket, migration `V2__evidence_files.sql`, port `EvidenceStorage`, validation Apache Tika, écriture d'audit, contrôle d'appartenance anti-IDOR) car c'est ici qu'elle est requise en premier.
**FRs covered:** FR-1, FR-2 (post-dépôt), FR-3, FR-4, FR-9, FR-10, FR-11, FR-13, FR-14 — **NFR:** NFR-1, NFR-2, NFR-3 — **AR:** AR-1, AR-2, AR-3, AR-6, AR-8, AR-9, AR-13 — **UX:** UX-DR1, UX-DR2

### Epic 2 : Litige adossé à la preuve
Ouvrir un litige exige au moins une preuve, garantie par un endpoint composite atomique (`POST /escrow/{id}/dispute`). Les parties se défendent en déposant tant que le litige n'est pas tranché, peuvent retirer leur propre pièce (retrait logique, sans passer sous le plancher obligatoire), et tout dépôt/retrait se verrouille à l'état terminal.
**FRs covered:** FR-2 (obligatoire à l'ouverture), FR-6, FR-7, FR-8, FR-12 — **AR:** AR-4, AR-5, AR-7 — **UX:** UX-DR3
**Dépend de :** Epic 1.

### Epic 3 : Preuve neutre du transporteur (partenaire machine)
Un partenaire logistique pousse des preuves horodatées de l'état de la marchandise via un endpoint API signé (HMAC-SHA256, clé dédiée, fenêtre ±5 min, nonce anti-rejeu par key-id), restreint aux transactions de sa société. Les pièces deviennent visibles de toutes les parties — potentiellement avant même l'ouverture d'un litige.
**FRs covered:** FR-5 — **NFR:** NFR-5, NFR-6 — **AR:** AR-10
**Dépend de :** Epic 1 (table, port, validation, audit).

### Epic 4 : Dépôt & litige résilients hors-ligne (PWA)
Une partie peut ouvrir un litige et déposer des preuves sans réseau : l'action et son/ses binaire(s) sont mis en file atomiquement (IndexedDB), l'état `DISPUTED` s'affiche de façon optimiste, puis tout est rejoué en multipart à la reconnexion. En cas de rejet serveur, la réconciliation conserve le fichier, annule l'optimiste et notifie l'utilisateur — aucune perte silencieuse.
**FRs covered:** FR-15, FR-16 — **NFR:** NFR-4 — **AR:** AR-11, AR-12, AR-13 — **UX:** UX-DR4, UX-DR5
**Dépend de :** Epic 1 + Epic 2 (endpoint composite).

---

## Epic 1 : Socle — dépôt & consultation contradictoire des preuves

Toute partie d'une transaction peut attacher des pièces (dès `FUNDS_LOCKED`) et consulter/télécharger toutes les pièces dans un dossier contradictoire tracé. Cet epic embarque l'infrastructure d'ingestion.

### Story 1.1 : Fondations de stockage des preuves

As a développeur de la plateforme,
I want une table `evidence_files`, un port de stockage isolé et un backend MinIO opérationnel,
So that toute preuve puisse être persistée durablement et relue par une clé opaque, sans coupler le reste du code au stockage.

**Acceptance Criteria:**

**Given** la base migrée par Flyway
**When** la migration `V2__evidence_files.sql` s'exécute
**Then** la table `evidence_files` existe avec les colonnes de l'ERD (id, transaction_id, uploaded_by_user_id nullable, uploader_type, partner_company_id nullable, original_filename, mime_type, size_bytes, storage_key, comment, status, created_at, withdrawn_at, withdrawn_by_user_id)
**And** un index `(transaction_id, created_at)` existe (FR-10)
**And** les idiomes de `V1` sont respectés (BIGSERIAL PK, TIMESTAMPTZ, FK `REFERENCES`).

**Given** l'interface `EvidenceStorage` (`store(bytes, contentType) → storageKey`, `load(storageKey) → stream`) (AD-6)
**When** l'adaptateur `MinioEvidenceStorage` (AWS SDK Java v2 `s3` 2.47.6) reçoit un binaire
**Then** il l'écrit sous une clé opaque `{transaction_id}/{uuid}`, jamais dérivée d'un nom fourni
**And** un **test automatisé** de round-trip (`store` puis `load` sur un MinIO de test / Testcontainers) prouve que le binaire restitué est identique octet pour octet — critère observable, pas « ça compile ».

**Given** `infra/docker-compose.yml`
**When** la stack démarre
**Then** un service `minio` tourne avec un volume persistant et le bucket `escrow-evidence` provisionné
**And** l'endpoint/credentials MinIO sont injectés au backend via `${ENV:default}` (aucun secret en dur).

**Given** aucune purge n'est planifiée
**When** le temps passe
**Then** les pièces restent indéfiniment (rétention illimitée POC, FR-14).

### Story 1.2 : Déposer une pièce sur une transaction

As a acheteur, vendeur ou arbitre d'une transaction,
I want attacher un fichier justificatif avec un commentaire optionnel,
So that je documente l'état de la transaction et prépare un éventuel litige.

**Acceptance Criteria:**

**Given** une transaction dont je suis partie prenante, à l'état `FUNDS_LOCKED`, `SHIPPED` ou `DISPUTED`
**When** j'appelle `POST /api/v1/escrow/{id}/evidence` (multipart : `files[]`, `comment` optionnel, `clientCapturedAt` optionnel) avec un JPG/PNG/PDF valide ≤ 10 Mo
**Then** la pièce est stockée via `EvidenceStorage`, une ligne `evidence_files` (`status=ACTIVE`) est créée avec `created_at` = heure serveur (AD-11)
**And** une entrée `audit_logs` est écrite dans la même transaction (propagation MANDATORY, payload `action=EVIDENCE_ADDED`, `evidenceId`, `sha256`) (FR-13, AD-5)
**And** la réponse est `201 Created` avec les métadonnées de la pièce.

**Given** un fichier dont le type réel (content-sniffing Apache Tika) n'est pas JPG/PNG/PDF, ou dont l'extension/Content-Type déclaré est incohérent
**When** je tente le dépôt
**Then** le dépôt est rejeté `400` avec un message explicite (FR-3, NFR-2).

**Given** un fichier de 0 octet ou > 10 485 760 octets
**When** je tente le dépôt
**Then** il est rejeté `400` ; la limite est arbitrée par le service (multipart Spring réglé > 10 Mo, `MaxUploadSizeExceededException` mappée → 400) (FR-4, AD-7).

**Given** une transaction dont je ne suis pas partie prenante, ou à l'état `RELEASED`/`REFUNDED`
**When** je tente le dépôt
**Then** je reçois `403` (non partie, AD-3) ou `409`/`400` (fenêtre fermée, AD-2) respectivement.

### Story 1.3 : Consulter la liste chronologique des preuves

As a acheteur, vendeur ou arbitre d'une transaction,
I want voir toutes les pièces de la transaction dans l'ordre chronologique,
So that je dispose du dossier contradictoire complet.

**Acceptance Criteria:**

**Given** une transaction dont je suis partie prenante
**When** j'appelle `GET /api/v1/escrow/{id}/evidence`
**Then** je reçois toutes les pièces (de toutes les parties, visibilité contradictoire — FR-9), triées par `created_at` croissant (FR-10)
**And** chaque item porte déposant, type de déposant, date/heure, type MIME, taille, commentaire et statut (`ACTIVE`/`WITHDRAWN`).

**Given** une transaction dont je ne suis pas partie prenante
**When** j'appelle l'endpoint de liste
**Then** je reçois `403` (AD-3).

**Given** une pièce retirée
**When** je consulte la liste
**Then** elle apparaît avec le statut `WITHDRAWN` (jamais masquée — AD-4).

### Story 1.4 : Télécharger une preuve en toute sécurité

As a acheteur, vendeur ou arbitre d'une transaction,
I want télécharger le fichier original d'une pièce,
So that je puisse l'examiner en détail.

**Acceptance Criteria:**

**Given** une pièce `{eid}` appartenant à la transaction `{id}` dont je suis partie prenante
**When** j'appelle `GET /api/v1/escrow/{id}/evidence/{eid}/download`
**Then** le binaire est servi depuis `EvidenceStorage`
**And** l'en-tête est `Content-Disposition: attachment` (jamais inline — anti-XSS stocké, NFR-2).

**Given** une pièce `{eid}` qui n'appartient PAS à la transaction `{id}` de l'URL
**When** je tente le téléchargement
**Then** je reçois `404`/`403` (vérification `{eid}.transaction_id == {id}` — anti-IDOR, FR-11, AD-3).

**Given** une transaction dont je ne suis pas partie prenante
**When** je tente le téléchargement d'une de ses pièces
**Then** je reçois `403`.

### Story 1.5 : Interface PWA — dépôt & liste des preuves

As a utilisateur de la PWA,
I want déposer une pièce et voir la liste des preuves depuis le détail d'une transaction,
So that j'utilise la fonctionnalité sans appeler l'API à la main.

**Acceptance Criteria:**

**Given** l'écran de détail d'une transaction à l'état `FUNDS_LOCKED`/`SHIPPED`/`DISPUTED`
**When** j'ouvre le composant de dépôt
**Then** je peux sélectionner un fichier, voir son type/taille, saisir un commentaire optionnel, et soumettre (UX-DR1)
**And** un type non autorisé ou une taille > 10 Mo est signalé côté client avant l'envoi (le serveur reste l'autorité).

**Given** une transaction avec des pièces
**When** j'affiche le détail
**Then** la liste chronologique des preuves s'affiche (déposant, horodatage, type, taille, statut) avec une action télécharger, et retirer si je suis le déposant (UX-DR2).

---

## Epic 2 : Litige adossé à la preuve

Ouvrir un litige exige au moins une preuve (endpoint composite atomique) ; les parties se défendent, retirent leur propre pièce avec un plancher, et le dossier se verrouille à l'état terminal.

### Story 2.1 : Ouvrir un litige avec preuve obligatoire (atomique)

As a acheteur ou vendeur d'une transaction,
I want ouvrir un litige en joignant obligatoirement au moins une preuve et un commentaire,
So that aucun litige ne puisse exister « à vide » et l'arbitre ait toujours un dossier.

**Acceptance Criteria:**

**Given** une transaction à l'état `FUNDS_LOCKED` ou `SHIPPED` dont je suis partie prenante
**When** j'appelle `POST /api/v1/escrow/{id}/dispute` (multipart : `files[]` 1..N, `comment` ≥ 10 caractères, `clientCapturedAt` optionnel)
**Then** dans une seule transaction (`@Transactional`), l'état passe à `DISPUTED` ET la/les pièce(s) sont attachées (`status=ACTIVE`) (FR-6, AD-1)
**And** l'audit enregistre la transition ET l'ajout de preuve.

**Given** une requête d'ouverture sans fichier, ou avec un commentaire < 10 caractères
**When** je l'envoie
**Then** elle est rejetée `400` (FR-6, FR-2) et aucune transition n'a lieu.

**Given** que la persistance de la pièce échoue (ex. MinIO indisponible)
**When** l'ouverture composite s'exécute
**Then** toute la transaction est annulée : pas de passage à `DISPUTED`, aucune ligne `evidence_files` créée (rollback atomique, test exigé — AD-1).

**Given** une transaction déjà `DISPUTED`, `RELEASED` ou `REFUNDED`
**When** je tente d'ouvrir un litige
**Then** je reçois `409` (transition invalide selon la machine à états).

**Given** l'endpoint composite et l'endpoint de dépôt simple (Story 1.2)
**When** un fichier est joint à l'ouverture
**Then** la validation (content-sniffing Tika, bornes de taille, clé opaque, écriture d'audit) **réutilise la même logique de service que Story 1.2** — aucune règle de validation dupliquée
**And** les mêmes fichiers invalides (mauvais type, 0 octet, > 10 Mo) sont rejetés `400` de façon identique aux deux endpoints (AR-13).

### Story 2.2 : Verrouiller dépôt & retrait aux états terminaux

As a plateforme,
I want interdire tout dépôt et tout retrait dès qu'une transaction est `RELEASED` ou `REFUNDED`,
So that le dossier de preuves reste figé après résolution.

**Acceptance Criteria:**

**Given** une transaction à l'état `RELEASED` ou `REFUNDED`
**When** une partie tente un dépôt (`POST /evidence`) ou un retrait (`/withdraw`)
**Then** l'opération est refusée (verrou fondé sur l'état, pas sur l'événement — FR-8, AD-2)
**And** le verrou s'applique aussi bien après un arbitrage (`RESOLVE_*`) qu'après une libération normale (`DELIVERY_CONFIRMED`).

**Given** une transaction encore `DISPUTED`
**When** une partie (acheteur, vendeur, arbitre) dépose une pièce
**Then** le dépôt est accepté (FR-7 : dépôt possible tant que le litige n'est pas tranché).

### Story 2.3 : Retirer sa propre pièce avec plancher de preuve

As a déposant d'une pièce,
I want marquer ma propre pièce comme retirée sans la supprimer,
So that je corrige un dépôt tout en préservant la traçabilité et sans vider le litige de ses preuves.

**Acceptance Criteria:**

**Given** une pièce `ACTIVE` que j'ai déposée
**When** j'appelle `POST /api/v1/escrow/{id}/evidence/{eid}/withdraw`
**Then** son `status` passe à `WITHDRAWN`, `withdrawn_at`/`withdrawn_by_user_id` sont renseignés, aucune suppression physique (AD-4)
**And** une entrée `audit_logs` `EVIDENCE_WITHDRAWN` est écrite (FR-13).

**Given** une pièce déposée par un tiers
**When** je tente de la retirer
**Then** je reçois `403` (on ne retire jamais la pièce d'autrui — FR-12).

**Given** une transaction `DISPUTED` où ma pièce est la dernière `ACTIVE` (toutes parties confondues)
**When** je tente de la retirer
**Then** je reçois `409` : le retrait ferait passer le total sous le plancher FR-6 (AD-4).

**Given** une transaction `DISPUTED` avec exactement deux pièces `ACTIVE`, retirées **simultanément** (deux appareils / deux requêtes concurrentes)
**When** les deux retraits s'exécutent en parallèle
**Then** au plus un réussit ; le second est refusé `409` — le plancher FR-6 tient sous la course (verrou `@Version` / vérification transactionnelle du compte, **test de concurrence exigé**, pas un « peut-être »).

### Story 2.4 : Interface PWA — ouvrir un litige & fil contradictoire

As a utilisateur de la PWA,
I want ouvrir un litige en joignant preuve + commentaire, et suivre le fil des pièces des deux parties,
So that je conduise le litige sans quitter l'application.

**Acceptance Criteria:**

**Given** le détail d'une transaction `FUNDS_LOCKED`/`SHIPPED`
**When** je lance « Ouvrir un litige »
**Then** le formulaire exige au moins un fichier ET un commentaire ≥ 10 caractères avant de permettre la soumission (miroir de FR-6/FR-2, UX-DR3)
**And** après succès, l'état affiché passe à `DISPUTED`.

**Given** une transaction `DISPUTED`
**When** je consulte le détail
**Then** je vois le fil chronologique contradictoire des pièces des deux parties et de l'arbitre, avec la possibilité de déposer une contre-preuve.

---

## Epic 3 : Preuve neutre du transporteur (partenaire machine)

Un partenaire logistique pousse des preuves horodatées via une API signée, restreinte à ses transactions, visibles de toutes les parties.

### Story 3.1 : Provisionner les clés HMAC entrantes & le magasin de nonces

As a plateforme,
I want des clés HMAC dédiées entrantes par partenaire et un stockage de nonces,
So that les dépôts partenaire puissent être authentifiés et protégés du rejeu, sans réutiliser le secret des webhooks sortants.

**Acceptance Criteria:**

**Given** la migration Flyway du partenaire
**When** elle s'exécute
**Then** un stockage des clés HMAC entrantes (par `key-id`, lié à une `companies`, distinct du `secret_key` sortant de `webhook_subscriptions`) et un stockage des nonces `(key_id, nonce, seen_at)` existent (AD-8, NFR-5).

**Given** un partenaire logistique référencé
**When** une clé entrante lui est provisionnée
**Then** elle porte un `key-id` résolvable vers sa `companies`, et n'est jamais la clé sortante.

**Given** un nonce déjà stocké pour un `key-id`
**When** on interroge le magasin
**Then** l'unicité est scindée par `key-id` (clé composite `(key_id, nonce)`) et la rétention est ≥ à la fenêtre de validité (purge < 5 min interdite) (AD-8).

### Story 3.2 : Déposer une preuve partenaire via API signée

As a partenaire logistique (machine),
I want pousser une preuve horodatée sur une transaction que je sers, via un appel signé,
So that je fournisse une preuve neutre de l'état de la marchandise.

**Acceptance Criteria:**

**Given** un appel `POST /api/v1/partner/escrow/{id}/evidence` avec en-têtes `key-id`, `X-Escrow-Signature`, timestamp et nonce, corps multipart signé
**When** la signature (HMAC-SHA256, clé dédiée du `key-id`, couvrant corps + métadonnées + timestamp) est valide, le timestamp dans ±5 min, le nonce jamais vu pour ce `key-id`, et la société du partenaire est impliquée dans la transaction `{id}`
**Then** la pièce est attachée (`uploader_type=CARRIER_PARTNER`, `partner_company_id` renseigné, `uploaded_by_user_id` null), visible de toutes les parties (FR-5)
**And** le nonce est enregistré et l'audit écrit.

**Given** un timestamp hors ±5 min OU un nonce déjà vu pour ce `key-id`
**When** l'appel arrive
**Then** il est rejeté `401`/`403` (anti-rejeu — AD-8, NFR-5).

**Given** une transaction où la société du partenaire n'est PAS impliquée
**When** le partenaire dépose
**Then** il reçoit `403` (FR-5).

**Given** qu'aucun scan antivirus n'est réalisé
**When** un fichier partenaire est ingéré
**Then** il est accepté sans scan (risque explicitement accepté POC — NFR-6, documenté).

---

## Epic 4 : Dépôt & litige résilients hors-ligne (PWA)

Une partie peut ouvrir un litige et déposer des preuves sans réseau ; tout est mis en file atomiquement, synchronisé et réconcilié sans perte.

### Story 4.1 : Migrer la file hors-ligne vers IndexedDB (porter le binaire)

As a utilisateur de la PWA sur réseau instable,
I want que la file hors-ligne puisse stocker des fichiers volumineux,
So that mes preuves en attente survivent à un rechargement sans dépasser les quotas.

**Acceptance Criteria:**

**Given** le store `offlineQueue` refondu
**When** une action hors-ligne portant un binaire est mise en file
**Then** l'entrée et son/ses `Blob` (≤ 10 Mo) sont persistés dans **IndexedDB** (plus localStorage) (AR-11, NFR-4)
**And** les actions sans binaire déjà existantes (create, event) continuent de fonctionner (non-régression).

**Given** un binaire de plusieurs Mo mis en file
**When** je recharge la PWA
**Then** l'entrée et son binaire sont toujours présents et rejouables.

### Story 4.2 : Ouvrir un litige & déposer des preuves hors-ligne

As a acheteur ou vendeur sans réseau,
I want ouvrir un litige et joindre mes preuves,
So that je ne sois pas bloqué par une coupure et que rien ne soit perdu.

**Acceptance Criteria:**

**Given** que je suis hors-ligne sur le détail d'une transaction `FUNDS_LOCKED`/`SHIPPED`
**When** j'ouvre un litige avec preuve(s) + commentaire
**Then** l'ouverture + le(s) binaire(s) sont mis dans **une seule entrée de file** atomique (mappée à l'endpoint composite AD-1) (FR-15)
**And** l'UI affiche l'état `DISPUTED` de façon optimiste.

**Given** une entrée de litige en file
**When** la connexion revient
**Then** l'entrée est rejouée en `POST /dispute` multipart (`files[]` 1..N) en une seule unité — l'ouverture et toutes les pièces réussissent ou échouent ensemble.

### Story 4.3 : Réconcilier la file à la synchronisation

As a utilisateur dont l'action offline a été rejouée,
I want que le système distingue un échec temporaire d'un rejet définitif,
So that mes fichiers ne soient ni perdus ni rejoués en boucle.

**Acceptance Criteria:**

**Given** un rejeu qui échoue pour cause de réseau, `5xx`, `408`, `429` ou `409` optimistic-lock
**When** le `flush()` classe l'échec
**Then** il est jugé **transitoire** : l'entrée et son binaire sont conservés et re-tentés plus tard (FR-16, AD-10).

**Given** un rejeu rejeté avec un code applicatif permanent (ex. `DISPUTE_ALREADY_RESOLVED`, `EVIDENCE_INVALID`, `WINDOW_CLOSED`)
**When** le `flush()` classe l'échec (par code applicatif, jamais par la seule classe HTTP)
**Then** l'affichage optimiste est annulé, l'entrée et son binaire sont **conservés**, l'auto-retry de cette entrée est stoppé (FR-16, AD-10)
**And** aucun fichier n'est perdu silencieusement.

### Story 4.4 : Notifier l'utilisateur du résultat de synchronisation

As a utilisateur de la PWA,
I want être informé de l'état de mes dépôts en attente,
So that je comprenne ce qui a été synchronisé, ce qui attend, et ce qui a été rejeté.

**Acceptance Criteria:**

**Given** des pièces/actions en file
**When** j'utilise l'application
**Then** un indicateur affiche le nombre d'éléments en attente de synchro (UX-DR4).

**Given** un rejet permanent à la synchro
**When** la réconciliation se produit
**Then** je reçois une notification claire avec le motif et l'état réel de la transaction, et l'affichage optimiste erroné est retiré (FR-16, UX-DR4).

### Story 4.5 : Récupérer ses preuves après un rejet définitif

As a partie dont l'ouverture de litige hors-ligne a été définitivement rejetée à la synchro,
I want retrouver mes fichiers intacts et comprendre quoi faire ensuite,
So that je ne perde ni mon travail ni le fil, malgré l'échec de mon action optimiste.

**Acceptance Criteria:**

**Given** une ouverture de litige hors-ligne rejetée définitivement (ex. `DISPUTE_ALREADY_RESOLVED`)
**When** j'ouvre l'application après la réconciliation (Story 4.3)
**Then** un écran dédié me présente **mes pièces conservées** (les binaires que j'avais joints, toujours téléchargeables/réutilisables — jamais perdus), le **motif** du rejet et l'**état réel** de la transaction (UX-DR5).

**Given** cet écran de récupération
**When** je le consulte
**Then** il propose un **parcours de sortie** explicite (ex. consulter la résolution, ré-attacher les pièces à une autre transaction éligible si applicable, ou acquitter et vider l'entrée)
**And** rien n'est supprimé tant que je n'ai pas explicitement acquitté (aucune perte silencieuse — FR-16).

---

## Epic 5 : Durcissement transverse (contrat d'API preuves)

Epic **transverse** (cross-cutting) qui solde le backlog de reports différés accumulé pendant les Epics 1-2, **exécuté entre Epic 2 et Epic 3** (l'ordre d'id ≠ l'ordre d'exécution). Ne livre pas de nouvelle valeur utilisateur mais durcit le contrat d'API et ferme des trous de couverture avant d'empiler l'Epic 3 (partenaire) et l'Epic 4 (offline/volume).

### Story 5.1 : Durcir le contrat d'API des preuves (bundle de reports)

As a mainteneur de la plateforme,
I want borner et durcir les endpoints de preuves et compléter la piste d'audit,
So that l'API reste robuste et cohérente sous volume et sous erreur, avant que l'Epic 3/4 n'amplifient la charge.

**Acceptance Criteria:**

**Given** la liste des preuves (`GET /escrow/{id}/evidence`) et la lecture du détail transaction (trail d'audit d'`EscrowService.getDetail`)
**When** une transaction porte un grand nombre de lignes
**Then** aucune liste n'est renvoyée **non bornée** : un plafond (ou pagination `Pageable`) est appliqué de façon **cohérente** aux deux endpoints (report deferred-work 1.3 + lecture d'audit).

**Given** un dépôt multipart `files[]` (dépôt simple ET ouverture composite)
**When** le nombre de fichiers dépasse un plafond (≤ 20, cf. hypothèse PRD §8)
**Then** la requête est rejetée `400` avant toute bufférisation massive (report deferred-work 1.2).

**Given** le téléchargement d'une preuve (`GET .../evidence/{eid}/download`)
**When** un ayant droit télécharge le binaire
**Then** une entrée d'audit (`EVIDENCE_DOWNLOADED`) est écrite — le chemin de lecture n'est plus un trou dans la piste d'audit (report deferred-work 1.4).

**Given** une défaillance du stockage objet **autre** que « objet absent » (MinIO indisponible, timeout, `S3Exception`, 403 de politique de bucket)
**When** elle survient pendant un dépôt ou un téléchargement
**Then** elle est mappée dans l'**enveloppe d'erreur JSON** de la plateforme (ex. `502`), jamais une page whitelabel `500` (report deferred-work + AI Epic 2).

**Given** une pièce retirée
**When** l'API la renvoie (liste, retrait, dépôt)
**Then** `EvidenceDto` expose `withdrawnAt` et `withdrawnByUserId` — qui a retiré la pièce et quand est visible (report deferred-work 2.3).

**Given** le store `evidence` de la PWA et un retrait de pièce
**When** un `loadEvidence` en vol se résout après le commit du retrait
**Then** le retrait participe au jeton `loadSeq` et la pièce ne réapparaît **jamais** brièvement en `ACTIVE` (report deferred-work 2.4).

**Given** la suite de tests
**When** on exécute `mvn test` (+ front)
**Then** tous les tests passent, y compris de nouveaux tests couvrant : le plafond de liste, le rejet `files[]` > plafond, l'audit de download, le mapping d'erreur S3, et les nouveaux champs du DTO.

### Story 5.2 : Corriger les reports remontés par le durcissement 5.1

As a mainteneur,
I want corriger les 3 défauts que la revue de la Story 5.1 a elle-même remontés,
So that le durcissement ne laisse ni régression fonctionnelle ni bug latent avant l'Epic 3.

**Acceptance Criteria:**

**Given** les listes désormais bornées (`GET /escrow/{id}/evidence` et le trail d'audit de `EscrowService.getDetail`)
**When** une transaction dépasse le plafond
**Then** ce sont les entrées **les plus récentes** qui sont conservées, jamais tronquées silencieusement (report A) — p. ex. sélectionner les N dernières par `created_at DESC` puis restituer dans l'ordre attendu ; l'invariant est « le bout récent n'est jamais perdu ». Un test le prouve pour les deux listes.

**Given** `EvidenceService.download` et une ligne dont `size_bytes` est `NULL` (colonne BIGINT nullable, entité `Long`)
**When** le téléchargement s'exécute
**Then** aucun `NullPointerException` d'unboxing : le `Content-Length` est omis (ou géré) proprement quand la taille est absente (report B). Un test couvre le cas `size_bytes NULL`.

**Given** `EvidenceService.download` qui ouvre un flux S3 vivant et écrit l'audit `EVIDENCE_DOWNLOADED`
**When** le téléchargement s'exécute
**Then** l'écriture d'audit et l'ouverture du flux sont ordonnées de façon à **ne jamais retenir une connexion/stream** en cas d'échec (report C) — p. ex. écrire l'audit avant d'ouvrir le flux, ou garantir la fermeture ; le flux reste consommé par la couche web.

**Given** la suite de tests
**When** on exécute `mvn test`
**Then** tout passe, avec les nouveaux tests A/B/C.
