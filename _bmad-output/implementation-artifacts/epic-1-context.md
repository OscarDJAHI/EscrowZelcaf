# Epic 1 Context: Socle — dépôt & consultation contradictoire des preuves

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Permettre à toute partie prenante d'une transaction (acheteur, vendeur, arbitre) d'attacher des pièces justificatives dès l'état `FUNDS_LOCKED`, puis de consulter et télécharger **toutes** les pièces du dossier — visibilité contradictoire, ordre chronologique, traçabilité complète. Cet epic est le socle : il embarque toute l'infrastructure d'ingestion (backend de stockage objet, table de preuves, port de stockage isolé, validation du contenu, écriture d'audit, contrôle d'appartenance anti-IDOR) parce que c'est ici qu'elle est requise en premier. Les epics 2, 3 et 4 s'appuient tous dessus. Sans la preuve, l'arbitre ne peut pas trancher : ce socle est le pivot de la confiance de la plateforme.

## Stories

- Story 1.1 : Fondations de stockage des preuves
- Story 1.2 : Déposer une pièce sur une transaction
- Story 1.3 : Consulter la liste chronologique des preuves
- Story 1.4 : Télécharger une preuve en toute sécurité
- Story 1.5 : Interface PWA — dépôt & liste des preuves

## Requirements & Constraints

**Dépôt**
- Toute partie prenante de la transaction peut attacher 1..N pièces dès que les fonds sont bloqués et jusqu'au verrou d'état terminal ; commentaire optionnel pour les dépôts hors ouverture de litige.
- Types acceptés : **JPG, PNG, PDF uniquement**, jugés sur le **type réel du contenu** (content-sniffing), jamais sur l'extension ou le `Content-Type` déclaré ; incohérence entre les trois ⇒ rejet.
- Taille : **0 < taille ≤ 10 485 760 octets**. Fichier vide comme fichier trop gros sont rejetés en 400 (jamais 413/500).

**Consultation & visibilité**
- **Contradictoire** : acheteur, vendeur et arbitre voient *toutes* les pièces, quelle qu'en soit la provenance. Aucune vue partielle par rôle.
- Liste triée par ordre chronologique (heure serveur), portant déposant, type de déposant, horodatage, type MIME, taille, commentaire, statut.
- Une pièce retirée reste visible avec son statut — jamais masquée.

**Sécurité (non négociable)**
- Toute validation est **serveur** ; les contrôles client sont un confort, jamais l'autorité.
- Nom de stockage **généré**, jamais dérivé du nom fourni (anti-path-traversal) ; le nom d'origine n'est qu'une métadonnée assainie.
- Accès aux binaires contrôlé par autorisation — aucune URL publique devinable.
- Restitution systématique en `Content-Disposition: attachment`, jamais *inline* (anti-XSS stocké via PDF).
- Anti-IDOR : le téléchargement vérifie que la pièce appartient bien à la transaction de l'URL **et** que le demandeur est partie prenante.

**Intégrité**
- Chaque dépôt (et retrait) produit une entrée d'audit immuable, committée dans la même transaction que l'opération métier — jamais après, jamais en dehors.
- Aucune suppression physique ; rétention illimitée (POC, aucune purge planifiée).
- Aucun scan antivirus : risque explicitement accepté pour le POC.

**Observabilité des critères** — Story 1.1 exige un test de round-trip réel prouvant l'identité octet-pour-octet du binaire restitué. « Ça compile » n'est pas un critère.

## Technical Decisions

**Contexte brownfield.** Aucun starter : la plateforme Spring Boot (couches `web/service/repository/domain`) et la PWA Vue 3/Pinia existent. La règle est de **ratifier les conventions présentes**, pas d'en inventer : injection par constructeur, controllers minces, `@Transactional` porté par le service, DTOs = records dans un holder avec `static from()`, enveloppe d'erreur globale existante (aucun nouveau handler — lever les exceptions maison 400/403/404/409), migrations Flyway versionnées avec `ddl-auto=none`, principal d'authentification injecté, routes authentifiées par défaut.

