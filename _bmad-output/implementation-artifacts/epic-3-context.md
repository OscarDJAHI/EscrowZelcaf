# Epic 3 Context: Preuve neutre du transporteur (partenaire machine)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Cet epic ouvre la plateforme à un contributeur **machine** : un partenaire logistique (livreur/transporteur) pousse des preuves horodatées de l'état de la marchandise via un endpoint API signé, sans écran ni compte interactif. L'enjeu est double : (1) permettre un dépôt authentifié par signature HMAC-SHA256, strictement limité aux transactions impliquant la propre société du partenaire, dont les pièces deviennent visibles de toutes les parties — potentiellement avant même l'ouverture d'un litige, où elles servent de preuve neutre ; (2) durcir le socle de sécurité (clés entrantes, magasin de nonces) pour qu'aucun secret partenaire ne puisse être faible, fuité, ni perdre son historique anti-rejeu. Il capitalise sur le socle d'ingestion déjà livré (table `evidence_files`, port de stockage, validation, audit) sans le dupliquer.

## Stories

- Story 3.1 : Provisionner les clés HMAC entrantes & le magasin de nonces
- Story 3.2 : Déposer une preuve partenaire via API signée
- Story 3.3 : Durcir le stockage des clés HMAC partenaire

## Requirements & Constraints

- **Dépôt partenaire signé (FR-5)** : endpoint machine dédié `POST /api/v1/partner/escrow/{id}/evidence`, authentifié par signature HMAC-SHA256. Le partenaire ne dépose que sur les transactions impliquant sa propre société (`companies`) ; toute tentative sur une transaction tierce est rejetée `403`.
- **Sécurité de la signature (NFR-5)** : clé HMAC **dédiée par partenaire**, identifiée par un **key-id** dans la requête, **distincte** du secret des webhooks *sortants* (jamais réutilisé). La signature couvre le **corps** (fichier + métadonnées) **et un horodatage**. Un **nonce anti-rejeu** est stocké et vérifié.
- **Anti-rejeu figé** : fenêtre de tolérance du timestamp **±5 min** ; unicité du nonce **scindée par key-id** ; **rétention des nonces ≥ durée de fenêtre** (une purge < 5 min rouvrirait une fenêtre de rejeu). Rejet `401/403` si le nonce a déjà été vu pour ce key-id ou si le timestamp est hors fenêtre.
- **Visibilité contradictoire anticipée** : la pièce partenaire est visible de toutes les parties, y compris avant l'ouverture d'un litige.
- **Absence de scan malveillant (NFR-6)** : aucun scan antivirus ; le fichier partenaire est ingéré sans scan, risque explicitement accepté et documenté (POC).
- **Retour synchrone suffisant** : le code HTTP synchrone (2xx/4xx) fait office de réponse au partenaire ; pas de notification asynchrone (POC).
- **Durcissement des clés (Story 3.3)** : garde d'entropie/longueur minimale à l'admission (≥ 32 octets, cohérent avec la convention du secret JWT) rejetant une clé faible ; le secret n'est **jamais** exposé en sérialisation (JSON, log, réponse API) ; le cycle de vie ne peut jamais effacer silencieusement l'historique anti-rejeu (désactivation logique ou FK `RESTRICT`, choix documenté et testé) ; `key-id` unique au niveau table.

## Technical Decisions

- **Modèle d'authentification** : route `permitAll` à la manière de `/webhooks/incoming/**` (pas de JWT), auth portée entièrement par la signature. En-têtes attendus : key-id partenaire, `X-Escrow-Signature`, timestamp, nonce ; corps multipart signé.
- **Réutilisation de l'util existant** : la vérification s'appuie sur `HmacSigner.sign/verify` (comparaison à temps constant) déjà en place pour les webhooks ; l'epic y **ajoute** la gestion nonce + timestamp, sans nouveau mécanisme cryptographique.
- **Résolution key-id → société** : chaque clé entrante porte un key-id résolvable vers une `companies`, base du contrôle « société impliquée dans la transaction ». Unicité du nonce par **clé composite `(key_id, nonce)`**.
- **Persistance dédiée** : stockage des clés HMAC entrantes (par key-id, lié à `companies`, distinct du `secret_key` sortant de `webhook_subscriptions`) et magasin de nonces `(key_id, nonce, seen_at)`, provisionnés par migration Flyway. Le contrat de sécurité fixe les invariants, pas le schéma exact.
- **Ligne `evidence_files` partenaire** : `uploader_type = CARRIER_PARTNER`, `partner_company_id` renseigné, `uploaded_by_user_id` null. Cet invariant d'attribution est tenu par une contrainte `CHECK` en base (CARRIER_PARTNER ⟺ société non-null ∧ user null), pas seulement par la discipline applicative.
- **Réutilisation stricte du socle d'ingestion** : validation du type réel (content-sniffing), bornes de taille, clé de stockage opaque `{transaction_id}/{uuid}`, écriture d'audit — tout passe par la **même logique de service** que le dépôt utilisateur ; aucune règle dupliquée.
- **Contrôle d'appartenance centralisé (anti-IDOR)** : réutiliser le contrôle unique de la plateforme ; ici « la société du partenaire est-elle impliquée dans la transaction {id} ? ».
- **Horodatage serveur source de vérité** : `created_at` = heure de réception serveur (clé du tri chronologique) ; l'heure client éventuelle reste conservée dans le payload d'audit, jamais utilisée pour l'ordre.
- **Audit atomique** : chaque dépôt partenaire écrit une entrée `audit_logs` (`EVIDENCE_ADDED`, `evidenceId`, `sha256`) dans la même transaction, en propagation MANDATORY.
- **Plancher de preuve** : une preuve partenaire compte dans le plancher (≥ 1 pièce active toutes parties confondues) qui gouverne le retrait en état `DISPUTED`.
- **Contrat multipart figé** : noms de champs partagés identiques à ceux des autres points de dépôt — `files[]` (1..N), `comment`, `clientCapturedAt` (ISO-8601, optionnel).

## Cross-Story Dependencies

- **Dépend de l'Epic 1** : réutilise la table `evidence_files`, le port de stockage, la validation d'ingestion et l'écriture d'audit déjà livrés.
- **Ordre interne intentionnel** : Story 3.1 provisionne les clés entrantes et le magasin de nonces (fondation de persistance) → Story 3.3 **durcit** ce stockage (entropie, non-divulgation, cycle de vie) **avant** que la Story 3.2 ne l'utilise pour vérifier les signatures → Story 3.2 consomme les clés durcies pour authentifier le dépôt.
- **Réutilise l'infrastructure existante** de la plateforme : `companies`, mécanisme webhook/HMAC (`HmacSigner`), mais **sans réutiliser le secret webhook sortant**.
