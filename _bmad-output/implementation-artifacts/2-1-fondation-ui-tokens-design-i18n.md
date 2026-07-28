# Story 2.1: Fondation UI — tokens de design et i18n EN/FR par clés

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a développeur frontend de la plateforme,
I want un socle de tokens CSS (couleurs, typographie, espacements, rayons) et une infrastructure i18n par clés installés dans la PWA existante,
so that tous les écrans à venir partagent un langage visuel et textuel unique, sans aucune valeur ni chaîne en dur.

**Portée réelle :** cette story est la fondation de **douze epics**. Tout ce qu'elle pose mal se propage ; tout ce qu'elle omet sera recopié en dur par les stories suivantes.

## Acceptance Criteria

**AC1 — Tokens exposés en CSS custom properties globales**
**Given** la PWA Vue 3 existante
**When** l'application est chargée
**Then** l'ensemble des tokens de couleur (marque navy/vert recalibré, surfaces et texte, sémantique success/warning/danger/info/neutral/offline, montants signés crédit/débit) est exposé en CSS custom properties globales
**And** la rampe typographique Inter (display 28 → caption 12, `amount` et `amount-hero`), les espacements base 4 et les rayons sont définis en tokens, avec un utilitaire `tabular-nums` applicable à tout montant/solde
**And** l'élévation respecte la règle : cartes bordées **sans ombre** au repos ; ombre légère `0 1px 3px rgb(0 0 0 / 0.1)` réservée aux surfaces flottantes (menus, toasts, modales).

**AC2 — Mapping état→token unique et centralisé**
**Given** le code couleur sémantique du cycle de vie escrow
**When** un écran doit représenter un état de transaction
**Then** une correspondance unique état→token est centralisée dans un module partagé (`INITIATED`=warning, `FUNDS_LOCKED`/`SHIPPED`=info, `RELEASED`=success, `DISPUTED`=danger, `REFUNDED`/expirée=neutral, offline=offline), jamais redéfinie par écran
**And** les interdits sont vérifiés par lint ou test : aucun dégradé décoratif, pas de navy comme couleur d'état, absence du vert `#50DF77`.

**AC3 — i18n par clés, bascule EN/FR persistée**
**Given** l'infrastructure i18n par clés en place et les chaînes des vues existantes (dont `AuthView`) migrées vers des clés EN et FR
**When** l'utilisateur bascule la langue via le sélecteur « EN / FR » (libellés texte, sans drapeaux)
**Then** toute l'interface s'affiche dans la langue choisie, avec l'**anglais par défaut** et les formats de date/nombre localisés
**And** le choix est persisté et restauré à la session suivante.

**AC4 — Clé manquante = échec, jamais silence**
**Given** une clé de traduction manquante dans l'une des deux langues
**When** l'application est exécutée en développement ou sous le harnais Vitest
**Then** l'absence est détectée (avertissement **bloquant en CI de test**) au lieu d'afficher silencieusement une chaîne en dur
**And** aucune nouvelle chaîne littérale n'est admise dans les composants migrés.

## Tasks / Subtasks

- [ ] **T1 — Lire avant d'écrire** (préalable à tout, AC: 1,2,3)
  - [ ] Lire `frontend/src/style.css` en entier : il porte aujourd'hui une palette **teal** (`--color-brand-500: #14b8a6`) via `@theme` Tailwind 4. Elle est **incompatible** avec l'identité ratifiée (navy + vert `#047857`) et doit être remplacée, pas complétée.
  - [ ] Lire `frontend/src/utils/stateMachine.js` (`STATE_COLORS`) : le mapping existe déjà et **contredit** la spec sur deux états (voir « Divergences » ci-dessous).
  - [ ] Lire `frontend/src/components/StateBadge.vue` : consomme `STATE_COLORS[state].badge` et rend le libellé par `state.replaceAll('_',' ')` — chaîne machine affichée à l'utilisateur.
  - [ ] Lister les chaînes visibles de `src/views/*.vue` et `src/components/*.vue` (concentration : `AuthView` 9, `NewTransactionModal` 8, `RecoveryView` 5).

