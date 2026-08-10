# Story 2.7: Politique de session sur appareil partagé (NFR-P8)

Status: ready-for-dev

<!-- Contexte créé le 2026-08-10. Faits vérifiés contre le code à cette date. -->

## Story

As a personne utilisant la plateforme depuis un poste partagé,
I want que ma session ne survive pas à mon départ,
so that la personne suivante ne se retrouve pas silencieusement authentifiée à ma place, avec mes transactions et mes preuves (NFR-P8).

**Origine :** décision produit **D4** tranchée le 2026-07-28 par Oscard — *les deux mesures, pas l'une ou l'autre*. Le mode de défaillance DOMINANT de l'appareil partagé — fermer l'onglet sans se déconnecter — est resté entier après la Story 1.9 : `ttl-seconds: 86400`, jeton en `localStorage`, aucune expiration d'inactivité. Cette story porte la politique **et ses quatre conséquences directes**, qui sont des implémentations de cette politique et non des correctifs indépendants (ex-bundle SESSION-PARTAGÉE du ledger).

---

## Acceptance Criteria

### AC1 — Le jeton meurt avec l'onglet
**Given** un utilisateur qui se connecte sans cocher « rester connecté »
**When** il ferme l'onglet puis qu'une autre personne rouvre l'application sur le même poste
**Then** aucune session n'est restaurée — le jeton vit en `sessionStorage` et meurt avec l'onglet
**And** l'option « rester connecté », explicite et **non cochée par défaut**, bascule le stockage en `localStorage` pour les appareils personnels.

