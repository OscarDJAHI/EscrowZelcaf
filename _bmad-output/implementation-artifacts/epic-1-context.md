# Epic 1 Context: Socle — dépôt & consultation contradictoire des preuves

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Permettre à toute partie prenante d'une transaction (acheteur, vendeur, arbitre) d'attacher des pièces justificatives dès l'état `FUNDS_LOCKED`, puis de consulter et télécharger **toutes** les pièces du dossier — y compris celles de la partie adverse — dans une logique contradictoire tracée. La preuve est le pivot de la confiance dans un séquestre B2B transfrontalier : c'est elle qui permet à un arbitre de trancher équitablement. Cet epic porte en plus toute l'infrastructure d'ingestion (stockage objet, table `evidence_files`, port de stockage, validation du contenu, écriture d'audit, contrôle d'appartenance), car c'est ici qu'elle est requise pour la première fois ; les epics 2 à 4 s'y adossent sans la redéfinir.

## Stories

- Story 1.1 : Fondations de stockage des preuves
- Story 1.2 : Déposer une pièce sur une transaction
- Story 1.3 : Consulter la liste chronologique des preuves
- Story 1.4 : Télécharger une preuve en toute sécurité
- Story 1.5 : Interface PWA — dépôt & liste des preuves

## Requirements & Constraints

