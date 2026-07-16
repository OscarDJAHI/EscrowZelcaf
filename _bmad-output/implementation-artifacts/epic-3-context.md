# Epic 3 Context: Preuve neutre du transporteur (partenaire machine)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Cet epic ouvre le dépôt de preuves à un contributeur **machine** : le partenaire logistique (livreur/transporteur), intégré comme partenaire API (aucun compte ni écran interactif). À la livraison, il pousse des preuves horodatées de l'état de la marchandise via un endpoint signé HMAC-SHA256, strictement restreint aux transactions impliquant sa propre société. Ces pièces deviennent immédiatement visibles de toutes les parties — potentiellement **avant même** l'ouverture d'un litige — et fournissent une preuve neutre et tierce. L'enjeu central est la sécurité : authentifier un appelant machine sans réutiliser le secret des webhooks sortants, empêcher tout rejeu d'une requête interceptée, et garantir qu'un partenaire ne puisse jamais déposer sur une transaction qui ne le concerne pas. L'epic se greffe sur l'infrastructure d'ingestion déjà livrée (table `evidence_files`, port de stockage, validation, audit) et n'y ajoute que la chaîne d'authentification partenaire, puis son durcissement.

## Stories

- Story 3.1 : Provisionner les clés HMAC entrantes & le magasin de nonces
- Story 3.2 : Déposer une preuve partenaire via API signée
- Story 3.3 : Durcir le stockage des clés HMAC partenaire
- Story 3.4 : Durcir l'endpoint partenaire signé (reports de 3.2)

## Requirements & Constraints

- Un partenaire logistique dépose via un endpoint API dédié authentifié par signature HMAC-SHA256 ; il ne peut déposer que sur les transactions impliquant sa propre société — toute tentative sur une transaction tierce est rejetée en 403.
- L'authentification repose sur une **clé HMAC dédiée par partenaire**, identifiée par un `key-id` dans la requête, **distincte** des secrets de webhooks sortants. La signature couvre le corps (fichier + métadonnées) **et** un horodatage.
- Anti-rejeu obligatoire : fenêtre d'horodatage **±5 min**, **nonce** unique stocké et vérifié, unicité scindée par `key-id`. Rétention des nonces **≥ à la fenêtre de validité** (aucune purge sous 5 min, sinon fenêtre de rejeu rouverte). Un timestamp hors fenêtre ou un nonce déjà vu ⇒ rejet 401/403.
- La pièce partenaire est attribuée `uploader_type=CARRIER_PARTNER`, `partner_company_id` renseigné, `uploaded_by_user_id` null, et reste visible de toutes les parties.
- **Aucun scan antivirus/anti-malware** n'est réalisé à l'ingestion : risque explicitement accepté pour le POC et à documenter. Un fichier partenaire est accepté sans scan.
- Retour synchrone suffisant : le code HTTP (2xx/4xx) fait office de réponse au partenaire ; pas de notification asynchrone (POC).
- Critères de robustesse soldés dans l'epic : croissance bornée de la table de nonces (purge des nonces expirés), surface d'authentification chirurgicale (la route signée `permitAll` ne doit pas ouvrir tout un sous-arbre), erreurs d'intégrité non masquées, et preuves validées sur vraie base (Postgres Testcontainer) y compris sous concurrence de nonce identique.

## Technical Decisions

