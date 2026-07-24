# Revue rubric — ARCHITECTURE-SPINE Escrow ZLECAf (production, 2026-07-24)

- **Relecteur** : rubric walker indépendant (checklist « good-spine », 8 points)
- **Objet** : `_bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md` (status: draft)
- **Références lues intégralement** : spine parent Evidence Upload (2026-07-15, status: final), PRD production 2026-07-24 (FR-P1..P43, NFR-P1..P25), backlog re-dérivé `epics.md` (11 epics / 63 stories, FR Coverage Map)
- **Date de revue** : 2026-07-24

## Verdict global

Spine **solide** : la ratification brownfield est fidèle, les AD-1..12 hérités sont repris read-only sans renumérotation ni contradiction, les 15 nouveaux AD couvrent l'essentiel des vrais points de divergence du circuit financier, et les reports (Deferred) sont presque tous adossés à une story porteuse. **Aucun finding critique.** Trois findings **high** empêchent toutefois de passer le spine en `status: final` en l'état : le modèle d'autorisation multi-utilisateur intra-entreprise est entièrement silencieux, l'enveloppe opérationnelle (environnements, observabilité, sauvegarde) est sous-décidée alors qu'AD-16 en dépend comme critère d'entrée de l'Epic 4, et l'exigence de lancement « migration Spring Boot 4.1.x » n'a pas de story porteuse dans le backlog de 63 stories.

---

## Findings

### CRITICAL

Aucun.

### HIGH

#### H-1 — Modèle d'autorisation multi-utilisateur intra-entreprise absent (checklist 1, 6)
- **Point précis** : AD-21 fixe les rôles *plateforme* (`{BUYER, SELLER, ADMIN, ARBITRATOR}`), mais rien dans le spine ne gouverne les **rôles internes par entreprise** (FR-P14), le rattachement User↔Company (FR-P9), l'invitation de collègues (FR-P13, Story 2.5). La Capability → Architecture Map n'a aucune ligne pour l'onboarding/comptes d'entreprise (Epic 2, stories 2.4/2.5).
- **Pourquoi c'est un problème** : c'est un vrai point de divergence pour le niveau en dessous. AD-3 (appartenance) et AD-20 (gating KYB) raisonnent « la company est-elle partie de la transaction ? » — mais pas « *quel utilisateur* de la company peut engager les fonds ? ». Sans décision, la Story 2.5 (rôles internes), la Story 4.x (financement/retrait) et la Story 7.x (gestion utilisateurs) inventeront chacune leur réponse : qui peut inviter, qui peut retirer, si un rôle interne restreint les actions engageantes. Divergence quasi garantie entre trois epics.
- **Correction proposée** : ajouter un AD (ou étendre AD-21) : énumérer les rôles internes (ex. `{OWNER, MEMBER}` `[ASSUMPTION]`), octroi serveur uniquement, définir quelles actions engageantes (financer, retirer, accepter) exigent quel rôle interne, et rattacher la garde au même rang qu'AD-3/AD-20 (contrôle serveur central, jamais dérivé côté UI). Ajouter la ligne Epic 2 à la Capability Map.

#### H-2 — Enveloppe opérationnelle sous-décidée : observabilité, environnements, sauvegarde (checklist 8, 3)
- **Point précis** : la ligne « Opérabilité (NFR-P11..P21, P23) » de la Capability Map renvoie à « CI, stack déployée, observabilité » gouvernés par « AD-16 (alerte), Deferred AR-P4/AR-P5 ». Or AR-P4 = secrets et AR-P5 = cible de déploiement/régions. **Personne ne décide ni ne défère nommément** : la stack d'observabilité (métriques/alerting), la stratégie d'environnements (dev/staging/prod, promotion), la sauvegarde/restauration et la rétention des logs.
- **Pourquoi c'est un problème** : (a) dimension « initiative » entièrement silencieuse = finding par définition (checklist 8) ; (b) plus grave : **AD-16 exige une « alerte bloquante (métrique dédiée) » comme critère d'entrée du circuit financier (Epic 4)**, mais l'outillage qui porte cette métrique n'est ni tranché ni déféré — le critère d'entrée d'AD-16 repose sur une décision qui n'existe nulle part. Une story Epic 4 et une story Epic 11 peuvent choisir deux stacks d'alerting incompatibles ; c'est précisément le trou qu'un Deferred ne doit pas laisser (checklist 3).
- **Correction proposée** : ajouter deux reports nommés avec porteur : **AR-P7 — stack d'observabilité & alerting** (tranché en Story 11.x, *avant* la story Epic 4 qui implémente l'alerte AD-16 — dépendance à écrire explicitement dans AD-16) et **AR-P8 — environnements & sauvegarde/restauration** (Story 11.3 ou nouvelle story). À défaut, poser au moins la contrainte minimale (ex. « toute alerte AD-16 = métrique exposée au format Prometheus » `[ASSUMPTION]`) pour rendre les deux epics convergents.