**Port de stockage (l'invariant central).** Le binaire n'est manipulé **que** via une interface de stockage (`store(bytes, contentType) → clé`, `load(clé) → stream`). La clé est **opaque**, de forme `{transaction_id}/{uuid}`. Aucun code hors de l'adaptateur ne connaît MinIO/S3 : c'est ce qui rendra triviale la bascule vers un autre backend (MinIO OSS est archivé — la question est ouverte, la réponse est ce port).

**Fenêtre fondée sur l'état, pas sur l'événement.** Dépôt et retrait autorisés ssi l'état ∈ {`FUNDS_LOCKED`, `SHIPPED`, `DISPUTED`}. Le contrôle est serveur et ne se dérive jamais du dernier événement enregistré.

**Contrôle d'appartenance unique et centralisé.** Tous les endpoints preuve chargent d'abord la transaction et passent par le **même** contrôle « X est-il partie de Y ? » (réutiliser le mécanisme `resolveRole`/`authorizeView` existant). Deux endpoints avec des règles d'accès divergentes = la faille.

**Horodatage.** `created_at` = heure serveur à la réception ; c'est la seule clé de tri. Une éventuelle heure de capture client est conservée dans le payload d'audit, jamais utilisée pour l'ordre.

**Audit.** Réutiliser le writer d'audit unique existant en propagation MANDATORY, avec payload JSONB schemaless enrichi (action, identifiant de pièce, empreinte sha256). **Pas** de nouvelle colonne `action_type`.

**Modèle de données.** Table net-new `evidence_files` (migration `V2`) suivant les idiomes de `V1` : PK BIGSERIAL, `TIMESTAMPTZ`, FK explicites, index `(transaction_id, created_at)`. Colonnes : transaction, déposant (nullable — un dépôt partenaire n'a pas d'utilisateur), type de déposant, société partenaire (nullable), nom d'origine, type MIME contraint, taille, clé de stockage, commentaire, statut, dates de création/retrait, auteur du retrait. Enums en `VARCHAR` : `UploaderType {BUYER, SELLER, ADMIN, CARRIER_PARTNER}`, `EvidenceStatus {ACTIVE, WITHDRAWN}`.

**Contrat multipart figé (transverse).** Noms de champs **identiques** partout — dépôt simple, ouverture composite, endpoint partenaire, rejeu offline : `files[]` (1..N), `comment`, `clientCapturedAt` (ISO-8601, optionnel). Toute divergence casse le rejeu offline en 400. À respecter dès Epic 1 même si les autres consommateurs n'existent pas encore.

**Arbitrage de la taille par le service.** La limite multipart du framework est réglée **au-dessus** de 10 Mo volontairement, pour que le service arbitre et que l'exception d'upload trop gros soit mappée vers 400 dans le handler global — enveloppe d'erreur uniforme.

**Infra.** Un service MinIO est ajouté au docker-compose (volume persistant + bucket provisionné) ; endpoint et credentials injectés via variables d'environnement avec valeur par défaut — **aucun secret en dur**.

**Stack imposée** — Java 21, Spring Boot 3.3.5, PostgreSQL 16, Apache Tika `tika-core` 3.3.1 (content-sniffing), AWS SDK for Java v2 `s3` 2.47.6, MinIO `RELEASE.2025-10-15T17-29-55Z`. Frontend Vue 3 + Pinia + Vite/PWA + Tailwind (existant).

**Direction des dépendances** — `web → service → {repository, audit, port}`. Seul l'adaptateur connaît le backend de stockage. Jamais de dépendance remontante.

## UX & Interaction Patterns

Aucun document UX formel n'existe ; les besoins front sont dérivés des parcours narratifs et restent à préciser avec l'utilisateur.

- **Composant de dépôt** sur le détail transaction, disponible dès `FUNDS_LOCKED` : sélection du fichier, aperçu type/taille, champ commentaire optionnel, soumission. Un type non autorisé ou une taille excessive est signalé côté client **avant** l'envoi — confort d'usage, le serveur restant l'autorité.
- **Liste chronologique des preuves** en vue contradictoire : déposant, horodatage, type, taille, statut actif/retiré, action télécharger, action retirer visible seulement si l'utilisateur est le déposant.
- Contexte d'usage : réseau instable (ZLECAf), mobile fréquent — le payload doit rester maîtrisé. Miniatures/prévisualisation : optionnel, non requis pour le POC.

## Cross-Story Dependencies

- **Story 1.1 débloque tout le reste** : la table, le port de stockage et le backend MinIO conditionnent 1.2 → 1.4. À faire en premier.
- **Story 1.2 pose la logique de service réutilisée partout** : validation, clé opaque, écriture d'audit. Epic 2 (ouverture composite de litige) et Epic 3 (dépôt partenaire) doivent **réutiliser ce même service** — aucune règle de validation dupliquée. La qualité de ce découpage se paiera dans les deux epics suivants.
- **Story 1.5 (PWA) dépend des endpoints de 1.2 et 1.3.**
- Le contrôle d'appartenance sert 1.2, 1.3 et 1.4 : le factoriser dès 1.2 plutôt que de le répliquer.
- **Le statut `WITHDRAWN`** est lu par la liste (1.3) alors que le retrait n'est implémenté qu'en Epic 2 — modéliser le statut dès 1.1 et l'afficher dès 1.3.
- **Epics 2, 3 et 4 dépendent tous d'Epic 1** (table, port, validation, audit, contrat multipart).
