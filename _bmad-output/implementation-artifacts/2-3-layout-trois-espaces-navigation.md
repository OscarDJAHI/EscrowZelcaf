---
baseline_commit: ac0449c6503893d3706c4d23626067ec375b66f3
---

# Story 2.3: Layout trois espaces et navigation responsive

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a utilisateur authentifié (client, arbitre ou opérateur),
I want accéder à l'espace correspondant à mon rôle dans une seule et même PWA, avec une navigation adaptée à mon appareil,
so that je retrouve immédiatement mes fonctions sans portail de connexion distinct ni interface incohérente.

**Portée réelle :** cette story pose le squelette applicatif que tous les écrans des dix epics suivants habiteront. Elle est aussi la première à toucher les **guards de route**, donc une surface de sécurité.

## Acceptance Criteria

**AC1 — Trois espaces sous une seule authentification**
**Given** l'auth JWT et les rôles existants
**When** un utilisateur se connecte via la vue de connexion existante intégrée au nouveau layout
**Then** une seule PWA sert trois espaces (app client, console d'arbitrage, back-office `/admin`) sous la même authentification, et l'utilisateur est routé vers l'espace de son rôle
**And** les guards de route bloquent l'accès aux espaces des autres rôles avec une **réponse uniforme anti-énumération (NFR-P9)** et un écran d'accès refusé i18n.

**AC2 — Navigation de l'espace client**
**Given** l'espace client
**When** il est affiché
**Then** la navigation expose 5 entrées — Accueil, Transactions, Wallet, Support, Profil — plus l'**emplacement** de la cloche de notifications (la cloche active arrive en Epic 8)
**And** les entrées dont les surfaces relèvent d'epics ultérieurs mènent à des écrans « à venir » i18n, **sans lien mort**.

**AC3 — Responsive client, mobile-first**
**Given** le client mobile-first
**When** la fenêtre passe les breakpoints **768** et **1024**
**Then** la navigation bascule d'onglets bas (mobile) à colonne 720 px puis sidebar avec zone de détail 2 colonnes, contenus limités à 720/960 px
**And** les cibles tactiles restent ≥ 44 px et le **focus clavier est visible** sur toute la navigation.

**AC4 — Console et back-office, desktop-first**
**Given** la console d'arbitrage et le back-office desktop-first
**When** ils sont ouverts à ≥ 768 px puis < 768 px
**Then** à large écran : sidebar fixe et contenus tableaux max 1280 px ; sous 768 px : un message explicite indique que la surface n'est pas optimisée mobile
**And** les shells des trois espaces sont couverts par des **tests de rendu** (guards, redirections par rôle).

**AC5 — PWA installable**
**Given** la PWA installée ou installable
**When** l'application est ajoutée à l'écran d'accueil puis relancée
**Then** manifest, icônes et écran de démarrage sont conformes et le **shell applicatif est précaché** et s'affiche sans réseau
**And** l'installabilité est vérifiée par un **audit automatisé** (Lighthouse PWA ou équivalent) dans le harnais existant.

## Tasks / Subtasks

- [x] **T1 — Lire avant d'écrire** (préalable)
  - [x] `frontend/src/router/index.js` : la garde `beforeEach` actuelle et sa logique de redirection profonde (`redirect=`), livrée en Story 1.9 et **protégée par `authRedirect.spec.js`** — ne pas la casser.
  - [x] `backend/src/main/java/com/zlecaf/escrow/domain/Role.java` : l'énumération RÉELLE des rôles.
  - [x] `frontend/vite.config.js` : configuration PWA existante (manifeste, Workbox, règle `NetworkFirst` sur `/api/`).
  - [x] `frontend/src/components/` : la bibliothèque livrée en 2-2, à assembler et non à réinventer.

- [x] **T2 — Décision de périmètre ARBITRATOR** (AC: 1) — voir « Le conflit de l'AC1 »
  - [x] Livrer le shell ET le guard de la console d'arbitrage, testés avec un rôle simulé.
  - [x] Documenter que l'espace est **inatteignable** tant que la Story 7-2 n'octroie pas le rôle, et pourquoi ce n'est pas un défaut.

- [x] **T3 — Guards de route par rôle** (AC: 1)
  - [x] Routage vers l'espace du rôle à la connexion.
  - [x] **Réponse uniforme** pour un accès à l'espace d'un autre rôle : même écran, même code, aucune différence observable entre « cet espace n'existe pas » et « il existe mais pas pour vous » (NFR-P9, gabarit `AntiEnumerationIntegrationTest` côté backend).
  - [x] Écran d'accès refusé i18n.
  - [x] **Ne pas régresser** la redirection profonde de 1.9 : `authRedirect.spec.js` doit rester vert.

- [x] **T4 — Shell client et navigation** (AC: 2,3)
  - [x] 5 entrées + emplacement de la cloche (inerte, l'Epic 8 l'activera).
  - [x] Écrans « à venir » i18n pour les surfaces d'epics ultérieurs — aucun lien mort.
  - [x] Bascule onglets bas → colonne → sidebar aux breakpoints 768 / 1024, largeurs 720/960 px.
  - [x] Cibles ≥ 44 px et **anneau de focus visible** — le token `--color-focus-ring` existe depuis 2-1.

- [x] **T5 — Shells console et back-office** (AC: 4)
  - [x] Sidebar fixe, tableaux max 1280 px à ≥ 768 px.
  - [x] Message explicite sous 768 px.

- [x] **T6 — PWA** (AC: 5)
  - [x] **Corriger `theme_color`** : le manifeste porte encore le teal `#0f766e` supprimé en 2-1.
  - [x] Précacher le shell (layout + navigation) et vérifier son affichage sans réseau.
  - [x] Audit d'installabilité automatisé — **voir « Le piège de l'AC5 » : le harnais n'existe pas.**

- [x] **T7 — Tests** (AC: 1,2,3,4)
  - [x] Tests de rendu des trois shells, guards et redirections par rôle (l'AC les exige nommément).
  - [x] Suite complète verte : **353 tests au départ**. `npm run lint` propre, `npm run verify:no-demo` vert.

## Dev Notes

### ⚠️ Le conflit de l'AC1 : `ARBITRATOR` n'existe pas

`backend/.../domain/Role.java` déclare **`{BUYER, SELLER, ADMIN}`**. Le rôle `ARBITRATOR` n'y est pas, et **aucun mécanisme ne l'octroie** — l'AC d'octroi/révocation appartient à la **Story 7-2**, que `sprint-status.yaml` marque déjà comme prérequis de 6-1.

Le spine avait vu venir exactement ceci. **AD-21, section `Prevents`, mot pour mot :

> « un espace (console, `/admin`) routé sur un rôle **qu'aucun mécanisme ne peut octroyer** »

et sa règle : « Le routage des trois espaces se fonde **exclusivement** sur ce rôle. »

**Conséquence à assumer, pas à masquer :** la console d'arbitrage sera **inatteignable** à la livraison de cette story, puisque aucun utilisateur ne peut porter le rôle. Ce n'est pas une raison de ne pas la livrer — le shell et le guard sont le vrai livrable, et ils doivent être **corrects et testés avec un rôle simulé**. C'est une raison de l'écrire noir sur blanc dans les notes de complétion, plutôt que de laisser croire que l'espace fonctionne.

**Ne pas « corriger » en ajoutant `ARBITRATOR` à l'énumération backend** : ce serait livrer un rôle à privilèges sans son mécanisme d'octroi audité, c'est-à-dire exactement ce qu'AD-21 interdit et ce que la Story 1.1 a passé un epic à fermer.

### ⚠️ Le piège de l'AC5 : le « harnais existant » n'existe pas

L'AC exige un audit d'installabilité « dans le harnais existant ». Vérifié : **aucun outillage de ce type n'est installé** — ni Lighthouse, ni Playwright, ni Puppeteer, ni axe. Le harnais existant est Vitest + jsdom, qui ne sait pas juger l'installabilité d'une PWA (elle dépend du service worker, du manifeste servi et du contexte sécurisé — jsdom n'en a aucun).

**Cela demande une décision de dépendance, à soumettre AVANT de l'installer** (règle du workflow, appliquée en 2-1 pour la police). Trois voies plausibles, à arbitrer :
1. `@lhci/cli` ou `lighthouse` en dépendance de développement, lancé sur le build en CI ;
2. une vérification plus modeste et sans dépendance : asserter le manifeste produit dans `dist/` (champs requis, icônes présentes aux bonnes tailles, `start_url`, `display`) et la présence du service worker — c'est moins qu'un audit Lighthouse, et il faut alors le dire ;
3. reporter l'audit à la Story 11-1 (pipeline CI) et livrer ici le manifeste conforme, avec la dette tracée.

**Ne pas installer d'outil sans accord**, et ne pas non plus cocher l'AC en prétendant qu'un test jsdom vaut audit d'installabilité.

### ⚠️ Le manifeste porte encore la palette supprimée

`vite.config.js` déclare `theme_color: '#0f766e'` — le **teal du POC**, supprimé de `style.css` par la Story 2.1 au profit du navy `#101E5A`. L'application installée s'ouvre donc sur une couleur que l'identité ratifiée a explicitement rejetée, et la barre système d'Android l'affiche. `background_color: '#ffffff'` est à confronter à `surface-page` (`#F6F7FB`).

C'est un reliquat que 2-1 n'a pas vu parce qu'elle ne regardait que `style.css`, et que cette story possède (AC5 lui donne le manifeste).

### Anti-régression : la garde de route existante est protégée par un test

`router/index.js` porte une garde livrée en Story 1.9 : un lien profond ouvert sans session revient à sa cible après connexion (`redirect=`), avec une protection contre la redirection ouverte. Elle est asservie par `src/views/__tests__/authRedirect.spec.js`, qui vérifie notamment le rejet des cibles protocol-relative et de la forme antislash.

Ajouter des guards de rôle **par-dessus** cette garde, sans en casser la logique, est le vrai exercice de T3. Le test existant doit rester vert sans être modifié.

### Anti-réinvention : la bibliothèque de 2-2 est là pour ça

`AppButton`, `AppCard`, `WalletCard`, `AppSkeleton`, `StateBadge`, `LanguageSwitcher` existent et sont testés. La navigation, les écrans « à venir » et l'écran d'accès refusé les **assemblent**. Un bouton écrit à la main dans un shell serait la régression que 2-2 vient d'éliminer.

`ComponentGalleryView` est un précédent utile de vue autonome — et rappelle que toute route de développement doit rester hors du build de production (`npm run verify:no-demo`).

### La décision d'espacement, à trancher ICI

Reportée deux fois. La rampe de `DESIGN.md` (5=24 px, 6=32, 7=48) diverge de Tailwind (20/24/28) à partir de 5 ; les tokens sont exposés en `--space-*` sans écraser l'échelle Tailwind, avec la correspondance documentée dans `style.css`. Aucun composant de 2-1 ni de 2-2 n'a eu besoin de ces valeurs.

**Cette story manipule des gouttières** (`gutter` 16 px mobile, 24 px desktop) et des largeurs maximales : c'est le premier cas réel. Trancher, et écrire la décision dans `style.css` à côté du commentaire existant.

### Règles héritées, non négociables

- **Aucune chaîne littérale** — la garde scanne gabarits, blocs script et modules, littéraux simples, doubles et backticks.
- **Aucune clé brute rendue** — passer par `@/i18n/labels` ; `$t(null)` LÈVE.
- **Aucune ombre hors `shadow-floating`** — la garde vise désormais la classe ET la propriété CSS (`boxShadow` en ligne compris).
- **Aucune couleur d'état redéfinie par un écran** — lire `STATE_COLORS` / `OFFLINE_CLASSES` / `SYNCING_CLASSES`.
- **Classes Tailwind en toutes lettres**, jamais composées : l'extraction est statique.
- **`type="button"` sur tout `<button>`** qui n'est pas un envoi de formulaire — l'oubli a été trouvé en revue de 2-2.
- **Formats de date et de nombre liés à `locale.value`**, jamais à la langue du navigateur.
- **Français avec accents** ; **vérification par mutation** sur toute garde livrée.

### Previous story intelligence

2-1 et 2-2 ont demandé **cinq campagnes de revue** au total. Ce qu'elles ont appris, applicable ici :

- **Une garde qu'aucune assertion ne relit n'est pas une garde.** Trois gardes se sont révélées fausses sur ces deux stories — sentinelle dans un commentaire supprimée par la minification, garde d'élévation aveugle à la propriété CSS, garde anti-prose aveugle aux backticks. **Chaque fois, seule la mutation l'a montré.**
- **Un `try/catch` ne garde que contre ce qui lève.** `Intl.format(NaN)` rend « $NaN », `new Date('x').toLocaleString()` rend « Invalid Date » : aucun ne lève.
- **Un composant sans test peut perdre son correctif en silence.**
- **Ne jamais restaurer par `git checkout --`** un fichier porteur de travail non commité.
- **Une case cochée n'est pas une preuve** — un compte de tests annoncé s'est révélé faux deux fois.
- **Prévoir la revue comme la moitié du travail.** Sur une story qui touche des guards de sécurité, c'est un minimum.

### Point ouvert remonté du design

`DESIGN.md` définit `primary-hover` mais **aucun `danger-hover`** ; le survol de la variante danger passe par l'opacité, faute de token. À arbitrer si cette story introduit d'autres surfaces dangereuses.

### File structure

- `frontend/src/router/index.js` — MODIFIER (guards de rôle par-dessus la garde existante)
- `frontend/src/layouts/` — shells des trois espaces (NOUVEAU)
- `frontend/src/views/` — écrans « à venir », accès refusé (NOUVEAUX)
- `frontend/vite.config.js` — manifeste PWA (theme_color, précache du shell)
- Tests colocalisés en `__tests__/`

### References

- [Source: `_bmad-output/planning-artifacts/epics.md#Story 2.3`] — AC d'origine
- [Source: `.../ARCHITECTURE-SPINE.md#AD-21`] — rôles, octroi serveur uniquement, et l'avertissement sur un espace routé sur un rôle non octroyable
- [Source: `.../DESIGN.md`] — gouttières, largeurs maximales, cibles tactiles, focus
- [Source: `_bmad-output/implementation-artifacts/2-1-…md`, `2-2-…md`] — stories précédentes et leurs cinq campagnes de revue
- [Source: `_bmad-output/project-context.md`] — règle de mutation, conventions d'encodage, de lint et de test

## Dev Agent Record

### Agent Model Used

claude-opus-5 (interactif, flux BMad classique)

### Debug Log References

Trois défauts trouvés par les tests que la story exigeait, chacun invisible autrement :

1. **Fuite d'URL sur le refus d'accès.** `return { name: 'not-found' }` semble préserver
   l'adresse demandée. Le catch-all vaut `/:pathMatch(.*)*` : le résoudre sans paramètre
   produit `/`. L'espace interdit renvoyait donc vers `/` pendant qu'une adresse inconnue,
   elle, gardait la sienne — même écran, deux URL, oracle rouvert. Le test qui compare les
   deux chemins était rouge avant correction, vert après (`params: { pathMatch: … }`).
   L'assertion sur les seuls NOMS de route, elle, passait dans les deux cas : c'est le
   chemin qui portait la fuite.

2. **`ClientShell` n'était monté par personne.** Le composant existait, ses sept tests
   passaient, et aucune des cinq routes de l'espace client ne le rendait : la navigation
   n'existait qu'en test. Un shell testé isolément prouve qu'il fonctionne, pas qu'il est
   branché. Résolu en déduisant le shell de `meta.space` dans `App.vue`, ce qui rend
   l'oubli structurellement impossible pour les écrans à venir, et vérifié par
   `appShell.spec.js` qui monte l'application entière avec le vrai routeur.

3. **Une assertion creuse écrite par moi-même.** Le test « un seul sélecteur de langue »
   visait un `data-testid` qui n'existe pas : `findAll` renvoyait 0, et « au plus un »
   était satisfait par le vide. Remplacé par un compte EXACT sur le `role="group"` que le
   composant porte réellement. Quatrième occurrence du même motif sur cet epic.

**Session incohérente.** `RecoveryView.spec.js` est passé au rouge sur une fixture sans
`role`. Plutôt que de rustiner le test, traité comme un vrai état — le ledger de la
Story 1.9 documente un `escrow_token` qui survit à un `escrow_user` illisible. Un refus
aurait condamné l'écran de récupération, seul endroit d'où l'utilisateur récupère des
fichiers n'existant nulle part ailleurs. Le routeur renvoie donc vers la connexion, en
conservant la cible, et sans que la réponse dépende de cette cible.

**Vérification par mutation** (règle du projet), 10 mutations, toutes rouges puis vertes
après restauration :
- `verify:pwa` — couleur dérivant du nuancier, icône déclarée 512 mesurant 192, icône
  absente du build, `display: browser` + `short_name` retiré ;
- shells — entrée pointant vers une route inexistante, cloche perdant son `aria-disabled`,
  message de refus nommant l'espace ;
- `App.vue` — sélecteur de langue redevenu inconditionnel, espace client privé de shell,
  refus enveloppé dans le shell client.

### Completion Notes List

- **AC1 — ARBITRATOR livré mais inatteignable, comme prévu par T2.** Le mapping, le guard,
  le shell et la redirection sont en place et testés avec un rôle simulé. `Role.java` ne
  l'émet pas : l'octroi appartient à la Story 7-2 (AD-21). Ce qui manque est le porteur de
  la clé, pas la porte. Signalé en commentaire dans `spaces.js`, `index.js` et la vue.
- **AC5 — écart assumé et non comblé.** L'AC demande un audit Lighthouse d'installabilité.
  Ce qui est livré est `npm run verify:pwa` : champs requis du manifeste, icônes présentes
  ET aux dimensions RÉELLES (lecture de l'en-tête PNG — une taille déclarée n'est pas une
  taille vérifiée), couleurs recoupées avec les tokens de `style.css`, service worker émis
  avec `index.html` dans son pré-cache. **C'est moins que Lighthouse** : ni HTTPS, ni
  portée du service worker à l'exécution, ni performances. En contrepartie il tourne en CI
  sans Chromium. L'affichage hors réseau est vérifié par la présence du shell au pré-cache,
  **pas** par un rendu navigateur réel — cet essai reste à faire.
- **Décision d'espacement tranchée** (reportée deux fois, cette story étant la première à
  poser des marges de page) : toute région de contenu porte `p-4 lg:p-6`, soit 16 px puis
  24 px à partir de 1024 px, `lg:p-6` valant `--space-gutter-desktop`. Le raisonnement —
  pourquoi des classes Tailwind plutôt que `var(--space-gutter)`, et pourquoi `lg` et non
  `md` — est écrit dans `style.css` à côté des tokens.
