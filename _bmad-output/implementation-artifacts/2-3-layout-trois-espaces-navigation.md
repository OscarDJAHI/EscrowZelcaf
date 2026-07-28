# Story 2.3: Layout trois espaces et navigation responsive

Status: ready-for-dev

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

- [ ] **T1 — Lire avant d'écrire** (préalable)
  - [ ] `frontend/src/router/index.js` : la garde `beforeEach` actuelle et sa logique de redirection profonde (`redirect=`), livrée en Story 1.9 et **protégée par `authRedirect.spec.js`** — ne pas la casser.
  - [ ] `backend/src/main/java/com/zlecaf/escrow/domain/Role.java` : l'énumération RÉELLE des rôles.
  - [ ] `frontend/vite.config.js` : configuration PWA existante (manifeste, Workbox, règle `NetworkFirst` sur `/api/`).
  - [ ] `frontend/src/components/` : la bibliothèque livrée en 2-2, à assembler et non à réinventer.

- [ ] **T2 — Décision de périmètre ARBITRATOR** (AC: 1) — voir « Le conflit de l'AC1 »
  - [ ] Livrer le shell ET le guard de la console d'arbitrage, testés avec un rôle simulé.
  - [ ] Documenter que l'espace est **inatteignable** tant que la Story 7-2 n'octroie pas le rôle, et pourquoi ce n'est pas un défaut.

- [ ] **T3 — Guards de route par rôle** (AC: 1)
  - [ ] Routage vers l'espace du rôle à la connexion.
  - [ ] **Réponse uniforme** pour un accès à l'espace d'un autre rôle : même écran, même code, aucune différence observable entre « cet espace n'existe pas » et « il existe mais pas pour vous » (NFR-P9, gabarit `AntiEnumerationIntegrationTest` côté backend).
  - [ ] Écran d'accès refusé i18n.
  - [ ] **Ne pas régresser** la redirection profonde de 1.9 : `authRedirect.spec.js` doit rester vert.

- [ ] **T4 — Shell client et navigation** (AC: 2,3)
  - [ ] 5 entrées + emplacement de la cloche (inerte, l'Epic 8 l'activera).
  - [ ] Écrans « à venir » i18n pour les surfaces d'epics ultérieurs — aucun lien mort.
  - [ ] Bascule onglets bas → colonne → sidebar aux breakpoints 768 / 1024, largeurs 720/960 px.
  - [ ] Cibles ≥ 44 px et **anneau de focus visible** — le token `--color-focus-ring` existe depuis 2-1.

- [ ] **T5 — Shells console et back-office** (AC: 4)
  - [ ] Sidebar fixe, tableaux max 1280 px à ≥ 768 px.
  - [ ] Message explicite sous 768 px.

- [ ] **T6 — PWA** (AC: 5)
  - [ ] **Corriger `theme_color`** : le manifeste porte encore le teal `#0f766e` supprimé en 2-1.
  - [ ] Précacher le shell (layout + navigation) et vérifier son affichage sans réseau.
  - [ ] Audit d'installabilité automatisé — **voir « Le piège de l'AC5 » : le harnais n'existe pas.**

- [ ] **T7 — Tests** (AC: 1,2,3,4)
  - [ ] Tests de rendu des trois shells, guards et redirections par rôle (l'AC les exige nommément).
  - [ ] Suite complète verte : **353 tests au départ**. `npm run lint` propre, `npm run verify:no-demo` vert.

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

### Debug Log References

### Completion Notes List

### File List
