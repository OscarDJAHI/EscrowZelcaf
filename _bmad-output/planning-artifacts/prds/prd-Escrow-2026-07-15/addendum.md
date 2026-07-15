# Addendum technique — Dépôt de preuves

> Détails « comment » extraits du PRD (le PRD reste au niveau « quoi »).
> À destination de l'architecture, du design de solution et du découpage en stories.
> **Propositions**, pas des décisions figées : à valider en phase architecture.

## 1. Modèle de données (nouvelle table)

Aucune table de pièces jointes n'existe aujourd'hui. Proposition d'une table `evidence_files` :

- `id` (BIGSERIAL, PK)
- `transaction_id` (BIGINT, FK → `escrow_transactions`, Not Null)
- `uploaded_by_user_id` (BIGINT, FK → `users`, **nullable** — null si dépôt partenaire machine)
- `uploader_type` (VARCHAR(20)) — `BUYER` | `SELLER` | `ADMIN` | `CARRIER_PARTNER`
- `partner_company_id` (BIGINT, FK → `companies`, nullable — renseigné pour un dépôt livreur)
- `original_filename` (VARCHAR(255))
- `mime_type` (VARCHAR(100)) — contraint à `image/jpeg`, `image/png`, `application/pdf`
- `size_bytes` (BIGINT) — ≤ 10 485 760
- `storage_path` (VARCHAR(500)) — chemin disque relatif
- `comment` (TEXT, nullable — obligatoire applicativement à l'ouverture de litige)
- `status` (VARCHAR(20)) — `ACTIVE` | `WITHDRAWN`
- `created_at` (TIMESTAMP, Not Null)
- `withdrawn_at` (TIMESTAMP, nullable)
- `withdrawn_by_user_id` (BIGINT, FK → `users`, nullable)

Index suggéré : `(transaction_id, created_at)` pour la consultation chronologique (FR-10).

## 2. Stockage objet MinIO / S3-compatible (POC)

- **Décision d'architecture (2026-07-15)** : stockage objet **MinIO (S3-compatible)** dès le POC, conteneur ajouté à `docker-compose`. Remplace la piste « disque local » initiale (aligné sur la tech-stack cible).
- Bucket **configurable** (ex. `app.evidence.bucket=escrow-evidence`, endpoint/credentials via variables d'environnement `${...:default}`).
- Clé d'objet générée : `{transaction_id}/{uuid}` — jamais dérivée du nom fourni (assainissement / anti-path-traversal — NFR-2). L'extension/MIME validé sert au `Content-Type` de restitution.
- Couche d'accès derrière l'interface (`EvidenceStorage`) : les endpoints ne manipulent qu'une **clé opaque**, pour permettre une bascule (S3 managé, chiffrement par fichier) sans toucher aux endpoints (NFR-1).

## 3. Endpoints REST proposés

Utilisateurs (JWT) :
- `POST /api/v1/escrow/{id}/evidence` — dépôt (multipart : fichier + commentaire). Refus si `state ∉ {FUNDS_LOCKED, SHIPPED, DISPUTED}` (c.-à-d. avant `FUNDS_LOCKED` ou état terminal). Validation content-sniffing + taille (0 < taille ≤ 10 Mo).
- `GET  /api/v1/escrow/{id}/evidence` — liste (métadonnées, ordre chronologique, statut).
- `GET  /api/v1/escrow/{id}/evidence/{eid}/download` — téléchargement ; **vérifier `{eid}.transaction_id == {id}`** et que l'appelant est partie prenante (anti-IDOR, FR-11) ; réponse en `Content-Disposition: attachment`.
- `POST /api/v1/escrow/{id}/evidence/{eid}/withdraw` — retrait logique (`status=WITHDRAWN`) ; refus si l'appelant n'est pas le déposant, ou si le retrait passe sous le plancher FR-6 alors que `DISPUTED` (FR-12).

Partenaire livreur (HMAC) :
- `POST /api/v1/partner/escrow/{id}/evidence` — dépôt machine ; en-têtes : **key-id partenaire** + signature HMAC-SHA256 **couvrant le corps** + horodatage + **nonce anti-rejeu**. Clé **dédiée entrante** (≠ secret webhook sortant). Vérifier que la société du partenaire (via key-id → `companies`) **est impliquée dans la transaction** `{id}` (sinon 403, FR-5).

**Note IDOR / autorisation** : centraliser un contrôle « l'utilisateur X est-il partie prenante de la transaction Y ? » réutilisé par tous les endpoints ci-dessus.

**Note content-sniffing** : valider le *magic number* (ex. Apache Tika ou détection maison) et le confronter à l'extension + au `Content-Type` déclaré ; rejeter en cas d'incohérence.

## 4. Impact sur la machine à états

- `OPEN_DISPUTE` doit être modifié pour **exiger** au moins une pièce dans la même unité de travail transactionnelle (FR-6) — soit la pièce est fournie dans la requête d'ouverture, soit l'ouverture référence une pièce venant d'être déposée. À trancher en architecture : **un endpoint composite** « ouvrir + joindre » est probablement le plus sûr pour garantir l'atomicité (et pour le rejeu offline atomique, FR-15).
- Verrouillage des dépôts quand `state ∈ {RELEASED, REFUNDED}` (FR-8).

## 5. Impact sur la file d'attente hors-ligne (PWA)

- `OfflineQueueStore` existant doit pouvoir porter un **binaire** (Blob/base64) en plus de l'événement.
- L'ouverture de litige hors-ligne = **une seule entrée de file** contenant `OPEN_DISPUTE` + la/les pièce(s), rejouée atomiquement (mappe vers l'endpoint composite ci-dessus).
- Attention à la **taille du stockage local** du navigateur (fichiers jusqu'à 10 Mo) — IndexedDB plutôt que localStorage.

## 6. Piste d'audit

- Réutiliser `audit_logs`. Un dépôt/retrait de preuve n'est pas une transition d'état financier : soit on enrichit le `payload` JSONB avec le type d'action (`EVIDENCE_ADDED` / `EVIDENCE_WITHDRAWN`) et l'`evidence_id`, soit on ajoute une colonne `action_type`. À décider en architecture.

## 7. Alternatives écartées (traçabilité des décisions)

- **Livreur en acteur interactif complet** — écarté pour le POC (coût élevé, 4e écran) au profit de l'intégration partenaire API (Option 2). Réutilise l'infra `companies`/webhook/HMAC.
- **Suppression physique des pièces** — écartée au profit du retrait logique, pour préserver l'immuabilité et la piste d'audit.
- **Disque local d'emblée** — écarté (2026-07-15) au profit de **MinIO (S3-compatible)** dès le POC, pour éviter une refonte de stockage entre POC et prod ; l'interface `EvidenceStorage` isole malgré tout le backend.
