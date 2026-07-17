# Epic 5 Context: Durcissement transverse (contrat d'API preuves)

<!-- Generated from planning artifacts. Regenerate with compile-epic-context if planning docs change. -->

## Goal

Epic **transverse** qui n'apporte aucune valeur utilisateur nouvelle : il solde le backlog de reports différés accumulé pendant les Epics 1 et 2, borne et durcit le contrat d'API des preuves et referme des trous de couverture, afin que l'API reste robuste et cohérente sous volume et sous erreur avant que l'Epic 3 (partenaire machine) et l'Epic 4 (hors-ligne / volume) n'amplifient la charge. Sa dernière story (5.3) est un ajout tardif d'une autre nature : elle dote l'enveloppe d'erreur d'un **code applicatif stable**, prérequis backend sans lequel la réconciliation hors-ligne de l'Epic 4 ne peut pas être implémentée conformément à l'architecture. **Ordonnancement : l'ordre des identifiants ne suit pas l'ordre d'exécution** — 5.1 et 5.2 s'exécutent entre l'Epic 2 et l'Epic 3 ; 5.3 arrive après, en réponse à une escalation de la Story 4.3.

## Stories

- Story 5.1 : Durcir le contrat d'API des preuves (bundle de reports)
- Story 5.2 : Corriger les reports remontés par le durcissement 5.1
- Story 5.3 : Contrat d'erreur codé (prérequis backend de la réconciliation)

## Requirements & Constraints

