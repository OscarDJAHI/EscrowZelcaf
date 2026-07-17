# Epic 4 Context: Dépôt & litige résilients hors-ligne (PWA)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Cet epic est le seul **front-end** de la feature : il rend le dépôt de preuves et l'ouverture de litige utilisables sur un réseau instable ou absent — contexte de terrain assumé du corridor ZLECAf, où une partie constate un dommage à la réception, souvent sans couverture. Sans réseau, l'utilisateur ouvre son litige et joint ses photos normalement : l'action **et ses binaires** sont mis en file dans **une seule entrée atomique** (IndexedDB, pour porter des `Blob` jusqu'à 10 Mo), l'état `DISPUTED` s'affiche de façon optimiste, puis l'entrée est rejouée en multipart vers l'endpoint composite à la reconnexion. Le cœur de l'epic n'est pas le chemin heureux mais la **réconciliation** : distinguer un échec temporaire (à re-tenter) d'un rejet définitif du serveur (transaction déjà résolue par l'autre partie, par exemple), annuler l'affichage optimiste devenu mensonger, notifier l'utilisateur avec le motif et l'état réel — et, dans tous les cas, **conserver le binaire**. La promesse non négociable : aucun fichier en attente n'est jamais perdu silencieusement, et l'utilisateur dont l'action est rejetée retrouve ses pièces intactes avec un parcours de sortie explicite.

## Stories

- Story 4.1 : Migrer la file hors-ligne vers IndexedDB (porter le binaire)
- Story 4.2 : Ouvrir un litige & déposer des preuves hors-ligne
- Story 4.3 : Réconcilier la file à la synchronisation
- Story 4.4 : Notifier l'utilisateur du résultat de synchronisation
- Story 4.5 : Récupérer ses preuves après un rejet définitif

## Requirements & Constraints

- Une partie doit pouvoir **ouvrir un litige et joindre ses preuves hors-ligne** ; l'événement et sa/ses pièce(s) sont mis en file **atomiquement** (jamais scindés en deux entrées désynchronisables) et synchronisés à la reconnexion, avec affichage **optimiste** de l'état `DISPUTED` en attendant.
- Le dépôt doit rester fonctionnel sur réseau instable/limité : file d'attente, payload maîtrisé. La compression/miniature est optionnelle, hors périmètre imposé.
- **Réconciliation à la synchro** : un rejeu rejeté par le serveur ⇒ l'entrée **et son binaire sont conservés**, l'affichage optimiste est annulé, l'utilisateur est notifié avec le **motif** et l'**état réel** de la transaction. Aucun fichier perdu silencieusement — c'est l'invariant central de l'epic.
- La file doit survivre à un rechargement de la PWA : entrée et binaire toujours présents et rejouables. Les actions déjà en file **sans** binaire (create, event) doivent continuer de fonctionner (non-régression du store existant).
- Un rejet définitif stoppe l'auto-retry de l'entrée concernée (pas de boucle infinie) mais **ne supprime rien** : rien n'est effacé tant que l'utilisateur n'a pas explicitement acquitté.
- Les règles métier miroir côté front pour l'ouverture de litige : ≥ 1 pièce et commentaire ≥ 10 caractères ; formats JPG/PNG/PDF ; ≤ 10 Mo par fichier. La validation front est un confort, jamais la garde — le serveur reste l'autorité.
- Critère de succès observé : le taux d'échec d'upload, en particulier sur mobile / hors-ligne.

## Technical Decisions