### AC2 — Expiration d'inactivité, minuterie ET démarrage
**Given** une session active laissée sans interaction
**When** le délai d'inactivité est dépassé, que l'onglet soit resté ouvert ou que l'application soit rouverte après ce délai
**Then** la session est terminée par le chemin existant `endSession` (purge locale complète avant l'appel réseau) et l'utilisateur est renvoyé vers l'authentification **avec un motif affiché**
**And** le contrôle s'exerce aussi **AU DÉMARRAGE**, pas seulement par minuterie — sinon un onglet rouvert échappe à la mesure.

### AC3 — Propagation inter-onglets
**Given** plusieurs onglets ouverts sur le même appareil
**When** l'utilisateur se déconnecte, ou que sa session expire, dans l'un d'eux
**Then** les autres onglets terminent leur session sans intervention et cessent d'afficher une interface authentifiée
**And** le mécanisme retenu est **documenté avec sa règle d'autorité entre onglets**.

### AC4 — Déconnexion à attente bornée
**Given** une déconnexion sur un réseau qui ne répond pas (portail captif, DNS suspendu)
**When** l'utilisateur clique sur « se déconnecter »
**Then** la navigation vers l'écran d'authentification n'attend pas indéfiniment la révocation serveur — l'attente est **bornée**, `keepalive` garantissant que la requête aboutit même après la navigation
**And** l'hygiène locale reste inchangée : tout le local est purgé **AVANT** l'appel réseau, ordre déjà prouvé par test.

### AC5 — Profil illisible traité explicitement
**Given** un jeton présent dont le profil `escrow_user` est illisible
**When** l'application démarre
**Then** cet état incohérent est traité explicitement — soit le jeton est effacé, soit la file signale « session incohérente » — au lieu d'être lu comme « personne n'est connectée », ce qui fait naître des entrées hors-ligne orphelines, supprimées à la déconnexion suivante.

### AC6 — Époque de session contre les courses A→B
**Given** une réponse de lecture émise pendant la session de A
**When** elle se résout après la connexion de B
**Then** elle est rejetée : une **époque de session monotone**, portée par `stores/session.ts`, est capturée à l'émission et comparée à la résolution
**And** le compteur anti-course d'`evidence` ne peut plus être rembobiné à 0 par un `$reset()` au point de rendre une réponse de A égale à la première lecture de B.

### AC7 — Preuve par mutation, y compris du câblage
**Given** la politique livrée
**When** la suite de tests est exécutée
**Then** chaque garde est prouvée **par mutation** (règle du `project-context.md`) — en particulier le test du **bouton de déconnexion** et du **câblage de `main.ts`**, aujourd'hui sans aucune couverture alors que ce sont les deux seuls appelants de production des primitives de session.

> **Correction de l'AC7 par rapport à `epics.md` :** l'AC dit « câblage de `main.js` ». **Ce fichier n'existe plus** — la migration TypeScript (commits `47c075f`→`35e1a0c`) l'a renommé `main.ts`. Voir le tableau de conversion des chemins en Dev Notes ; tous les chemins `.js` des artefacts amont sont périmés dans leur extension, **aucun ne l'est sur le fond**.

---

## Décisions de spécification

Aucune source amont ne tranchait les points suivants. Ils sont tranchés **ici** ; deux attendent confirmation d'Oscard (marqués ⚠️, listés en fin de fichier).

### D-A — L'expiration d'inactivité tombe dans la sémantique `expired`, pas `logout`

`endSession` a **déjà deux modes aux périmètres différents** (Story 1.9, `session.ts:100-213`) :
- `logout` — purge tout : mémoire, identifiants, **IndexedDB de l'utilisateur**, cache de lecture, marqueur `escrow_last_user`, **puis** révocation serveur.
- `expired` — purge mémoire et identifiants seulement, sort avant IndexedDB (`session.ts:158`).

L'inactivité prend la sémantique **`expired`**. Motif : détruire la file offline parce que quelqu'un est parti déjeuner, c'est perdre du travail non envoyé — **AD-9 interdit la perte silencieuse de fichier**, et **UX-DR32 exige que la file survive à la ré-authentification**. L'hygiène d'appareil partagé est assurée par la mort du jeton et par le fait que la file est **scopée par utilisateur** (B ne peut pas rejouer les entrées de A). Ce qui protège, c'est le scoping, pas la destruction.

> **CONFIRMÉ par Oscard le 2026-08-10** (question Q2, close). Décision normative, à ne pas rouvrir en développement ni en revue sans la reposer explicitement.

Le nouveau mode s'appelle **`'idle'`** et non `'expired'` : il doit être distinguable pour afficher son propre motif (AC2), alors que `'expired'` reste le 403 nu.

### D-B — `BroadcastChannel` est obligatoire, l'écouteur `storage` ne suffit pas

L'évidence du ledger (entrée E1) proposait « un écouteur `storage` sur la clé de jeton ». **Ce correctif est périmé par D4 elle-même** : l'événement `storage` **ne se déclenche pas entre onglets pour `sessionStorage`**, qui est cloisonné par onglet. Avec AC1, le chemin par défaut n'émettrait aucun événement.

Mécanisme retenu : **`BroadcastChannel('escrow-session')`**, avec repli silencieux si l'API est absente (la propagation dégrade, elle ne casse pas).

**Règle d'autorité entre onglets (AC3) :** l'onglet où l'action a lieu est l'**émetteur unique** de la révocation serveur. Les onglets récepteurs exécutent une terminaison **purement locale** — ils n'appellent ni `logoutUser` ni aucun endpoint. Motif : **NFR-P2** rate-limite `/auth/*` ; N onglets produisant N révocations transformeraient une déconnexion en rafale anti-bruteforce contre l'utilisateur lui-même.

### D-C — Le bouton de déconnexion migre dans les shells

`sprint-status.yaml:206` affirme « 2-3 done, le bouton de déconnexion a sa place définitive ». **Cette affirmation est fausse et vérifiée fausse** : `ClientShell.vue` et `DesktopShell.vue` ne portent aucune déconnexion (`grep -rn "logout\|endSession" frontend/src/layouts` → 0). Le bouton est resté dans `views/DashboardView.vue:101-106`.

Conséquence non relevée jusqu'ici : **les rôles ARBITRATOR et ADMIN n'ont aujourd'hui aucun moyen de se déconnecter par l'interface** — `DesktopShell` sert leurs deux espaces et ne porte pas le bouton. UX-DR20 pose « une seule auth pour trois espaces » ; une politique de session qui ne s'applique qu'à un espace n'est pas une politique.

Le bouton migre donc dans les deux shells. Il est **tokenisé au passage** (aujourd'hui `border-gray-300 text-gray-600 hover:bg-gray-50`, classes brutes non conformes à 2-1) et reçoit `type="button"` (règle 2-2).

### D-D — Le périmètre de la purge est explicite, et trois choses survivent

Aucune source ne définissait « purge locale complète ». Elle **exclut nommément** :
1. `escrow_locale` — préférence d'**appareil**, pas de session. Décision de la Story 2.1, commentée en `session.ts:189-199` : *« ne pas ajouter `escrow_locale` à la purge sans rouvrir la décision »*. Verrouillé par `session.spec.ts:155`.
2. Le **cache de lecture** en mode `expired`/`idle` — AD-27 : il survit à la ré-authentification.
3. Les **entrées IndexedDB des autres utilisateurs** du même appareil — invariant 1.9, verrouillé par `session.spec.ts:211`.

Et elle **inclut** en mode `logout` : mémoire des stores, identifiants, IndexedDB de l'utilisateur qui part + les orphelines, cache de lecture, marqueur `escrow_last_user`.

### D-E — Ce que la story ne revendique PAS

**Interdiction d'écrire une AC absolue.** Deux chemins de fuite restent ouverts et sont **hors périmètre**, routés vers la Story 11-3 / revue PWA :
- **E2** — l'index d'expiration Workbox conserve les URL `/api/` du partant (`vite.config.ts:66-71`) ; `purgeReadCache()` ne le touche pas.
- **E9** — une réponse `/api/` en vol au moment de la purge **recrée** `escrow-api-cache` derrière elle (stratégie `NetworkFirst`, écriture par le service worker hors de tout store Pinia). **L'époque de session de l'AC6 ne couvre pas ce chemin.**

Ne pas écrire « aucune donnée de A ne survit ». L'AC1 de la Story 1.9 disait « à aucun instant du parcours » et a été relevée en revue comme non tenue au sens strict. **Écrire l'écart plutôt que le cocher.**

---

## Tasks / Subtasks

- [ ] **T0 — Lire avant d'écrire** (préalable bloquant)
  - [ ] `frontend/src/stores/session.ts` en entier — il porte **deux avertissements « Story 2.7 réécrira ce fichier »** (`:198-199`) et ses commentaires sont *load-bearing*.
  - [ ] `frontend/src/api/client.ts:19-26` (intercepteur requête), `:35` (verrou `sessionExpiryAnnounced`), `:61` (`concernsCurrentSession`), `:89` (`envelopeWasParsed`), `:111-126` (intercepteur réponse).
  - [ ] `frontend/src/stores/auth.ts:32` (`loadStoredUser`), `:51` (state), `:63-68` (`persist`), `:181` (`clearSession`), **`:213-224`** (l'avertissement sur `logout()` — chemin hérité, aucun appelant de production).
  - [ ] `frontend/src/stores/__tests__/session.spec.ts:243-293` — le test d'ordonnancement. **Le comprendre avant de toucher `endSession`.**
  - [ ] `frontend/src/i18n/index.ts:22-56` — le patron de lecture résiliente du stockage (la simple LECTURE lève sous Safari « bloquer tous les cookies »).

- [ ] **T1 — Substrat de stockage commutable** (AC: 1)
  - [ ] Créer une indirection **unique** pour le jeton et le profil. Aujourd'hui **3 points de lecture** (`client.ts:20`, `client.ts:62`, `auth.ts:51`) et **2 d'écriture** (`auth.ts:64-65`) accèdent directement à `localStorage`. Aucun ne doit subsister.
  - [ ] Le choix de substrat est lui-même persisté en `localStorage` (il doit survivre à la fermeture de l'onglet pour que « rester connecté » tienne au retour) sous une clé dédiée. **Il ne contient aucune donnée personnelle.**
  - [ ] Reproduire la **résilience d'accès** de `i18n/index.ts` pour `sessionStorage` : l'accès à la propriété peut lever, pas seulement la méthode.
  - [ ] ⚠️ **`client.ts:61` (`concernsCurrentSession`) est un correctif de la Story 1.9, pas un détail de lecture.** Il compare l'en-tête `Authorization` de la requête échouée au jeton **stocké**, pour empêcher un 401/403 nu **retardataire de la session précédente** de détruire la session **neuve** — et il **échoue ouvert**. Si la lecture ne suit pas le changement de substrat, ce correctif redevient inopérant en silence : la garde lira un stockage vide, ne reconnaîtra plus la session courante, et le défaut de 1.9 se rouvre **avec une suite verte**. Migrer cette lecture **et** vérifier par mutation que `client.spec.ts` rougit.
  - [ ] **Exporter `LAST_USER_STORAGE_KEY`** depuis `session.ts:57` (aujourd'hui `const` privé) et remplacer le littéral dupliqué de `session.spec.ts:55`. Sans cela, un renommage laisserait les assertions vertes.

- [ ] **T2 — Case « rester connecté »** (AC: 1)
  - [ ] Dans `AuthView.vue`, mode login uniquement. **Non cochée par défaut.** Clé i18n EN+FR, cible ≥ 44 px, focus visible (UX-DR38).
  - [ ] ⚠️ **Ne pas fermer la porte de la Story 2.6** : le TOTP s'insérera **entre** la soumission et l'émission du jeton. Le choix doit être mémorisé **avant** d'avoir un jeton en main.

- [ ] **T3 — Expiration d'inactivité** (AC: 2)
  - [ ] **Délai = 15 minutes** (tranché par Oscard le 2026-08-10). **Constante nommée et exportée**, jamais un littéral recopié : un seuil présent à deux endroits diverge au premier ajustement, et le test doit lire la même constante que la production.
  - [ ] Minuterie réarmée sur interaction + **contrôle au démarrage** (`main.ts`). Le contrôle au démarrage est ce qui attrape l'onglet rouvert — et le shell est **précaché** (UX-DR46), donc l'UI authentifiée s'affiche **avant tout appel API**.
  - [ ] 🔴 **« Inactivité » ≠ « absence de clic ». Un versement de preuve en cours EST de l'activité.** Un dépôt de 10 Mo (`PlatformLimits`) sur une liaison de corridor lente peut dépasser 15 minutes **sans une seule interaction** : l'utilisateur clique « déposer » puis attend. Une minuterie naïve tuerait la session **au milieu du transfert** et détruirait exactement le travail que la décision Q2 protège. La preuve d'activité doit donc inclure **les requêtes en vol**, pas seulement les événements d'entrée. Couvrir ce cas par un test explicite — c'est le scénario le plus coûteux de la story s'il est manqué, et le plus silencieux.
  - [ ] Horodatage de dernière activité persisté dans le même substrat que le jeton.
  - [ ] Ajouter le mode `'idle'` à `EndSessionReason` (`session.ts:90`). **Traiter explicitement le mode inconnu** : la revue 1.9 a relevé que `explicit = reason === 'logout'` faisait tomber tout mode nouveau **en silence** sur le chemin conservateur (`spec-1-9:150`). Ajouter un mode rouvre ce défaut mécaniquement.
  - [ ] Motif affiché : clé i18n EN+FR, **motif ET action de reprise** (UX-DR28), passé par le **canal d'annonces a11y centralisé** (convention Frontend du spine) — ne pas créer un second canal.
  - [ ] ⚠️ **Ne pas réarmer le verrou `sessionExpiryAnnounced` par minuterie.** `client.ts:30-33` documente que seul `beginSession` l'abaisse. La minuterie d'inactivité est un mécanisme distinct.

- [ ] **T4 — Propagation inter-onglets** (AC: 3)
  - [ ] `BroadcastChannel('escrow-session')`, repli silencieux si absent.
  - [ ] Récepteurs : terminaison **locale seule**, aucun appel réseau (voir D-B, motif NFR-P2).
  - [ ] Pas de rechargement brutal — la convention Frontend du spine interdit le reload silencieux ; l'onglet doit terminer sa session de façon **observable**.
  - [ ] Documenter la règle d'autorité **dans le code**, en commentaire load-bearing.

- [ ] **T5 — Attente bornée sur la révocation** (AC: 4)
  - [ ] Borner l'attente de `auth.revokeOnServer` / `logoutUser` (`api/auth.ts:77-80`, aujourd'hui `fetch` + `keepalive`, **aucun `signal`**).
  - [ ] ⚠️ **NEVER (spec 1.9:47) : ne pas remplacer `logoutUser` par un appel axios.** Le `fetch` + `keepalive` est une décision de la revue 1.6. `keepalive` est précisément ce qui garantit que la requête aboutit après la navigation.
  - [ ] ⚠️ **Ce changement peut casser `session.spec.ts:243-293`** : borner l'attente change le nombre d'`await` devant la révocation. C'est exactement ce qui a fait virer la suite au rouge en passe 3 de la Story 1.9, avec un correctif pourtant bon. Vérifier ce test avant/après et le réparer sur `vi.waitFor`, jamais sur un nombre fixe de macrotâches.
  - [ ] `DashboardView.vue:53-63` porte un **commentaire justificatif périmé** (« la navigation avortait la requête en vol » — vrai de `window.location.href`, faux de `router.replace`). Le corriger ou le supprimer : *« une justification périmée est ce qui fait reconduire une attente non bornée pour toujours »*.
  - [ ] L'attente visible est libellée avec son délai annoncé (UX-DR26).

- [ ] **T6 — Trois états du jeton** (AC: 5)
  - [ ] `loadStoredUser()` (`auth.ts:32`) rend `null` sur échec de `JSON.parse` alors que le jeton survit. Distinguer **trois** états : jeton absent / jeton + profil lisible / jeton + profil illisible.
  - [ ] Le **routeur a déjà** une branche « session incohérente » (2-3, `router/index.ts:181`). **La file offline ne l'a pas** : `init()`/`hydrate()` lisent `user?.id == null` comme « pas de session », et chaque mise en file estampille `userId: undefined` → entrées orphelines.
  - [ ] ⚠️ La 2-4 introduit un **quatrième** état — compte non vérifié. La logique de démarrage doit l'accueillir sans réécriture.

- [ ] **T7 — Époque de session** (AC: 6)
  - [ ] Compteur monotone porté par `session.ts`, capturé à l'émission, comparé à la résolution. `grep "epoch\|sessionEpoch" frontend/src/stores/session.ts` → **0** aujourd'hui.
  - [ ] Câbler sur `stores/escrow.ts:83` (`this.transactions = await fetchTransactions()` — **aucune garde de session**, seulement un `issuedAt` horodaté).
  - [ ] `evidence.ts:25` : `loadSeq: 0` dans le `state()`, donc `$reset()` le rembobine (appelé en `session.ts:149` et `:256`). L'époque doit rendre ce rembobinage inoffensif — **ne pas supprimer `loadSeq`**, il protège aussi des courses intra-session (`withdrawEvidence` l'incrémente en `:89`).
  - [ ] ⚠️ **L'époque est une garde client complémentaire, jamais un substitut d'AD-3.** L'autorisation reste serveur.

- [ ] **T8 — Bouton de déconnexion dans les shells** (AC: 3, 7 — voir D-C)
  - [ ] Migrer de `DashboardView.vue:101-106` vers `ClientShell.vue` et `DesktopShell.vue`.
  - [ ] Tokeniser (classes brutes aujourd'hui), `type="button"`, cible ≥ 44 px, focus visible.
  - [ ] ⚠️ **Point ouvert de 2-2 reconduit deux fois** : `DESIGN.md` définit `primary-hover` mais **aucun `danger-hover`**. Si le bouton prend une variante danger, c'est le moment d'arbitrer plutôt que de passer par l'opacité.
  - [ ] ⚠️ **Ne jamais recâbler ce bouton sur `auth.logout()`.** `auth.ts:213-224` porte l'avertissement : cette action ne purge que deux clés et rouvrirait quatre reports fermés par la 1.9 — **avec une suite verte**.
  - [ ] Conserver le filet `window.location.assign('/auth')` (`DashboardView.vue:77-78`) si `router.replace` échoue (chunk `AuthView` introuvable), et **le tester** : il est aujourd'hui entièrement mort pour la suite.

- [ ] **T9 — Tests** (AC: 1..7)
  - [ ] `DashboardView`/shells : premier test du bouton de déconnexion. Gabarit le plus proche : `views/__tests__/RecoveryView.spec.ts:497-523` (`trigger('click')` avec confirmation/annulation).
  - [ ] **Câblage `main.ts`** : `src/__tests__/appShell.spec.ts` est le véhicule qui existe déjà (monte l'application entière avec le vrai routeur) et ne mentionne ni `logout` ni `endSession`. *« Un shell testé isolément prouve qu'il fonctionne, pas qu'il est branché. »*
  - [ ] Minuteries factices : **un seul fichier** du dépôt en fait usage (`escrow.offline.spec.ts:304-355`) et **uniquement `setSystemTime`** — aucun `advanceTimersByTime` nulle part. Patron à introduire.
  - [ ] ⚠️ **`vi.waitFor` s'appuie sur les minuteries RÉELLES et se bloque sous `vi.useFakeTimers()`.** `session.spec.ts:279` et `:583` en dépendent. Ne pas basculer tout le fichier en minuteries factices.
  - [ ] **Instants sentinelles, jamais `new Date()`** : deux `toISOString()` réels tombent dans la même milliseconde et ne distinguent rien.
  - [ ] Pinia **réel** — `@pinia/testing` n'est pas installé **et ne doit pas l'être** (`SyncFailureNotice.spec.ts:94`). i18n réel via `createEscrowI18n('en')`.
  - [ ] Session posée par la vraie primitive `applySession({token, user})` : une fixture ne posant que `user` laisse `isAuthenticated` à `false` et teste silencieusement le chemin déconnecté.
  - [ ] **Chaque assertion négative appariée à une positive** dans le même test, comptes exacts (un `data-testid` inexistant fait renvoyer 0 à `findAll` et satisfait « au plus un » par le vide — **quatrième occurrence de ce motif sur cet epic**).

- [ ] **T10 — Vérification par mutation** (AC: 7 — obligatoire, `project-context.md:99-102`)
  - [ ] Une mutation **par garde livrée** : substrat `sessionStorage`, contrôle au démarrage, minuterie, propagation inter-onglets, borne d'attente, branche « profil illisible », époque de session, câblage `main.ts`, bouton de déconnexion.
  - [ ] Forme : **mutation la plus proche du défaut d'origine** (pas une mutation commode), suite relancée, **et** vérification que ce sont bien les tests visés qui rougissent.
  - [ ] ⚠️ Piège 1.9 (`spec-1-9:170`) : une assertion posée sur un chemin `break` **n'est pas** détectée par la mutation — `break` sort normalement et atteint tout ce qui suit ; seul un `throw` saute la ligne. *« Sans la passe de mutation, un test vert et inutile aurait été livré comme preuve. »*
  - [ ] Consigner chaque résultat dans la section Completion Notes.

- [ ] **T11 — Barrières** (toutes bloquantes en CI)
  - [ ] `npm run test` — base actuelle **427 tests / 29 fichiers**, tous verts en 12,1 s.
  - [ ] `npm run lint` (`--max-warnings 0`, `reportUnusedDisableDirectives: 'error'` — une directive `eslint-disable` devenue inutile **fait échouer** le lint).
  - [ ] `npm run build` = `npm run type-check && vite build` (`vue-tsc --build`) — **gate ajouté par la migration TS, non consigné dans les stories antérieures**. Doit rester à 0 diagnostic.
  - [ ] `python3 scripts/check-encoding.py` (racine backend) — **écrire le français avec ses accents**, y compris dans les commentaires de test.
  - [ ] `npm run verify:no-demo`, `npm run verify:pwa`.

---

## Dev Notes

### ⚠️ Tableau de conversion des chemins — tous les artefacts amont citent des `.js` morts

La migration TypeScript a eu lieu **entre la Story 2-3 et la Story 2-4** et **aucun artefact de planification ne l'a intégrée**. Les faits tiennent tous ; les chemins et numéros de ligne, non.

| Cité par le ledger / spec 1.9 / epics.md | Réel au 2026-08-10 |
|---|---|
| `main.js:18` | `frontend/src/main.ts:26` (`app.mount` en `:33`) |
| `stores/session.js` | `frontend/src/stores/session.ts` — `READ_CACHE_NAME:44`, `LAST_USER_STORAGE_KEY:57`, `purgeReadCache:67`, `EndSessionReason:90`, `endSession:100`, `beginSession:229`, `installSessionExpiryListener:324` |
| `stores/auth.js:17-24`, `:41` | `frontend/src/stores/auth.ts:32`, `:51`, `:64-68` |
| `api/auth.js:36-43` | `frontend/src/api/auth.ts:77-80` |
| `api/client.js:60`, `:3` | `frontend/src/api/client.ts:61`, `TOKEN_STORAGE_KEY` en `:5` |
| `views/DashboardView.vue:54` | `frontend/src/views/DashboardView.vue:66` |
| `stores/evidence.js` (`loadSeq`) | `frontend/src/stores/evidence.ts:15,25,30,38,42,89` |
| `stores/offlineQueue.js` (`flushing`) | `frontend/src/stores/offlineQueue.ts:305,316,429` |
| `stores/escrow.js:82,127,185` | `frontend/src/stores/escrow.ts:71-92,135,149` |
| `vite.config.js:50-58` | `frontend/vite.config.ts:66-71` |
| `application.yml:44` | `backend/src/main/resources/application.yml:77` |

Piège annexe : `vitest.config.ts:16` déclare `setupFiles: ['./vitest.setup.js']` alors que le fichier est **`vitest.setup.ts`**. Ça fonctionne par résolution Vite. Ne pas s'y fier pour localiser du code.

### État actuel — les cinq clés de stockage

| Clé | Où | Sort en 2.7 |
|---|---|---|
| `escrow_token` | `client.ts:5` (déf.), lu `:20`, `:62` ; `auth.ts:51`, écrit `:64-65` | **→ substrat commutable** |
| `escrow_user` | `auth.ts:18` (déf.), lu `:32`, écrit `:67-68` | **→ substrat commutable** |
| `escrow_last_user` | `session.ts:57` (déf. **non exportée**), `:184`, `:266`, `:300-301` | reste en `localStorage` (marqueur d'appareil) ; **exporter la constante** |
| `escrow_locale` | `i18n/index.ts:39` | **NE PAS PURGER** (décision 2.1, verrouillée par test) |
| `escrow_offline_queue` | `offlineQueue.idb.ts:19` | migration one-shot, hors périmètre |

**Aucun `sessionStorage` n'existe nulle part aujourd'hui.** Aucun `localStorage.clear()` en production — la purge est toujours **par clés nommées** (invariant `session.ts:194`). Ne pas régresser.

### L'ordre de `endSession` — et pourquoi son test est fragile

Ordre actuel (`session.ts:100-213`), **prouvé par `session.spec.ts:243-293`** :
1. lire `userId` et `revokedToken` **avant** toute purge (`:121-122`)
2. `auth.clearSession()` (`:134`) → 3. `escrow.$reset()` (`:143`) → 4. `evidence.$reset()` (`:148`) → 5. `queue.clearMemory()` (`:153`)
6. *si `expired` → **return** ici (`:158`)*
7. `await idb.clearForUser(userId)` (`:170`) → 8. `await purgeReadCache()` (`:175`) → 9. `removeItem('escrow_last_user')` (`:184`)
10. **et seulement alors** `await auth.revokeOnServer(revokedToken)` (`:207`)

Chaque effet dans son propre `try/catch`. **`endSession` ne rejette jamais.**

Le test mocke `logoutUser` sur une promesse **qui ne se résout jamais** — seul moyen de distinguer les deux ordres — et observe sur `vi.waitFor`, pas sur un nombre fixe de macrotâches. Il assère **tous** les effets locaux pendant que la révocation est en vol.

> **C'est la troisième passe consécutive de la Story 1.9 où une moitié de la purge est restée derrière l'`await` sans que la suite le voie, chaque fois parce qu'elle ne contrôlait que la moitié déjà déplacée.** Puis la suite a viré au rouge parce que le correctif ajoutait deux `await` réels et que le test n'en drainait qu'un. *« Le correctif de code était bon, sa preuve ne l'était plus. »*

### NEVER — contraintes dures héritées

- **Ne pas importer le routeur ni un store dans `api/client.ts`** — cycle `router → stores/auth → api/auth → api/client` (documenté `client.ts:99-102`). Le pattern est : `client.ts` n'émet qu'un `window.dispatchEvent`, `session.ts` possède le teardown.
- **Ne pas remplacer `logoutUser` par axios** (spec 1.9:47, décision de la revue 1.6).
- **Ne pas toucher le `403` nu de Spring Security.** Le discriminant « session morte » est **exactement** `code == null && (status === 401 || status === 403)`, défini **une seule fois** dans `utils/replayFailure.ts`. Un 403 **codé** est un verdict métier, un 401 **codé** `AUTH_FAILED` un échec de connexion : **ni l'un ni l'autre ne déconnecte**. La Story 2.5 produira des « 403 uniformisés » applicatifs — la distinction doit être figée avant.
- **Ne pas modifier `vitest.setup.ts` entre les lignes 17 et 31.** L'ordre échange de `globalThis.Blob`/`File` depuis `node:buffer` **puis** import **dynamique** d'`undici` est *load-bearing* : un import statique serait hoisté au-dessus de l'échange et capturerait le Blob de jsdom. Insérer après la ligne 32 si nécessaire.
- **Ne pas ajouter d'entrée à la liste `PENDING_MIGRATION`** (`i18n/__tests__/noHardcodedStrings.spec.ts`). ⚠️ **`stores/auth` et `stores/escrow` y figurent et sont tous deux réécrits par cette story.** Retirer une entrée fait ROUGIR la suite tant que le module n'est pas migré.
- **Ne pas restaurer un fichier par `git checkout --`** s'il porte du travail non commité (un correctif de `StateBadge` a été effacé ainsi en 2-1).

### Conventions UI héritées (2-1 / 2-2 / 2-3)

- **Aucune chaîne littérale** — la garde scanne gabarits, script **et** modules ; simples, doubles **et backticks**.
- **Aucune clé brute rendue** — passer par `@/i18n/labels` ; **`$t(null)` LÈVE**.
- **Aucune ombre hors `shadow-floating`** — la garde vise la classe **et** la propriété CSS (`boxShadow` en ligne compris).
- **Classes Tailwind en toutes lettres, jamais composées** — l'extraction est statique ; une classe construite à l'exécution n'existe pas et **le rendu est muet, sans erreur**.
- **`type="button"`** sur tout `<button>` qui n'est pas un envoi de formulaire.
- Marges de contenu : `p-4 lg:p-6` (décision 2-3).
- Tokens : `surface-card`, `primary`/`primary-hover`/`primary-foreground`, `danger`, `--radius`, `--shadow-floating`.

### Modes d'échec récurrents de ce projet — ce que cette story va rencontrer

1. **Un test vert n'est pas une preuve ; seule la mutation l'est.** Cinq gardes du projet se sont révélées creuses : sentinelle vivant dans un commentaire supprimé par la minification, garde d'élévation aveugle à la propriété CSS, drapeau `hydrated` asserté sur un store neuf où il valait déjà `false`, assertion sur un `data-testid` inexistant, assertion posée sur un chemin `break`. **Chaque fois, seule la mutation l'a montré.**
2. **Un composant testé isolément n'est pas un composant branché.** `ClientShell` avait 7 tests verts et **n'était monté par aucune route**. Symétriquement : `endSession` et `installSessionExpiryListener` sont testés comme **fonctions**, jamais comme **câblage**.
3. **Un drapeau sentinelle a deux états de départ.** Un test qui part de l'état neutre ne distingue rien. Applicable au drapeau d'inactivité et à l'époque.
4. **Un `try/catch` ne garde que contre ce qui lève.** `Intl.format(NaN)` rend « $NaN », `new Date('x').toLocaleString()` rend « Invalid Date » — aucun ne lève.
5. **Un objet littéral hérite d'`Object.prototype`.** `spaceForRole('constructor')` rendait la fonction `Object` et `?? null` ne rattrapait rien → **enfermement hors de `/auth`**. Corrigé par `Object.hasOwn`. Même vigilance sur toute table de correspondance ajoutée ici.
6. **Un octet brut dans un littéral rend un fichier invisible à `grep` ET à `git`.** `AuthView.vue` est devenu binaire en 1.9 (classe de caractères de contrôle écrite avec les octets qu'elle matche) et **échappait au grep de vérification de sa propre story**. Un en-tête magique s'écrit en tableau d'octets explicite.
7. **Une justification périmée reconduit un défaut pour toujours** — `DashboardView.vue:53-63` en porte une aujourd'hui.
8. **Une AC absolue est intenable et sera relevée en revue.** Écrire l'écart plutôt que le cocher.

### Prévoir la revue comme la moitié du travail

Bilan des stories antérieures : **1.9 → 3 passes, 29 correctifs (5 hauts)** ; 2-1 → 3 passes ; 2-2 → 2 campagnes ; 2-3 → 3 couches. Constat reconduit sur trois stories : **haiku produit du volume, pas de la vérification** (2-3 : 2 pistes tenables sur 15 ; les deux premières couches citaient des lignes **du patch**, pas des fichiers). Les défauts de cette story demanderont d'**exécuter** — prévoir sonnet et des mutations en worktree jetable.

### Project Structure Notes

Fichiers **modifiés** : `stores/session.ts`, `stores/auth.ts`, `stores/escrow.ts`, `stores/evidence.ts`, `api/client.ts`, `api/auth.ts`, `main.ts`, `views/AuthView.vue`, `views/DashboardView.vue`, `layouts/ClientShell.vue`, `layouts/DesktopShell.vue`, `i18n/en.json`, `i18n/fr.json`.
Fichiers **nouveaux** : module de substrat de stockage, module de propagation inter-onglets, module d'inactivité (emplacement à l'appréciation du dev, sous `src/utils/` ou `src/stores/` selon qu'il porte de l'état).
Tests **nouveaux** : test de composant du bouton de déconnexion, test de câblage dans `appShell.spec.ts`, suites d'inactivité / inter-onglets / époque.

**Recouvrement avec la Story 2-4 (en pause à T6) :** `api/client.ts`, `stores/auth.ts`, `stores/session.ts`, `views/AuthView.vue`, `views/VerifyEmailView.vue`. C'est le motif du réordonnancement du 2026-08-10 — T6/T8 de la 2-4 s'écriront **après**, une seule fois, contre la politique livrée ici.

### References

- [Source: `epics.md#Story 2.7`] lignes 668-711 — les 7 AC
- [Source: `deferred-work.md:353-404`] — les 9 entrées de l'ex-bundle SESSION-PARTAGÉE (E1, E5, E6, E7, E8 ici ; E2, E9 → 11-3 ; E4 → Epic 9 ; E3 ici)
- [Source: `spec-1-9-hygiene-session-appareil-partage.md`] — antécédent direct : 29 correctifs, `:26-47` contraintes NEVER, `:150` mode inconnu silencieux, `:170` mutation sur chemin `break`, `:189` 403 nu délibéré
- [Source: `2-3-layout-trois-espaces-navigation.md:189-216`] — les trois défauts trouvés par ses propres tests ; `:291-352` revue
- [Source: `2-1-fondation-ui-tokens-design-i18n.md:210-214`] — section écrite **pour cette story** : persistance de la langue à travers la déconnexion
- [Source: `project-context.md:99-102`] — règle de vérification par mutation ; `:77` — 403 nu vs 403 codé
- [Source: `ARCHITECTURE-SPINE.md`] — AD-9, AD-10, AD-18, AD-20, AD-21, AD-23, AD-27, AD-30 ; conventions « Sécurité & session » et « Frontend »
- [Source: `epics.md#UX Design Requirements`] — UX-DR13, UX-DR20, UX-DR21, UX-DR26, UX-DR28, UX-DR32, UX-DR35, UX-DR37, UX-DR38, UX-DR39, UX-DR40, UX-DR43, UX-DR45, UX-DR46
- [Source: `epics-production.md:72`] — texte canonique de NFR-P8 (**absent du PRD**, qui délègue en `prd.md:178`)
- [Source: `sprint-status.yaml:198-217`] — décision de séquencement du 2026-08-10

---

## Décisions d'Oscard — 2026-08-10 (les trois questions sont closes)

**Q1 → DÉLAI D'INACTIVITÉ = 15 MINUTES.** Valeur normative de cette story. Elle n'existait dans aucun artefact amont ; elle vit désormais ici et doit être **une constante nommée et exportée**, pas un littéral dispersé — un seuil recopié à deux endroits diverge au premier ajustement.

**Q2 → CONFIRMÉ : l'expiration d'inactivité NE PURGE PAS la file offline.** La décision D-A tient telle qu'écrite : sémantique `expired`, mode nommé `'idle'`. L'hygiène d'appareil partagé vient de la mort du jeton et du scoping par utilisateur, pas de la destruction du travail non envoyé (AD-9, UX-DR32).

**Q3 → TTL serveur de 24 h HORS PÉRIMÈTRE.** `application.yml:77` conserve `ttl-seconds: 86400`. Aucune AC ne le touche. Conséquence à ne pas maquiller : **un jeton dérobé reste valide 24 h côté serveur** — la politique client réduit la surface d'exposition sur l'appareil, elle ne raccourcit pas la vie du jeton. À porter au ledger à la clôture de la story, comme dette nommée.

---

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

### File List