- **Aucune liste non bornée** — La liste des preuves et la lecture du trail d'audit du détail transaction appliquent un plafond (ou une pagination) de façon **cohérente sur les deux endpoints** : décision unique de contrat de liste, pas endpoint par endpoint. Le troncage préserve le **bout récent** — les entrées les plus récentes ne sont jamais perdues silencieusement.
- **Plafond du nombre de fichiers** — Toute requête multipart dépassant le plafond (≤ 20 fichiers, limite indicative héritée d'une hypothèse produit) est rejetée en `400` **avant toute bufférisation massive**, sur le dépôt simple **et** l'ouverture composite de litige.
- **Piste d'audit complète** — Le chemin de lecture du binaire écrit lui aussi une entrée d'audit (acteur, rôle, id de pièce) : le download n'est plus un trou de non-répudiation. Attention à la sémantique d'une écriture depuis une lecture.
- **Toute erreur passe par l'enveloppe JSON de la plateforme** — Les défaillances du stockage objet autres que « objet absent » (stockage indisponible, timeout, refus de politique de bucket) sont mappées proprement (ex. `502`), jamais rendues en page whitelabel `500`, ni en code brut. Sans faire fuiter de détail interne.
- **Attribution du retrait exposée** — Les réponses de l'API révèlent qui a retiré une pièce et quand (valeurs déjà présentes en base et en audit).
- **Robustesse du chemin de téléchargement** — Tolérer une taille absente en base sans erreur d'exécution ; ne jamais retenir un flux/connexion de stockage ouvert en cas d'échec.
- **Cohérence du store PWA au retrait** — Voir section UX.
- **Critère de succès transverse** — La suite complète (backend + front) reste verte, chaque durcissement étant accompagné du test qui le verrouille.

## Technical Decisions

- **AD-10 — classer les échecs par code applicatif, jamais par la seule classe HTTP.** À la synchro hors-ligne, chaque échec de rejeu est classé **transitoire** (conserver entrée + binaire, re-tenter) ou **permanent** (annuler l'affichage optimiste, conserver entrée + binaire, notifier avec motif + état réel, stopper l'auto-retry). Un `409` est ambigu : conflit de verrou optimiste (transitoire) ou règle métier définitive (permanent). Le classement ne peut donc reposer ni sur le statut HTTP seul, ni sur du **matching de texte** de message (instable). Invariant absolu : **aucun fichier n'est perdu silencieusement**.
- **Conséquence structurante (5.3)** — L'enveloppe d'erreur globale porte un champ **`code`** : identifiant machine **stable**, SCREAMING_SNAKE_CASE, distinct de la reason-phrase HTTP. Chaque exception métier porte un code ; l'enum machine-readable déjà présente sur l'exception de transition mais jamais sérialisée sert de point d'appui et est généralisée à toutes les exceptions métier.
- **Granularité exigée** — L'illégalité sur **état terminal** (litige déjà résolu ; transaction libérée/remboursée) reçoit des codes **distincts** de l'illégalité ordinaire : c'est cette distinction qui permet au front de trancher permanent vs transitoire.
- **Verrou optimiste** — Une collision de verrouillage optimiste est mappée sur un **`409` codé `CONCURRENT_MODIFICATION`** (au lieu du `500` par défaut du framework) et classée **transitoire**.
- **Énumération faisant foi**, arrêtée et partagée, référençable par le front :
  - **PERMANENT** : `DISPUTE_ALREADY_RESOLVED`, `TRANSACTION_TERMINAL`, `WINDOW_CLOSED`, `EVIDENCE_INVALID`, `NOT_A_PARTY`, `TRANSACTION_NOT_FOUND`, `EVIDENCE_FLOOR_VIOLATION`, `COMMENT_TOO_SHORT`, `TOO_MANY_FILES`.
  - **TRANSITOIRE** : hors-ligne/réseau, `5xx`, `CONCURRENT_MODIFICATION`, `FILE_READ_ERROR`, timeout/rate-limit.
- **Le contrat de codes est public** — Une fois publié, il est consommé par le front ; un test le verrouille pour qu'un renommage ne casse pas silencieusement la réconciliation.
- **Conventions à respecter** — Enveloppe d'erreur et exceptions applicatives existantes : lever les exceptions du socle, ne pas multiplier les handlers. Audit via un writer unique, propagation atomique avec l'opération. Le binaire n'est manipulé qu'à travers le port de stockage (clé opaque) : aucun code de service/web ne référence le SDK objet. Règle de dépendance descendante uniquement : web → service → {repository, audit, port}. Le contrat de champs multipart est **figé** et identique sur tous les chemins (composite / simple / partenaire / rejeu offline) — une divergence produit un `400` au rejeu.

## UX & Interaction Patterns

- **Cohérence optimiste du store `evidence`** — Le retrait de pièce mute la liste en place sans participer au jeton de séquence qui garde les chargements ; un chargement en vol résolu après le commit du retrait peut réafficher brièvement la pièce comme active. Faire participer le retrait à ce jeton, sous une politique unique de cohérence optimiste (dépôt + retrait). Fenêtre étroite, auto-corrigée, serveur toujours cohérent : durcissement d'UX, pas perte de données.
- **Socle de la notification de réconciliation** — Le champ `code` est ce qui rend possible, côté PWA, l'indicateur de pièces en attente et la notification de rejet serveur (motif + état réel, sans perte silencieuse de fichier). Les écrans appartiennent à l'Epic 4 ; l'Epic 5 n'en livre que le socle machine.

## Cross-Story Dependencies

- **5.2 dépend de 5.1** — Elle corrige les trois défauts (troncage perdant le bout récent, taille absente au téléchargement, ordonnancement audit/flux) que la revue de 5.1 a elle-même remontés ; elle n'a de sens qu'appliquée au code livré par 5.1.
- **5.1 + 5.2 précèdent délibérément les Epics 3 et 4** — Ces épics amplifient la charge sur exactement les surfaces ici bornées (liste, dépôt multipart, stockage objet).
- **5.3 bloque les Stories 4.3, 4.4 et 4.5** — Prérequis backend de la réconciliation. Origine : escalation CRITICAL de la Story 4.3 — AD-10 impose un classement par code applicatif, le backend n'en exposait aucun, et l'Epic 4 était cadré « aucune modification backend ». La contradiction est tranchée ici : **l'Epic 4 n'est donc pas purement frontend**.
- **5.3 recoupe les règles métier des Epics 1-3** — Les codes à attribuer couvrent des exceptions levées au dépôt, au retrait, à l'ouverture de litige et au verrou d'état terminal : l'inventaire traverse tout le périmètre existant, pas seulement l'Epic 5.
- **Séquencement de consommation** — 5.3 publie les codes ; la Story 4.3 les consomme. Publier le contrat avant que le front n'y adhère est l'ordre requis.