- [ ] **T2 — Poser les tokens CSS** (AC: 1)
  - [ ] Déclarer TOUTES les valeurs du frontmatter de `DESIGN.md` en custom properties globales : couleurs (marque, surfaces, texte, sémantique, montants signés), rampe Inter, espacements base 4, rayons.
  - [ ] Charger la police **Inter** (couverture latin étendu FR/EN requise).
  - [ ] Fournir un utilitaire `tabular-nums` (`font-variant-numeric: tabular-nums`) applicable à tout montant/solde.
  - [ ] Régler l'élévation : cartes bordées sans ombre ; ombre uniquement pour surfaces flottantes.
  - [ ] Décider et **documenter** l'articulation tokens ↔ Tailwind 4 (`@theme`) : les tokens sont le contrat, Tailwind le véhicule. Ne pas créer deux sources de vérité.

- [ ] **T3 — Centraliser le mapping état→token** (AC: 2)
  - [ ] **Étendre `STATE_COLORS`, ne pas créer un module concurrent.** Le retargeter sur les tokens et **corriger les deux divergences** (INITIATED, SHIPPED).
  - [ ] Vérifier tous les consommateurs actuels après changement (`StateBadge.vue`, et tout autre import de `STATE_COLORS`).
  - [ ] Écrire la garde des interdits (lint ou test) : pas de dégradé, pas de navy en couleur d'état, `#50DF77` absent du dépôt.

- [ ] **T4 — Infrastructure i18n** (AC: 3)
  - [ ] Installer `vue-i18n@11.4.8` (dernière stable Vue 3 au 2026-07-28).
  - [ ] Catalogues EN et FR par clés, **anglais par défaut**, formats de date/nombre localisés.
  - [ ] Sélecteur « EN / FR » en libellés **texte** — jamais de drapeaux (règle explicite de `DESIGN.md`).
  - [ ] Persister le choix et le restaurer à la session suivante. **Attention** : la clé de stockage doit survivre à `endSession()` — voir « Interaction avec la Story 1.9 ».