- **Endpoint & auth (AD-8).** `POST /api/v1/partner/escrow/{id}/evidence`, route `permitAll` (comme `/webhooks/incoming/**`, pas de JWT), authentification portée entièrement par la signature. En-têtes attendus : `key-id` partenaire, `X-Escrow-Signature`, timestamp, nonce ; corps multipart signé. Réutiliser l'util `HmacSigner.sign/verify` (comparaison à temps constant) déjà présent pour les webhooks, en y **ajoutant** nonce + timestamp — ne pas réécrire la primitive cryptographique.
- **Séparation des secrets.** La clé entrante est provisionnée par partenaire, résolvable via `key-id` vers une ligne `companies`, et n'est jamais le `secret_key` sortant de `webhook_subscriptions`. Unicité du nonce par clé composite `(key_id, nonce)`.
- **Modèle de données.** Stockage dédié des clés HMAC entrantes et magasin de nonces `(key_id, nonce, seen_at)`, provisionnés par migration Flyway (idiomes existants : `V<n>__desc.sql`, `ddl-auto=none`, `BIGSERIAL`/`IDENTITY`, `TIMESTAMPTZ`, FK). Le contrat de sécurité fixe les invariants ; le schéma précis (table dédiée vs colonnes) est laissé au code.
- **Durcissement des clés (Story 3.3).** Garde d'entropie minimale à l'admission (secret ≥ 32 octets, cohérent avec la convention du secret JWT `escrow.jwt.secret`) rejetant une clé faible ; secret **jamais sérialisé** (`@JsonIgnore`/WRITE_ONLY — absent des réponses API, logs, JSON) ; cycle de vie préservant l'historique anti-rejeu — soit désactivation par drapeau `is_active` plutôt que suppression, soit FK nonce→clé en `RESTRICT`/`NO ACTION` (jamais `CASCADE`, qui effacerait l'historique) ; `key_id` UNIQUE en base. Choix documenté et testé.
- **Attribution en base (AD-12).** L'intégrité de `evidence_files` est tenue par des contraintes DB : `CHECK` d'attribution garantissant `CARRIER_PARTNER` ⟺ `partner_company_id` non-null ∧ `uploaded_by_user_id` null (et l'inverse pour un humain) — pas seulement la discipline applicative.
- **Réutilisation stricte du socle d'ingestion.** Le dépôt partenaire passe par la **même logique de service** que le dépôt utilisateur : stockage via le port `EvidenceStorage` (clé opaque `{transaction_id}/{uuid}`, jamais dérivée du nom fourni), validation serveur stricte (content-sniffing Apache Tika, bornes de taille, `Content-Disposition: attachment` — AD-7), écriture `audit_logs` via `AuditService.recordSuccess` en propagation MANDATORY dans la même transaction (AD-5, action `EVIDENCE_ADDED`, `evidenceId`, `sha256`). Aucune règle de validation dupliquée. Frontières `@Transactional` dans le service ; controllers minces.
- **Contrôle d'appartenance (AD-3).** La vérification « la société du partenaire (via `key-id`) est-elle impliquée dans la transaction `{id}` ? » précède toute opération, cohérente avec le contrôle centralisé anti-IDOR des autres endpoints preuve.
- **Horodatage (AD-11).** `created_at` = heure serveur à la réception (clé du tri chronologique) ; l'heure client éventuelle est conservée dans le payload d'audit, jamais utilisée pour l'ordre.
- **Plancher de preuve (AD-4).** Une preuve partenaire compte dans le plancher (≥ 1 pièce active toutes parties confondues) qui gouverne le retrait en état `DISPUTED`.
- **Durcissement endpoint live (Story 3.4).** Purge des nonces expirés (tâche planifiée et/ou à l'écriture) pour borner la table ; matcher `permitAll` ciblant **exactement** `POST /api/v1/partner/escrow/*/evidence` et non tout `/api/v1/partner/**` ; interprétation fine des `DataIntegrityViolationException` (seul le conflit d'unicité du nonce = rejeu 401/403 ; toute autre violation remonte en 500, non masquée en échec d'auth) ; tests d'intégration et de concurrence sur vraie base (Postgres Testcontainer) prouvant qu'exactement une requête concurrente au même nonce réussit.
- **Enveloppe d'erreur & stack.** Réutiliser l'enveloppe d'erreur globale `{timestamp,status,error,message}` et les exceptions existantes (aucun nouveau handler). Contrat multipart figé, identique aux autres points de dépôt : `files[]` (1..N), `comment`, `clientCapturedAt` (ISO-8601, optionnel). Java 21, Spring Boot 3.3.5, PostgreSQL 16, Apache Tika 3.3.1, AWS SDK Java v2 S3 2.47.6, MinIO S3-compatible.

## Cross-Story Dependencies

- **Dépend de l'Epic 1** : réutilise la table `evidence_files`, le port `EvidenceStorage`, la validation d'ingestion et l'écriture d'audit déjà livrés.
- **Ordre interne intentionnel** : 3.1 provisionne les clés entrantes et le magasin de nonces (fondation de persistance) → 3.3 **durcit** ce stockage (entropie, non-divulgation, cycle de vie) **avant** que 3.2 ne l'utilise pour vérifier les signatures → 3.2 consomme les clés durcies pour authentifier le dépôt → 3.4 solde les reports de la revue de 3.2 (bornage de la table de nonces, précision du matcher `permitAll`, mapping d'erreurs, preuves sur vraie base).
- **Réutilise l'infrastructure existante** : `companies`, util `HmacSigner`, mécanisme webhook — mais **sans réutiliser le secret webhook sortant**.
- **Indépendant de l'Epic 4** (hors-ligne/PWA) : aucun couplage direct.