- **File atomique portant le binaire (AD-9).** Le store `offlineQueue` migre de **localStorage vers IndexedDB** pour porter des `Blob` (≤ 10 Mo) — le quota localStorage est la raison directe de la refonte. Une ouverture de litige offline = **une seule entrée** contenant l'action + le(s) binaire(s), rejouée en **multipart**. Ne pas introduire de base64 en localStorage comme solution de repli.
- **Cible du rejeu (AD-1).** L'entrée est rejouée vers l'endpoint composite `POST /api/v1/escrow/{id}/dispute` (multipart, `files[]` **1..N**), qui ouvre le litige ET attache les pièces dans une seule unité transactionnelle : tout réussit ou tout échoue. `OPEN_DISPUTE` n'existe pas comme endpoint indépendant sans pièce — la cardinalité `1..N` existe précisément pour permettre un rejeu offline multi-pièces.
- **Contrat multipart figé (AR-13).** Noms de champs **identiques** sur composite / plain / partenaire / rejeu offline : `files[]`, `comment`, `clientCapturedAt` (ISO-8601, optionnel). Une divergence de nommage côté front = 400 au rejeu : interdit. `clientCapturedAt` est le canal prévu pour l'heure de capture d'un dépôt différé.
- **Classement des échecs (AD-10), le cœur de l'epic.** Au `flush()`, classer chaque échec **par le code applicatif de l'enveloppe d'erreur, jamais par la seule classe HTTP** — un `409` est ambigu. **Transitoire** (conserver + re-tenter) : offline/réseau, `5xx`, `408`, `429`, et `409` **optimistic-lock** (`@Version`). **Permanent** (annuler l'optimiste, **conserver** l'entrée + binaire, notifier, stopper l'auto-retry) : un ensemble **énuméré** de codes applicatifs (ex. `DISPUTE_ALREADY_RESOLVED`, `EVIDENCE_INVALID`, `WINDOW_CLOSED`). Cette classification remplace le `break` aveugle actuel du store.
- **Enveloppe d'erreur.** Le back-end renvoie l'enveloppe globale existante `{timestamp, status, error, message}` ; le front s'appuie sur le code applicatif qu'elle porte. Si un code permanent nécessaire n'est pas exposé côté serveur, c'est un conflit à remonter — pas une raison de retomber sur la classe HTTP.
- **Horodatage (AD-11).** L'heure serveur à la réception fait foi et gouverne le tri chronologique ; l'heure client de capture est seulement conservée dans le payload d'audit. Le front ne doit donc pas supposer que l'ordre d'affichage suivra l'ordre de capture offline.
- **Périmètre de code.** Travail concentré sur `frontend/src/stores/offlineQueue.js` (refonte), le store `escrow` (état optimiste), `api/evidence.js` et les composants de dépôt/liste. Stack front existante : Vue 3 + Pinia + Vite/PWA + Tailwind. **Aucune modification back-end n'est requise** par cet epic : les endpoints consommés sont déjà livrés.
- **Preuve de test attendue (AD-10).** Test front du store prouvant qu'un `409`-lock est re-tenté et qu'un code applicatif permanent **gèle** l'entrée **sans perdre le binaire**. C'est le test qui matérialise la promesse « aucune perte silencieuse » ; le chemin heureux ne suffit pas.

## UX & Interaction Patterns

_Aucun document UX formel n'existe ; les exigences ci-dessous sont dérivées des parcours narratifs et de l'architecture — les détails visuels restent à préciser avec l'utilisateur._

- **UX-DR3 (rappel, livré en Epic 2)** — Le parcours « Ouvrir un litige » exige ≥ 1 pièce + commentaire, avec état optimiste `DISPUTED` ; cet epic le rend fonctionnel hors-ligne, il ne le réinvente pas.
- **UX-DR4 — Indicateur de synchro.** Bannière/indicateur affichant le **nombre d'éléments en attente**, visible pendant l'usage normal de l'app. En cas de rejet permanent : notification claire portant le **motif** et l'**état réel** de la transaction, et retrait de l'affichage optimiste erroné.
- **UX-DR5 — Écran de récupération après rejet définitif.** Écran dédié présentant les **pièces conservées** (toujours téléchargeables/réutilisables), le motif du rejet et l'état réel de la transaction, plus un **parcours de sortie** explicite (consulter la résolution, ré-attacher les pièces à une autre transaction éligible si applicable, ou acquitter et vider l'entrée). L'acquittement explicite est la seule voie de suppression.

## Cross-Story Dependencies

- **Dépend de l'Epic 1** (dépôt/liste/téléchargement et leurs composants front) **et de l'Epic 2** (endpoint composite `POST /escrow/{id}/dispute`, cible du rejeu) — les deux sont livrés.
- **Ordre interne fortement couplé** : 4.1 refond le store en IndexedDB (fondation de persistance du binaire) → 4.2 s'en sert pour mettre en file l'ouverture composite atomique et le rejeu multipart → 4.3 ajoute la classification transitoire/permanent au `flush()` → 4.4 expose le résultat de 4.3 à l'utilisateur (compteur + notification) → 4.5 exploite les binaires conservés par 4.3 pour offrir la récupération. 4.4 et 4.5 sont sans objet tant que 4.3 ne produit pas un verdict de réconciliation fiable.
- **Indépendant de l'Epic 3** (partenaire HMAC) : aucun couplage, hors le contrat multipart partagé.
- **Non-régression exigée** sur les entrées de file existantes sans binaire (create, event) lors de la migration 4.1.