- [ ] **T5 — Migrer les chaînes existantes** (AC: 3)
  - [ ] Migrer toutes les vues et composants existants vers des clés, `AuthView` comprise (nommément citée par l'AC).
  - [ ] Traduire chaque clé en EN **et** FR.

- [ ] **T6 — Garde « clé manquante »** (AC: 4)
  - [ ] Détection en développement ET sous Vitest, **bloquante en CI**.
  - [ ] Garde anti-régression : aucune chaîne littérale nouvelle dans les composants migrés.
  - [ ] **Prouver par mutation** : retirer une clé FR → la CI rougit ; la remettre → verte. Consigner le résultat.

- [ ] **T7 — Tests** (AC: 1,2,3,4)
  - [ ] Test de composant sur le sélecteur de langue (harnais `@vue/test-utils` opérationnel, gabarit `SyncFailureNotice.spec.js`).
  - [ ] Test du mapping état→token, y compris les deux états corrigés.
  - [ ] Suite complète verte : **241 tests frontend** au départ, aucun ne doit rougir.
  - [ ] `npm run lint` propre (`--max-warnings 0`).

## Dev Notes

### ⚠️ Divergences constatées entre le code actuel et la spec — à corriger, pas à conserver

| Sujet | Code aujourd'hui | Spec ratifiée | Conséquence si ignoré |
|---|---|---|---|
| Palette de marque | `style.css` : teal `#14b8a6` (`@theme` Tailwind) | navy `#101E5A` + vert `#047857` | Toute l'UI des 12 epics part sur la mauvaise identité |
| `INITIATED` | `bg-gray-200` (neutre) | **warning** | Une invitation en attente ne se distingue plus d'une transaction annulée |
| `SHIPPED` | `bg-amber-100` (ambre) | **info** | Ambre est réservé à l'attente ; l'expédition est un état d'avancement |
| Libellé du badge | `state.replaceAll('_',' ')` — chaîne machine | libellé i18n | Chaîne technique montrée à l'utilisateur, non traduisible |

Les deux corrections d'état sont un **changement de comportement visible**, pas un refactor : les vérifier explicitement.

### ⚠️ Piège documentaire : les identifiants `UX-DR*` ne sont définis nulle part

`epics.md` cite `UX-DR1`…`UX-DR48` **164 fois**, mais aucun document ne définit ce que porte chaque identifiant — ni `DESIGN.md`, ni `EXPERIENCE.md`, ni le spine. Leur sens n'existe que dans la **glose inline** de chaque critère d'acceptation.

**Ne pas chercher un registre de règles UX : il n'existe pas.** L'autorité pour cette story est, dans cet ordre :
1. le **frontmatter de `DESIGN.md`** (valeurs exactes des tokens — c'est le contrat) ;
2. le **corps de `DESIGN.md`** (règles d'usage : un seul primaire par surface, jamais d'icône seule, interdits) ;
3. la glose inline des AC ci-dessus.

Les AC de cette story ont été réécrits pour **inliner** ce que les `UX-DR` désignaient : ils se suffisent à eux-mêmes.

### Architecture compliance — AD-23 (contrat i18n)

> « Le **frontend** possède les clés EN/FR de toute l'UI — **clé manquante = échec CI (Story 2.1)**. […] Les réponses API ne portent jamais de texte destiné à l'utilisateur — codes machine. Aucun service métier ne compose de texte. »

Deux conséquences directes :
- L'échec CI sur clé manquante est **nommément assigné à cette story** par le spine. Ce n'est pas optionnel (AC4).
- Les libellés d'erreur se dérivent du champ `code` de l'enveloppe, **jamais** du champ `message` du serveur. Un miroir front des codes existe déjà : `src/utils/replayFailure.js` (`FAILURE_LABELS`), gardé contre la dérive par `replayFailure.spec.js` qui lit `ErrorCode.java`. **Ces libellés devront devenir des clés i18n** — et la garde anti-dérive doit continuer de passer.

### Interaction avec la Story 1.9 (hygiène de session) — risque de régression

`stores/session.js#endSession()` purge `localStorage`, IndexedDB et le cache de lecture à la déconnexion. La **préférence de langue n'est pas une donnée de session** : elle doit survivre à une déconnexion (sinon l'utilisateur suivant retombe en anglais sans raison), mais elle ne doit pas non plus fuiter d'information sur l'utilisateur partant.

Décider explicitement et **prouver par test** : la clé de langue survit-elle à `endSession()` ? Le comportement retenu doit être commenté dans `session.js`, dont les commentaires sont *load-bearing* (voir `project-context.md`).

### Library & framework requirements

| Dépendance | Version | Note |
|---|---|---|
| `vue-i18n` | **11.4.8** | Dernière stable pour Vue 3 (vérifié npm, 2026-07-28). Ne PAS prendre `legacy` (8.x, Vue 2) ni `rc`/`alpha` (9.x sont des canaux distincts, pas des successeurs de 11.x). |
| `vue` | 3.5.13 (installé) | — |
| `tailwindcss` | 4.0 (installé) | Tokens via `@theme` ; le contrat reste `DESIGN.md`. |
| `@vue/test-utils` | 2.4.11 (installé) | Harnais opérationnel, gabarit `src/components/__tests__/SyncFailureNotice.spec.js`. |

Aucune bibliothèque UI (PrimeVue, Naive…) : `DESIGN.md` acte des **tokens CSS maison**.

### File structure

- `frontend/src/style.css` — tokens globaux (REMPLACE la palette teal)
- `frontend/src/utils/stateMachine.js` — `STATE_COLORS` (ÉTENDRE, ne pas dupliquer)
- `frontend/src/i18n/` — catalogues EN/FR (NOUVEAU)
- `frontend/src/main.js` — enregistrement du plugin i18n. **Attention à l'ordre** : `main.js:17` appelle `useOfflineQueueStore().init()` avant `app.mount()`, et `installSessionExpiryListener` y est câblé. Ne pas déplacer ces appels.
- Tests colocalisés en `__tests__/`.

### Testing requirements

- **Vérification par mutation obligatoire** sur toute garde livrée (AC2 interdits, AC4 clé manquante) : retirer la garde, constater le rouge, restaurer, consigner. Règle du projet, née de trois correctifs de l'Epic 1 corrects en code et creux en preuve.
- **Piège Blob / fake-indexeddb** : ne pas « nettoyer » `vitest.setup.js`. L'ordre et le caractère dynamique des imports y sont *load-bearing*.
- `vitest.config.js` est séparé de `vite.config.js` **volontairement** — ne pas fusionner.
- Écrire du français **avec accents** : la garde d'encodage (`scripts/check-encoding.py`, job CI `Hygiène`) prouve la validité UTF-8 à chaque push. Le contournement « sans accents » est levé.
- Ne JAMAIS écrire un littéral à octets bruts (en-tête magique, classe de caractères de contrôle) : c'est ce qui a rendu `AuthView.vue` binaire et invisible à `grep`. Tableau d'octets explicite ou séquence d'échappement.

### Previous story intelligence

Epic 1 clos 10/10, **≈129 correctifs de revue sur 10 stories** — la revue est la moitié du travail. Enseignements transposables :

- La seule story frontend de l'Epic 1 (**1.9**) a demandé **trois passes de revue** et 29 correctifs, dont 5 hauts. Les preuves frontend sont plus fragiles : ordonnancement asynchrone, purge locale à moitié déplacée deux passes de suite parce que le test ne contrôlait que la moitié déjà déplacée.
- Story 1.10 : un test de convention par regex ratait les noms qualifiés et les appels multilignes — **une garde par regex doit être testée sur ses propres contournements**. Pertinent pour l'AC2 (interdits) et l'AC4 (chaînes littérales).
- Bundle QUALITÉ-CI (2026-07-28, juste avant cette story) : ESLint est désormais actif avec `reportUnusedDisableDirectives: 'error'` — une directive `eslint-disable` qui ne supprime plus rien **fait échouer le lint**. `no-await-in-loop` est active.

### Git intelligence

Cinq derniers commits : tous de préparation d'Epic 2 (bundle QUALITÉ-CI, décisions D1/D2/D4, tri du ledger). **Aucun code applicatif frontend n'a bougé** depuis la Story 1.9 — l'état du frontend est celui que 1.9 a laissé, augmenté des deux corrections de code mort du bundle QUALITÉ-CI (`props` dans `NewTransactionModal.vue`, `route`/`useRoute` dans `TransactionDetailView.vue`).

Barrières posées la veille, qui s'appliquent à cette story : `npm run lint --max-warnings 0` et le job CI `Hygiène (encodage / octets NUL)` sont **des required checks bloquants** sur `main` et `develop`.

### Project Structure Notes

- Story suivante (**2.2**, bibliothèque de composants) consomme directement les tokens de 2.1 : tout token manquant ici devient une valeur en dur là-bas.
- **2.7** (politique de session sur appareil partagé) s'exécute juste après 2.3 et touchera `stores/session.js` — d'où l'importance de documenter dès maintenant la décision sur la persistance de la langue.
- La couverture de tests de composant est de **3 sur 14** ; la règle du projet est désormais : toute story qui livre ou réécrit un composant apporte son test de composant.

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story 2.1`] — AC d'origine
- [Source: `_bmad-output/planning-artifacts/ux-designs/ux-Escrow_claude-2026-07-24/DESIGN.md`] — **contrat des tokens** (frontmatter = valeurs exactes) et règles d'usage
- [Source: `_bmad-output/planning-artifacts/architecture/architecture-Escrow_claude-2026-07-24/ARCHITECTURE-SPINE.md#AD-23`] — contrat i18n, échec CI sur clé manquante
- [Source: `_bmad-output/planning-artifacts/prds/prd-Escrow_claude-2026-07-24/prd.md#NFR-P24`] — bilingue EN/FR dès le MVP, par clés
- [Source: `_bmad-output/project-context.md`] — règles de test, piège Blob, règle de mutation, conventions d'encodage et de lint
- [Source: `frontend/src/style.css`, `frontend/src/utils/stateMachine.js`, `frontend/src/components/StateBadge.vue`] — état actuel à modifier

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