**Dépôt**
- Toute partie prenante de la transaction peut attacher 1..N pièces dès `FUNDS_LOCKED` ; un commentaire du déposant est optionnel à ce stade (il ne devient obligatoire qu'à l'ouverture d'un litige, hors epic).
- Types acceptés : JPG, PNG, PDF **uniquement**, contrôlés sur le type réel du contenu (content-sniffing), jamais sur l'extension ou le `Content-Type` déclaré seuls ; incohérence entre les trois ⇒ rejet.
- Taille : `0 < taille ≤ 10 485 760` octets. Un fichier vide comme un fichier hors borne est rejeté. La limite est **arbitrée par le service**, pas par le conteneur multipart.

**Consultation & visibilité**
- Visibilité contradictoire : chaque partie et l'arbitre voient **toutes** les pièces, quelle qu'en soit l'origine.
- Liste par ordre chronologique croissant, chaque item portant déposant, type de déposant, horodatage, type MIME, taille, commentaire et statut (`ACTIVE`/`WITHDRAWN`). Une pièce retirée reste visible, jamais masquée.
- Téléchargement du binaire original, servi uniquement après contrôle d'appartenance.

**Sécurité (autorité serveur)**
- Toute validation est serveur ; le client ne fait que du confort d'UX, il n'est jamais l'autorité.
- Anti-IDOR : le téléchargement vérifie que la pièce appartient bien à la transaction de l'URL **et** que le demandeur est partie prenante.
- Pas de nom de stockage dérivé du nom fourni (anti-path-traversal) ; le nom d'origine n'est conservé qu'en métadonnée assainie.
- Restitution en `Content-Disposition: attachment`, jamais *inline* (anti-XSS stocké via PDF).
- Aucun scan antivirus : risque explicitement accepté pour le POC.

**Intégrité & rétention**
- Chaque dépôt génère une entrée d'audit immuable, cohérente transactionnellement (ACID) avec l'opération métier.
- Rétention illimitée : aucune purge planifiée pour le POC.

**Codes d'erreur attendus** : `400` (type/taille/contenu invalide), `403` (non partie prenante), `409`/`400` (fenêtre de dépôt fermée), `404`/`403` (pièce étrangère à la transaction).

## Technical Decisions

**Contexte brownfield.** La plateforme Spring Boot + Vue existe déjà : ratifier les conventions présentes (injection par constructeur, controllers minces, `@Transactional` porté par le service, DTOs en records avec `static from()`, enveloppe d'erreur globale existante, migrations Flyway avec `ddl-auto=none`). Aucun nouveau handler d'exception : lever les exceptions applicatives existantes.

- **Port de stockage (invariant central)** — le binaire n'est manipulé qu'à travers une interface de stockage exposant un `store`/`load` par **clé opaque** de forme `{transaction_id}/{uuid}`, jamais dérivée du nom fourni. L'implémentation POC est un adaptateur MinIO (S3-compatible, AWS SDK Java v2). **Aucun code hors de l'adaptateur ne connaît MinIO/S3** — c'est ce qui rendra la bascule future (S3 managé, chiffrement) indolore.
- **Modèle de données** — table net-new `evidence_files` (migration `V2`) : transaction, déposant (nullable — un dépôt partenaire n'a pas d'utilisateur), type de déposant, société partenaire (nullable), nom d'origine, MIME, taille, clé de stockage, commentaire, statut, `created_at`, `withdrawn_at`, `withdrawn_by`. Idiomes de `V1` obligatoires (BIGSERIAL PK, TIMESTAMPTZ, FK). Index `(transaction_id, created_at)` — c'est la clé du tri chronologique. Enums en `VARCHAR` : déposant ∈ {BUYER, SELLER, ADMIN, CARRIER_PARTNER}, statut ∈ {ACTIVE, WITHDRAWN}.
- **Fenêtre fondée sur l'état, pas sur l'événement** — dépôt/retrait autorisés **ssi** l'état ∈ {`FUNDS_LOCKED`, `SHIPPED`, `DISPUTED`}. Jamais dérivé du dernier événement enregistré.
- **Contrôle d'appartenance unique** — tout endpoint preuve charge d'abord la transaction et passe par le **même** contrôle « X est-il partie de Y ? » déjà présent dans la plateforme. Deux endpoints avec des règles d'accès divergentes = défaut.
- **Audit dans la même transaction** — écriture via le writer d'audit unique existant en **propagation MANDATORY** (commit atomique avec l'opération métier). Le payload JSONB schemaless est enrichi (action, identifiant de pièce, sha256, heure client de capture si dépôt différé). **Pas** de nouvelle colonne : le schéma d'audit ne bouge pas.
- **Horodatage : serveur source de vérité** — `created_at` = heure serveur à la réception, seule clé de tri. L'heure client de capture n'est conservée que dans le payload d'audit et n'ordonne jamais rien.
- **Contrat multipart figé** — noms de champs identiques sur **tous** les points d'entrée (dépôt simple, ouverture composite, partenaire, rejeu offline) : `files[]` (1..N), `comment`, `clientCapturedAt` (ISO-8601, optionnel). Une divergence ici casserait le rejeu offline des epics ultérieurs.
- **Infrastructure** — service MinIO ajouté au compose (volume persistant + bucket provisionné) ; endpoint et credentials injectés via `${ENV:default}`, aucun secret en dur.
- **Découpage en couches** — `web → service → {repository, audit, port}` ; l'adaptateur seul connaît le backend de stockage. Aucune dépendance remontante.
- **Preuve par test observable** — le round-trip de stockage doit être prouvé par un test automatisé (binaire restitué identique octet pour octet), pas par « ça compile ». La garde de fenêtre d'état étend le test de machine à états existant plutôt que de le contourner.

## UX & Interaction Patterns

Aucun document UX formel n'existe ; les besoins ci-dessous sont dérivés des parcours décrits dans le PRD et restent à préciser avec l'utilisateur.

- **Composant de dépôt** sur l'écran de détail transaction, disponible dès `FUNDS_LOCKED` : sélection de fichier, aperçu type/taille, champ commentaire optionnel, soumission. Le client signale un type ou une taille invalides avant l'envoi — confort d'UX, le serveur restant l'autorité.
- **Liste chronologique des preuves** sur ce même écran (déposant, horodatage, type, taille, statut actif/retiré), avec action « télécharger » et action « retirer » visible seulement pour le déposant de la pièce.

## Cross-Story Dependencies

- **Story 1.1 est bloquante pour tout le reste** : table, port de stockage et backend objet conditionnent les stories 1.2 à 1.4.
- Story 1.2 (dépôt) établit la logique de validation, la clé opaque et l'écriture d'audit que **l'ouverture composite de l'Epic 2 doit réutiliser telle quelle** — aucune règle de validation dupliquée. La concevoir comme un service réutilisable, pas comme une méthode de controller.
- Stories 1.3 et 1.4 dépendent de 1.2 pour exister avec des données ; 1.5 (PWA) consomme les endpoints de 1.2 à 1.4.
- Le contrôle d'appartenance introduit ici est le point unique réutilisé par les epics 2 à 4.
- Epics aval : Epic 2 (litige) et Epic 3 (partenaire) dépendent de cet epic pour la table, le port, la validation et l'audit ; Epic 4 (hors-ligne) en dépend via le contrat multipart figé.