#### H-3 — Migration Spring Boot 4.1.x : exigence de lancement sans story porteuse (checklist 3)
- **Point précis** : Stack : « Spring Boot 3.3.5 existant → **migration 4.1.x exigence de lancement** » ; Deferred : « story **à ajouter** à l'Epic 11 ».
- **Pourquoi c'est un problème** : un report n'est pas un trou *seulement* s'il a un point d'atterrissage. Ici le spine élève la migration au rang d'exigence de lancement tout en constatant que le backlog de 63 stories ne la contient pas. Si la story n'est jamais créée, le spine et le backlog se contredisent silencieusement au moment du lancement — et la migration Framework 7 est un chantier transverse (167+ tests) qui ne s'improvise pas en fin d'Epic 11.
- **Correction proposée** : escalader immédiatement la création de la story Epic 11 (contenu déjà cadré dans le Deferred) et faire de son existence un critère du gate `check-implementation-readiness` ; le spine ne doit pas passer `final` tant que le porteur n'existe pas.

### MEDIUM

#### M-1 — AD-13/AD-15/AD-18 : propriétés exigées sans mécanisme de concurrence ni de stockage d'idempotence fixé (checklist 2)
- **Point précis** : AD-13 exige « solde jamais négatif, prouvé par test de concurrence » ; AD-18 exige une « clé d'idempotence obligatoire » dont « la même clé rejouée renvoie le résultat du premier traitement ». Ni le mécanisme de sérialisation (verrou pessimiste par compte ? contrainte ? niveau d'isolation ?) ni le stockage des clés d'idempotence (table ? unicité ? réponse persistée ? TTL ?) ne sont fixés. Par ailleurs deux mécanismes d'idempotence coexistent (référence métier unique AD-13, clé d'idempotence AD-18, référence PSP AD-17) sans articulation.
- **Pourquoi c'est un problème** : la Rule énonce le *résultat* mais pas le *moyen* — or c'est le moyen qui diverge. `DepositService`, `WithdrawalService` et `EscrowService` (trois groupes de stories de l'Epic 4/5) peuvent implémenter trois stratégies de verrouillage différentes (risque de deadlocks croisés et de sémantiques de rejeu incompatibles). Le « Prevents » (double-engagement, doublon) n'est réellement empêché que si le mécanisme est unique.
- **Correction proposée** : compléter AD-13/AD-18 d'une convention unique, p.ex. : verrou pessimiste `SELECT … FOR UPDATE` sur les `ledger_accounts` impliqués, ordonnés par id (anti-deadlock), contrôle du disponible sous verrou ; table `idempotency_keys` `UNIQUE(key)` portée par `LedgerService`, réponse sérialisée persistée ; articulation explicite : clé d'idempotence API → référence métier de l'écriture.

#### M-2 — RabbitMQ 3.13 épinglé sans signalement de fin de support (checklist 4)
- **Point précis** : Stack : « RabbitMQ | 3.13 ».
- **Pourquoi c'est un problème** : la ligne 3.13 est sortie de support communautaire (la série courante est 4.x depuis fin 2024). Le spine vérifie et date soigneusement l'EOL Spring Boot (« vérifié web 2026-07 ») mais ratifie RabbitMQ 3.13 sans le même examen — incohérence de rigueur pour une plateforme dont l'outbox AD-22 repose sur ce broker en production.
- **Correction proposée** : vérifier le statut de support de la 3.13 à date, et soit planifier la montée 4.x (même traitement que Spring Boot : Deferred avec story Epic 11), soit documenter le support commercial retenu.

#### M-3 — Tokens CSS vs Tailwind existant : le sort de l'existant n'est pas statué (checklist 5, 1)
- **Point précis** : le spine parent ratifie « Vue 3 + Pinia + Vite/PWA + **Tailwind** — existant » ; le nouveau spine impose « Tokens CSS de `DESIGN.md` = contrat (pas de lib UI imposée) » et un arbre `design/tokens.css`, sans mentionner Tailwind ni décider de son sort.
- **Pourquoi c'est un problème** : brownfield mal ratifié = divergence. Les stories 2.1/2.2/2.3 (fondation UI) et toutes les vues existantes (AuthView, écrans Evidence livrés) coexisteront : sans règle, certains composants seront stylés en classes Tailwind, d'autres en tokens, et le « mapping état→couleur centralisé unique » (UX-DR2) aura deux sources de vérité.
- **Correction proposée** : statuer dans Consistency Conventions, p.ex. : « les tokens `DESIGN.md` sont la seule source des valeurs ; Tailwind est conservé comme couche utilitaire configurée *depuis* les tokens (theme = tokens) ; interdiction de valeurs en dur dans les classes ; l'existant migre opportunistiquement story par story ».

#### M-4 — Deferred « brouillons de messages hors-ligne » : report explicitement sans porteur (checklist 3)
- **Point précis** : Deferred : « brouillons de messages hors-ligne (UX-DR44 `[ASSUMPTION]` — à ratifier ou abandonner, **aucune story porteuse**) ».
- **Pourquoi c'est un problème** : le spine constate lui-même l'absence de point d'atterrissage. Un report sans porteur ne sera jamais tranché ; pendant ce temps, l'Epic 6 (messagerie, AD-26) et l'Epic 9 (offline, AD-27) peuvent chacun présumer la réponse — la whitelist AD-27 n'inclut pas les messages, mais UX-DR44 laisse croire le contraire côté UX.
- **Correction proposée** : trancher maintenant — le plus cohérent avec AD-27 est d'**abandonner** UX-DR44 (les messages ne sont pas dans la whitelist offline) et de le noter comme décision, ou de créer la story Epic 9 qui le ratifie. Ne pas laisser le report ouvert sans porteur.

#### M-5 — Session & authentification : plusieurs stories, aucune décision de modèle (checklist 1, 8)
- **Point précis** : les stories 1.3 (anti-bruteforce), 1.6 (politique de mots de passe **et révocation JWT**), 1.9 (hygiène de session), 2.4 (OTP), 2.8/FR-P28 (2FA TOTP) touchent toutes le même module d'auth. Le spine ne dit rien du modèle de session : durée des JWT, refresh ou non, mécanisme de révocation (blacklist ? version de token ?), interaction 2FA↔JWT.
- **Pourquoi c'est un problème** : la révocation JWT est structurante (elle conditionne stateless vs état serveur) ; cinq stories réparties sur deux epics peuvent choisir des mécanismes incompatibles (ex. 1.6 introduit une blacklist pendant que 1.9 suppose des tokens courts sans état).
- **Correction proposée** : une ligne de Consistency Conventions (Sécurité) fixant le modèle : p.ex. access token court + refresh révocable en base `[ASSUMPTION]`, révocation par identifiant de session, 2FA vérifiée à l'émission du token.

### LOW

#### L-1 — ERD : cardinalité wallet contradictoire (checklist 1)
- **Point précis** : `companies ||--|| wallets : "un par devise"` — la cardinalité dessinée est 1–1 alors que le libellé dit « un par devise », et AD-14/FR-P25 disent « wallets en USD » (un seul au MVP) avec « unicité company+currency » en note normative.
- **Pourquoi** : incohérence interne mineure, mais l'ERD est un document de sortie pour les stories de l'Epic 4 — la migration wallet doit savoir si la contrainte est `UNIQUE(company_id)` ou `UNIQUE(company_id, currency)`.
- **Correction** : `companies ||--o{ wallets` + note « `UNIQUE(company_id, currency)`, USD seul seedé au MVP ».

#### L-2 — Capability Map : lignes manquantes Epic 2 (onboarding) et Epic 10 (site public) (checklist 6)
- **Point précis** : FR-P9/P13/P14/P27/P28 (Epic 2) et FR-P41 (Epic 10) n'apparaissent dans aucune ligne de la Capability → Architecture Map (P27 n'est touché que par AD-24 via le consentement).
- **Pourquoi** : la couverture PRD est nominalement assurée par les conventions, mais la map est l'outil de traçabilité du niveau en dessous ; deux epics entiers sans ligne affaiblissent le contrôle de couverture (et ont masqué H-1).
- **Correction** : ajouter « Onboarding & comptes (FR-P9/P13/P14/P27/P28) | `AuthService` étendu, `CompanyService` | AD-21 (+ nouvel AD H-1), AD-24 » et « Site public (FR-P41) | `views/public/`, textes légaux `ConfigService` | AD-24, conventions frontend ».

#### L-3 — `scheduler/` référencé mais absent du paradigme et de l'arbre source (checklist 1)
- **Point précis** : la ligne « Cycle escrow & SLA » de la map cite `scheduler/` ; le tableau des couches et l'arbre source ne le mentionnent pas. AD-19 rend les jobs idempotents par les gardes de la machine (bien), mais l'emplacement et la convention de nommage des jobs (SLA, purge nonces héritée 3.4, rapprochement AD-16) ne sont fixés nulle part.
- **Correction** : ajouter `scheduler/` (ou `service/scheduler/`) à l'arbre source avec la convention `XxxJob`, et noter que tout job doit être sûr en exécution concurrente (multi-instance — lien avec AR-P5).

#### L-4 — Versions frontend ratifiées sans note de fraîcheur (checklist 4)
- **Point précis** : « Vue / Pinia / Vite | 3.5 / 2.3 / 6 (existant) ». Pinia 3.x et Vite 7 existent ; le marqueur « existant » ratifie légitimement le brownfield, mais contrairement à Spring Boot rien n'indique que la fraîcheur a été examinée et le report assumé.
- **Correction** : une mention « montées mineures frontend non bloquantes, au fil de l'eau » dans Deferred suffit.

---

## Parcours de la checklist (synthèse)

| # | Critère | Évaluation |
| --- | --- | --- |
| 1 | Fixe les vrais points de divergence, sans en manquer | **Partiel.** Le cœur financier, KYB, notifications, offline, i18n, config sont bien verrouillés (AD-13..27 pertinents et bien choisis). Manquent : autorisation intra-entreprise (H-1), mécanisme de concurrence/idempotence (M-1), sort de Tailwind (M-3), modèle de session (M-5), emplacement des jobs (L-3). |
| 2 | Chaque AD a une Rule enforceable qui empêche le Prevents | **Largement oui.** Les Rules sont majoritairement testables et tenues en base (AD-13 immuabilité DB, AD-25 échéance calculée, AD-20 garde unique, AD-22 outbox). Exception : AD-13/15/18 énoncent le résultat sans fixer le moyen (M-1) ; AD-16 dépend d'un outillage non décidé (H-2). |
| 3 | Rien dans Deferred ne laisse deux unités diverger | **Partiel.** AR-P1/P2/P3/P4/P6 sont des reports propres : port fixé ici, choix du fournisseur adossé à une story-spike identifiée. Trous : migration Spring Boot sans porteur (H-3), observabilité non nommée (H-2), brouillons offline sans porteur (M-4). |
| 4 | Technologies vérifiées-actuelles | **Partiel.** Spring Boot EOL vérifié et daté (exemplaire), MinIO honnêtement flaggé archivé, Tika/PostgreSQL/Java OK. RabbitMQ 3.13 hors support non signalé (M-2) ; versions frontend ratifiées sans examen (L-4). Le tag MinIO reprend la version corrigée du parent (leçon du faux tag retenue). |
| 5 | Ratifie le brownfield au lieu de le contredire | **Oui, à une ambiguïté près.** Package-by-layer étendu fidèlement, Flyway V1..V5 + `ddl-auto=none` repris, Vue3/Pinia et la file IndexedDB (Story 4.1 POC, y compris le harnais Vitest) ratifiés, whitelist Story 1.1 étendue et non réécrite, socle webhooks/RabbitMQ réutilisé par l'outbox. Seule ambiguïté : Tailwind (M-3). |
| 6 | Couvre FR-P1..P43 et NFR-P1..P25 | **Quasi complet.** Vérifié contre le PRD et la FR Coverage Map du backlog : 37/43 FR-P tracés dans le spine (binds ou map) ; FR-P9/P13/P14/P27/P28/P41 non tracés (H-1, L-2). NFR-P1..P25 tous rattachés (par plages) ; NFR-P22 (PCI-DSS) traité avec justesse par AD-17 (parcours hébergés). |
| 7 | Aucun AD-13..27 n'affaiblit AD-1..12 ni les invariants hérités | **Oui.** Vérification croisée AD par AD : AD-19 préserve la machine à états et AD-2 ; AD-25 étend AD-4 en le durcissant (le passage POC « rétention illimitée » → « purge interdite avant échéance ≥ 5 ans » est un durcissement explicite et assumé, pas une contradiction) ; AD-27 restreint AD-9 sans le contredire (litige+preuve reste en file, l'optimisme financier est exclu — l'optimisme `DISPUTED` d'AD-9 n'est pas financier) ; AD-18 s'appuie sur AD-5 ; AD-23 conserve l'enveloppe d'erreur + `code` (AD-10/Story 5.3) ; AD-21 étend la whitelist héritée sans la rouvrir. Aucun conflit détecté. |
| 8 | Toute dimension « initiative » décidée, déférée ou en question ouverte | **Partiel.** Données, sécurité applicative, intégrations, frontend, i18n, config, rétention : décidés. Fournisseurs : déférés proprement. **Silencieux** : environnements & promotion, observabilité (nommément), sauvegarde/DR (H-2) ; topologie d'exécution mono/multi-instance (effleurée via l'idempotence AD-19, jamais posée — L-3) ; modèle de session (M-5). |

## Recommandation

Corriger H-1, H-2, H-3 (deux ajouts d'AD/Deferred + une escalade de story) avant de passer le spine en `status: final` ; M-1 et M-3 devraient être tranchés avant le démarrage des epics 4 et 2 respectivement ; le reste peut être absorbé au fil des stories.
