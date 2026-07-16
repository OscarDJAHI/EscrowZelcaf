# Epic 3 Context : Preuve neutre du transporteur (partenaire machine)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Cet epic ouvre la plateforme à un acteur non humain : un partenaire logistique (transporteur) qui pousse des preuves horodatées de l'état de la marchandise via un endpoint API signé, sans session utilisateur. La valeur est d'obtenir une preuve **neutre** (ni acheteur ni vendeur), authentifiée cryptographiquement, restreinte aux transactions que la société du partenaire sert réellement, et visible de toutes les parties — potentiellement **avant même** l'ouverture d'un litige. L'enjeu principal est la sécurité machine-à-machine : authentification par HMAC-SHA256 avec clé dédiée entrante, protection anti-rejeu (fenêtre temporelle + nonce), et cloisonnement strict par société. L'epic réutilise toute l'infrastructure d'ingestion posée par l'Epic 1 (table, stockage, validation, audit) : il n'apporte que l'authentification partenaire et l'attribution machine des pièces.

## Stories

- Story 3.1 : Provisionner les clés HMAC entrantes & le magasin de nonces
- Story 3.2 : Déposer une preuve partenaire via API signée

## Requirements & Constraints

- Un partenaire dépose **uniquement** sur des transactions où sa société est impliquée ; sinon `403`. Le cloisonnement par société est la garde d'autorisation centrale de l'epic (équivalent machine du contrôle d'appartenance humain).
- L'authentification repose sur une signature HMAC-SHA256 portée par un en-tête `X-Escrow-Signature`, accompagnée d'un `key-id` partenaire, d'un timestamp et d'un nonce. La signature doit couvrir le **corps + les métadonnées + le timestamp** (pas seulement le corps).
- Anti-rejeu **non négociable** : timestamp accepté dans une fenêtre de **±5 minutes** ; nonce jamais réutilisé pour un même `key-id`. Un timestamp hors fenêtre ou un nonce déjà vu ⇒ rejet `401`/`403`.
- La clé HMAC entrante est **dédiée** et distincte du secret des webhooks **sortants** ; ne jamais réutiliser le secret sortant en entrée.
- Rétention des nonces **≥ durée de la fenêtre** de validité : toute purge en deçà de 5 min rouvre une fenêtre de rejeu et est interdite.
- Une pièce partenaire est attribuée en `uploader_type = CARRIER_PARTNER`, avec `partner_company_id` renseigné et `uploaded_by_user_id` null (attribution machine, pas humaine).
- **Aucun scan antivirus** n'est réalisé sur les fichiers partenaire : risque explicitement accepté (POC), à documenter.
- Le dépôt réutilise la validation d'ingestion existante (content-sniffing du type réel, bornes de taille, clé de stockage opaque) et écrit une entrée d'audit dans la même transaction que le dépôt.

## Technical Decisions

- **Route et sécurité** : `POST /api/v1/partner/escrow/{id}/evidence`, exposée en `permitAll` (à la manière des webhooks entrants `/webhooks/incoming/**`) — l'autorisation vient exclusivement de la signature, pas d'une session.
- **Réutilisation de l'util existant** : la vérification de signature s'appuie sur l'util `HmacSigner` (comparaison constant-time) déjà présent pour les webhooks ; l'epic l'**étend** avec la logique nonce + timestamp plutôt que de la réimplémenter. Étendre `HmacSignerTest` : signature couvrant corps+timestamp, nonce rejoué (même key-id) rejeté, timestamp hors ±5 min rejeté.
- **Contrôleur dédié** : `PartnerEvidenceController` (couche web mince) déléguant au service ; toutes les frontières `@Transactional` restent dans la couche service.
- **Persistance (migration Flyway du partenaire)** : nouvelle migration ajoutant (1) un stockage des **clés HMAC entrantes** indexé par `key-id` et lié à une ligne `companies`, distinct du `secret_key` sortant de `webhook_subscriptions` ; (2) un magasin de **nonces** `(key_id, nonce, seen_at)` dont l'unicité est scindée par `key-id` via une clé composite `(key_id, nonce)`. Respecter les idiomes des migrations existantes (BIGSERIAL PK, TIMESTAMPTZ, FK `REFERENCES`, index).
- **Résolution du key-id** : un `key-id` doit être résolvable vers la `companies` du partenaire ; c'est cette société qui est confrontée à la transaction `{id}` pour la garde d'autorisation.
- **Contraintes DB d'attribution** : la ligne `evidence_files` d'une preuve partenaire est validée par les `CHECK` de la table (posés par l'Epic 1) : `CARRIER_PARTNER ⟺ partner_company_id non-null ∧ uploaded_by_user_id null`. L'attribution ne repose pas sur la seule discipline applicative.
- **Contrat multipart figé** : mêmes noms de champs (`files[]`, `comment`, `clientCapturedAt`) que les endpoints humains, pour un contrat d'ingestion uniforme.

## Cross-Story Dependencies

- **Dépend de l'Epic 1** (prérequis dur) : table `evidence_files`, port `EvidenceStorage`, validation d'ingestion et écriture d'audit sont réutilisés tels quels ; l'Epic 3 n'ajoute que l'authentification partenaire.
- **Dépend de l'Epic 5** (durcissement transverse, exécuté avant l'Epic 3) : le contrat d'API des preuves est déjà borné et durci (plafonds de liste, plafond `files[]`, mapping d'erreur stockage, audit de download) — les dépôts partenaire héritent de ce contrat robuste.
- **Ordre interne** : Story 3.1 (schéma clés + nonces) est un prérequis dur de Story 3.2 (endpoint signé), qui consomme le magasin de clés et de nonces.
- L'attribution `CARRIER_PARTNER` alimente le plancher de preuve de l'Epic 2 : une preuve partenaire compte dans le décompte des pièces `ACTIVE` toutes parties confondues (décision POC).