- **`theme_color` corrigé** : le manifeste portait encore le teal `#0f766e` que la Story 2.1
  avait retiré du nuancier. Il vaut désormais `#101e5a` (`--color-brand-navy`), et
  `background_color` `#f6f7fb` (`--color-surface-page`). Ces deux copies sont inévitables —
  l'OS lit le manifeste avant toute feuille de style — donc `verify:pwa` échoue si elles
  dérivent, ce qu'aucun contrôle ne faisait jusqu'ici.
- **Anti-régression tenue** : `authRedirect.spec.js` (redirection profonde de la Story 1.9)
  est resté vert du début à la fin.
- **Point ouvert reconduit** : toujours aucun token `danger-hover` dans `DESIGN.md`. Cette
  story n'a introduit aucune surface dangereuse ; l'arbitrage reste dû.
- Suite : **403 tests / 29 fichiers** (353 au départ, soit +50). `npm run lint` propre,
  `npm run verify:no-demo` et `npm run verify:pwa` verts, tous deux câblés en CI.

### File List

**Nouveaux**
- `frontend/src/router/spaces.js` — module pur : rôle → espace, décision d'accès uniforme, navigation des espaces desktop
- `frontend/src/router/__tests__/spaces.spec.js`
- `frontend/src/router/__tests__/guards.spec.js`
- `frontend/src/layouts/ClientShell.vue`
- `frontend/src/layouts/DesktopShell.vue`
- `frontend/src/layouts/__tests__/shells.spec.js`
- `frontend/src/__tests__/appShell.spec.js`
- `frontend/src/views/AccessDeniedView.vue`
- `frontend/src/views/ComingSoonView.vue`
- `frontend/src/views/ArbitrationHomeView.vue`
- `frontend/src/views/AdminHomeView.vue`
- `frontend/scripts/verify-pwa.mjs`

**Modifiés**
- `frontend/src/App.vue` — résolution du shell depuis `meta.space`
- `frontend/src/router/index.js` — `meta.space` par route, guards de rôle, catch-all servant l'écran de refus
- `frontend/src/style.css` — décision de gouttière
- `frontend/src/i18n/fr.json`, `frontend/src/i18n/en.json`
- `frontend/vite.config.js` — `theme_color`, `background_color`, `globPatterns`
- `frontend/package.json` — script `verify:pwa`
- `frontend/src/views/__tests__/RecoveryView.spec.js` — fixture dotée d'un rôle
- `.github/workflows/ci.yml` — `verify:pwa` après le build

### Change Log

| Date | Version | Description |
|---|---|---|
| 2026-07-28 | 0.1 | Implémentation de la Story 2.3 : trois espaces, guards par rôle, réponse uniforme (NFR-P9), shells client et desktop, correction du manifeste PWA et garde `verify:pwa`, décision de gouttière. Statut → review. |
