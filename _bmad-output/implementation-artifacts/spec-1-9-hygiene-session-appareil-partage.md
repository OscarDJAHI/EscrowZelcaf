---
title: 'Story 1.9 — Hygiène de session sur appareil partagé'
type: 'feature'
created: '2026-07-26'
status: 'done'
baseline_revision: 'd9bed15874b871efe7665966a90811afa146ef9e'
final_revision: '1a2c7e0ef15ca3f5302f68653459e27a06170697'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-1-context.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Sur un appareil partagé, la déconnexion ne laisse presque rien derrière elle — mais ce « presque » est la faille. `stores/auth.js:92-99` n'efface que `token` et `user` : les stores `escrow` (transactions, `currentDetail`, horodatages de cache) et `evidence` (`items`) restent en mémoire, la file IndexedDB `escrow-offline` reste entière et **device-globale** (`offlineQueue.idb.js:3-5`, aucun discriminant d'utilisateur), et `flush()` (`offlineQueue.js:167`) rejoue les entrées de **n'importe qui** avec le JWT courant — gelant définitivement les preuves du premier utilisateur en `NOT_A_PARTY`. Le cache de lecture Workbox `escrow-api-cache` (`vite.config.js:51-58`) conserve en plus ses réponses `/api/` 24 h. Symétriquement, l'intercepteur (`api/client.js:31`) ne réagit qu'au **401**, alors que ce backend renvoie un **403 nu** pour toute session expirée ou révoquée (`SecurityConfig` sans `AuthenticationEntryPoint` → `Http403ForbiddenEntryPoint`, figé par `PartnerSecurityMatcherTest:70-73`) : depuis la Story 1.6, tout `revokeSessions` laisse le second appareil dans une UI authentifiée zombie, sans redirection ni explication. C'est la NFR-P8 et l'UX-DR32, et les quatre reports du ledger (`deferred-work.md:160,171,175,229`).

**Approach:** Poser **une** primitive de fin de session avec deux modes (déconnexion explicite / expiration subie) qui orchestre tous les stores, et rendre la file offline **propriétaire-consciente** à l'hydratation, au rejeu et à la purge — sans toucher au filtrage d'affichage déjà livré et prouvé par la Story 4.4. L'intercepteur HTTP ne connaît ni le routeur ni les stores : il émet un événement `window`, comme `escrow:sync` le fait déjà.

## Boundaries & Constraints

**Always:**
- Le discriminant « session morte » est **exactement** `code == null && (status === 401 || status === 403)` — même règle que `replayFailure.js:172`, **définie une seule fois** et réutilisée par les deux appelants. Un 403 **codé** (`NOT_A_PARTY`, `FORBIDDEN`, `UNAUTHORIZED_TRANSITION`) est un verdict métier : il ne déconnecte jamais. Un 401 **codé** `AUTH_FAILED` est un échec de connexion : il ne déconnecte jamais non plus (sinon la vue d'authentification se réinitialise à chaque mot de passe erroné).
- **Deux modes, deux périmètres** (epic-1-context.md:52) : `logout` explicite purge tout, y compris la file IndexedDB de l'utilisateur qui part et le cache de lecture ; `expired` ne purge que la mémoire et les identifiants — file et cache **survivent** à la ré-authentification.
- « Survivent à la ré-authentification » vaut pour **le même** utilisateur. Une connexion dont l'identifiant diffère du dernier utilisateur vu sur l'appareil purge le cache de lecture avant toute chose : sinon, après une expiration, un B hors ligne se ferait servir les réponses `/api/` de A par Workbox.
- La purge IndexedDB d'une déconnexion ne touche **que** les entrées de l'utilisateur qui part (`meta.userId`) et les entrées **orphelines** (sans `meta.userId`) ; les entrées d'un **autre** utilisateur du même appareil sont intactes.
- `flush()` et `hydrate()` ne voient que les entrées dont `meta.userId` correspond à l'utilisateur authentifié — comparaison via `ownsEntry` (`utils/frozenEntry.js:68-73`), jamais une réimplémentation de la coercion `String(a) === String(b)`.
- La cible est conservée en **query param** `redirect` sur la route `auth`, et n'est acceptée au retour que si elle est un chemin **relatif** commençant par `/` et **pas** par `//` (garde anti-open-redirect).
- Toute chaîne visible ajoutée reste en anglais littéral, à l'image de l'existant : l'i18n par clés est la Story 2.1, ne pas la préempter.
- Aucune régression sur les 156 tests frontend existants — en particulier les 31 de `SyncFailureNotice.spec.js` et les 25 de `RecoveryView.spec.js`, qui posent leurs fixtures directement dans `queue` en mémoire.

**Block If:**
- L'implémentation exige de faire disparaître les entrées gelées d'un utilisateur **avant** qu'il ait pu les récupérer, ou de rendre `/recovery/:entryId` (Story 4.5) inatteignable pour une entrée qui lui appartient encore.
- Fermer le trou impose de modifier le contrat 403 **côté backend** (nouvel `ErrorCode`, `AuthenticationEntryPoint`) : c'est la surface de la Story 1.10 (anti-énumération) et cela casserait `PartnerSecurityMatcherTest` et `replayFailure.js`.
- La purge du cache Workbox ne peut être faite sans enregistrer un gestionnaire dans le service worker (`vite-plugin-pwa` en mode `injectManifest`) : cela changerait la stratégie PWA livrée.

**Never:**
- Ne pas modifier les **getters** `frozenEntries` / `pendingCount` ni le double filtrage d'affichage `auth.isAuthenticated` + `ownsEntry` de `SyncFailureNotice.vue:45-48` et `RecoveryView.vue:120-127` : ils sont livrés, prouvés, et le scoping ajouté ici est en amont (hydratation / rejeu / purge).
- Ne pas faire réapparaître les entrées sans `meta.userId` : la Story 4.5 a explicitement refusé de leur inventer un propriétaire.
- Ne pas importer le routeur ni un store dans `api/client.js` : `router → stores/auth → api/auth → api/client` boucle.
- Ne rien ajouter au backend (aucune migration `V10__`, aucun `ErrorCode`, aucun endpoint).
- Ne pas toucher à `vitest.setup.js` (ordre swap `Blob`/`File` puis import **dynamique** d'`undici` : load-bearing).
- Ne pas remplacer `logoutUser` par un appel axios : le `fetch` + `keepalive` de `api/auth.js:36-43` est une décision de la revue 1.6.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Déconnexion explicite | A connecté, 2 entrées de A en file (1 gelée), 1 entrée de B, 1 orpheline | Révocation serveur attendue ; `token`/`user` effacés ; `escrow`/`evidence`/`offlineQueue` remis à zéro ; les 2 entrées de A **et** l'orpheline supprimées d'IndexedDB ; l'entrée de B **intacte** ; `escrow-api-cache` supprimé ; navigation vers `auth` **sans** `redirect` | Échec de révocation, d'IndexedDB ou de `caches` : la purge locale aboutit quand même et la navigation a lieu |
| Bascule d'utilisateur | Après la déconnexion de A, B se connecte dans le même onglet | B n'hydrate que ses propres entrées ; `flush()` ne rejoue que les siennes ; aucune transaction de A affichée à aucun instant | — |
| 403 nu sur endpoint protégé | Session A, jeton révoqué par un autre appareil (Story 1.6) | `escrow:session-expired` émis une fois ; identifiants et mémoire purgés ; IndexedDB **et** `escrow-api-cache` conservés ; `auth?redirect=/escrow/42` | — |
| Ré-authentification du même utilisateur | A expiré puis A se reconnecte | Cache de lecture **conservé** ; entrées de A ré-hydratées puis rejouées si en ligne | — |
| Bascule après expiration | A expiré (cache conservé) puis **B** se connecte | `escrow-api-cache` purgé au moment de la connexion de B, avant tout rendu ; B n'hydrate que ses entrées | Purge de cache en échec : la connexion aboutit quand même |
| 403 codé | `NOT_A_PARTY` sur `GET /api/v1/escrow/42` | Aucun événement, aucune purge, aucune redirection ; l'erreur remonte à l'appelant | Rendue par la vue |
| 401 codé `AUTH_FAILED` | Mot de passe erroné sur `/auth` | Aucun événement, aucune purge ; `auth.error` renseigné, le formulaire reste utilisable | Message d'erreur du store |
| Reprise de cible | `auth?redirect=/escrow/42`, connexion réussie | `router.replace('/escrow/42')` | — |
| Cible hostile | `auth?redirect=//evil.example/x` ou `https://evil/x` | Cible rejetée, `router.replace('/')` | — |
| Rafale de 403 | 3 appels concurrents échouent en 403 nu | Un seul teardown, une seule navigation | Les appels suivants sont ignorés (session déjà close) |
| Démarrage sans session | Rechargement, aucun jeton | Écouteurs `online`/`offline` posés ; **aucune** hydratation ; `flush()` non déclenché | — |
| Entrée orpheline en file | Entrée sans `meta.userId`, A connecté | Jamais hydratée, donc jamais rejouée ni affichée ; supprimée à la prochaine déconnexion | — |

</intent-contract>

## Code Map

- `frontend/src/utils/replayFailure.js:154-179` -- `classifyReplayFailure` ; c'est là que vit déjà la règle du 401/403 nu (`:172`) et son raisonnement backend en commentaire (`:162-171`).
- `frontend/src/utils/frozenEntry.js:68-73` -- `ownsEntry(entry, user)`, seule coercion propriétaire autorisée ; `:43-59` documente pourquoi une entrée sans propriétaire n'est montrée à personne.
- `frontend/src/api/client.js:3` (`TOKEN_STORAGE_KEY`), `:17-24` (intercepteur requête), `:26-40` (intercepteur réponse, `=== 401` à remplacer).
- `frontend/src/stores/auth.js:5` (`USER_STORAGE_KEY`, non exporté), `:17-22` (state hydraté à la construction), `:30-42` (`persist`/`applySession`), `:74-99` (`logout`, dont le JSDoc renvoie explicitement à cette story).
- `frontend/src/stores/offlineQueue.idb.js:3-5` (nom/version/store), `:19-24` (`upgrade`, aucun index), `:47-58` (`getAll` + tri FIFO), `:73-82` (`remove`, `clear`), `:132` (`resetDBForTests`).
- `frontend/src/stores/offlineQueue.js:51-57` (state, `initialized`/`hydrated` séparés), `:80-111` (`init`), `:114-123` (`hydrate`, merge volontaire), `:158-254` (`flush`, filtre `!frozen` seul `:167`), `:251-253` (dispatch `escrow:sync`).
- `frontend/src/stores/escrow.js:13-24` (state + horodatages), `:77-82` (commentaire fondateur du stamp `meta.userId`), `:127`, `:185`.
- `frontend/src/stores/evidence.js:6-13` -- `items`, `loadedId`, `loadSeq` (cloisonné par transaction, jamais par utilisateur).
- `frontend/src/router/index.js:42-52` -- garde, retourne `{ name: 'auth' }` sans `query`.
- `frontend/src/views/AuthView.vue:26-34` -- `handleSubmit`, `router.push('/')` en dur, aucun `useRoute`.
- `frontend/src/views/DashboardView.vue:42-50` -- `await auth.logout()` puis `window.location.href = '/auth'`.
- `frontend/src/main.js:11-19` -- ordre pinia → router → `useOfflineQueueStore().init()` non attendu → `mount`.
- `frontend/vite.config.js:50-58` -- `cacheName: 'escrow-api-cache'` (source de vérité du nom).
- Gabarits de test : `frontend/src/stores/__tests__/offlineQueue.spec.js:42-50` (`new IDBFactory()` + `resetDBForTests()`), `:105-110` (simulation de rechargement), `frontend/src/components/__tests__/SyncFailureNotice.spec.js:34-56` (helper `frozen(...)`), `:86-89` (fixture de session complète : **les deux** moitiés).

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/utils/replayFailure.js` -- extraire la règle du 401/403 nu en `export function isBareAuthFailure({ code, status })` et faire de `classifyReplayFailure:172` son premier appelant (comportement inchangé) ; déplacer le commentaire `:162-171` sur la nouvelle fonction -- une seule définition pour le rejeu et pour l'intercepteur : deux copies dériveraient, et c'est ce prédicat qui empêche un `AUTH_FAILED` de déconnecter.
- [x] `frontend/src/api/client.js` -- remplacer le bloc `status === 401` par : si `isBareAuthFailure(extractFailureReason(error))` **et** qu'un jeton est présent en `localStorage`, émettre `window.dispatchEvent(new CustomEvent('escrow:session-expired'))` **une seule fois** (verrou module) ; exporter `resetSessionExpiryLatch()` pour le relever ; ne plus toucher à `localStorage` ni à `window.location` ici -- l'intercepteur ne peut pas importer le routeur (`router → stores/auth → api/auth → api/client` boucle) ; la garde « jeton présent » évite un teardown sur un endpoint anonyme, et le verrou exporté évite qu'un minuteur arbitraire décide quand la fenêtre se rouvre.
- [x] `frontend/src/stores/auth.js` -- exporter `USER_STORAGE_KEY` ; scinder `logout()` en `clearSession()` (purge synchrone `token`/`user`/`loading`/`error` + `persist()`) et `revokeOnServer(token)` ; `logout()` reste l'enchaînement des deux pour ne pas casser `auth.spec.js` -- l'expiration doit pouvoir purger sans appeler un endpoint qui refusera le jeton mort.
- [x] `frontend/src/stores/offlineQueue.idb.js` -- remplacer `getAll()` par `getAllForUser(userId)` (même tri FIFO, filtre `ownsEntry`-équivalent sur `meta.userId`) et ajouter `clearForUser(userId)` supprimant les entrées de `userId` **et** les entrées sans `meta.userId` ; conserver `clear()` (helper de test) et `remove(id)` -- le scoping devient une propriété de l'API de persistance, pas une consigne que chaque appelant doit se rappeler ; pas d'index ni de `DB_VERSION = 2` : le volume est de quelques entrées, une migration de schéma coûterait plus qu'elle ne rapporte (à documenter dans le fichier).
- [x] `frontend/src/stores/offlineQueue.js` -- `hydrate()` et `flush()` lisent l'utilisateur courant (`useAuthStore().user?.id`) : sans utilisateur, `hydrate()` ne charge rien et `flush()` ne rejoue rien ; `flush()` filtre `!item.frozen` **et** `ownsEntry(item, user)` ; `init()` ne migre/hydrate que si une session existe ; ajouter `clearMemory()` (vide `queue`, remet `hydrated` à `false`, laisse `initialized` et les écouteurs en place) et `adoptSession()` (hydrate puis `flush()` si en ligne) -- c'est le rejeu inter-utilisateur qui gèle définitivement les preuves du premier propriétaire (`deferred-work.md:160`), pas seulement l'affichage.
- [x] `frontend/src/stores/session.js` -- **à créer** : orchestrateur (pas un `defineStore`, l'expliquer en tête de fichier) exportant `endSession({ reason })` et `beginSession(userId)`. `reason: 'logout'` → lire `user.id` **avant** toute purge, `await auth.revokeOnServer(token)`, `auth.clearSession()`, `$reset()` sur `escrow` et `evidence`, `offlineQueue.clearMemory()`, `idb.clearForUser(id)`, `purgeReadCache()` ; `reason: 'expired'` → `auth.clearSession()`, `$reset()` sur `escrow` et `evidence`, `offlineQueue.clearMemory()`, **rien** sur IndexedDB ni sur le cache. `beginSession(userId)` → `resetSessionExpiryLatch()`, purge du cache de lecture **si** le marqueur `escrow_last_user` est renseigné et diffère de `userId`, écriture du marqueur, puis `offlineQueue.adoptSession()`. Le marqueur est effacé par `endSession({ reason: 'logout' })` (le cache y est déjà purgé), conservé par `expired`. Chaque effet est encapsulé dans son propre `try/catch` -- placé hors de `auth.js` parce que `escrow.js` importe `auth.js` : un `auth → escrow` fermerait le cycle ; les `try/catch` individuels garantissent qu'un `caches` indisponible n'empêche pas la purge de la file ; le marqueur ne porte qu'un identifiant opaque, là où `escrow_user` portait tout le profil.
- [x] `frontend/src/stores/session.js` (même fichier) -- `purgeReadCache()` : si `typeof caches !== 'undefined'`, `await caches.delete('escrow-api-cache')` ; nom en constante exportée, avec un commentaire pointant `vite.config.js:53` -- le cache `NetworkFirst` retient 24 h de réponses `/api/` du partant ; `escrow-app-shell` et `escrow-images` ne portent aucune donnée utilisateur et ne sont pas touchés.
- [x] `frontend/src/stores/session.js` (même fichier) -- exporter `installSessionExpiryListener(router)` : écoute `escrow:session-expired`, exécute `endSession({ reason: 'expired' })` puis `router.replace({ name: 'auth', query: { redirect: router.currentRoute.value.fullPath } })` (sans `redirect` si la route courante est déjà `auth`) -- la fonction reçoit le routeur en argument plutôt que de l'importer : c'est ce qui la rend testable sans monter l'application, et c'est le seul endroit qui connaît à la fois le routeur et les stores.
- [x] `frontend/src/stores/auth.js` (`applySession`) -- appeler `beginSession(user?.id)` après une connexion réussie -- c'est le seul point où l'identité change ; sans lui, l'utilisateur qui vient de se connecter n'hydrate jamais ses propres entrées, `main.js` ayant déjà tourné.
- [x] `frontend/src/main.js` -- après `app.use(router)` et avant `mount`, appeler `installSessionExpiryListener(router)` ; laisser `useOfflineQueueStore().init()` en place -- `main.js` reste un fichier de câblage sans logique, donc sans test propre.
- [x] `frontend/src/router/index.js` -- la garde renvoie `{ name: 'auth', query: { redirect: to.fullPath } }` (sans `redirect` si `to.fullPath === '/'`) -- UX-DR32 : un lien profond ouvert sans session doit revenir à sa cible après connexion.
- [x] `frontend/src/views/AuthView.vue` -- `useRoute()`, et après succès `router.replace(safeRedirect(route.query.redirect))` où `safeRedirect` n'accepte qu'une chaîne commençant par `/` et **pas** par `//`, sinon `'/'` -- un `redirect` vient de l'URL, donc de l'attaquant : sans la garde, `?redirect=//evil.example` fabrique une redirection ouverte depuis l'écran de connexion.
- [x] `frontend/src/views/DashboardView.vue` -- `logout()` devient `await endSession({ reason: 'logout' })` puis `router.replace({ name: 'auth' })` -- le rechargement complet n'est plus le mécanisme de purge (il ne l'a jamais été pour IndexedDB) ; une navigation de routeur rend le parcours testable, et la révocation reste attendue comme l'exige la revue 1.6.
- [x] `frontend/src/stores/__tests__/session.spec.js` -- **à créer** : couvrir les lignes « Déconnexion explicite », « Bascule d'utilisateur », « 403 nu » et « Démarrage sans session » de la matrice, avec de vraies entrées IndexedDB (gabarit `offlineQueue.spec.js:42-50`) et une fixture de session complète (`applySession`, **les deux** moitiés) ; asserter explicitement que l'entrée de B **survit** à la déconnexion de A et qu'en mode `expired` IndexedDB est **intacte** ; simuler `caches` par un double et vérifier `delete('escrow-api-cache')` ; couvrir aussi les deux lignes de ré-authentification — même utilisateur : cache **conservé** ; utilisateur différent : cache purgé au `beginSession` — et `installSessionExpiryListener` avec un faux routeur (`replace` espionné, `currentRoute` fabriqué) -- la valeur de la story est dans ce qui **n'est pas** effacé autant que dans ce qui l'est.
- [x] `frontend/src/stores/__tests__/offlineQueue.spec.js` -- ajouter : hydratation qui ignore les entrées d'autrui et les orphelines ; `flush()` qui ne rejoue que les entrées du propriétaire connecté (vérifier `apiClient.request` non appelé pour l'entrée d'autrui) ; `init()` sans session qui pose les écouteurs sans hydrater -- le rejeu inter-utilisateur n'est couvert par aucun test aujourd'hui.
- [x] `frontend/src/api/__tests__/client.spec.js` -- **à créer** : 403 nu avec jeton → un seul `escrow:session-expired` ; 403 codé `NOT_A_PARTY` → aucun ; 401 codé `AUTH_FAILED` → aucun ; 401 nu avec jeton → un ; 403 nu **sans** jeton → aucun ; rafale de 3 → un seul -- c'est le discriminant qui décide si un verdict métier déconnecte l'utilisateur.
- [x] `frontend/src/utils/__tests__/replayFailure.spec.js` -- ajouter les assertions directes sur `isBareAuthFailure` (401 nu, 403 nu, 403 codé, 404 nu) sans modifier les 23 tests existants -- l'extraction ne doit rien changer à la classification du rejeu.
- [x] `frontend/src/views/__tests__/authRedirect.spec.js` -- **à créer** : la garde de routeur pose `redirect` sur une route profonde et l'omet sur `/` ; `AuthView` reprend une cible relative, rejette `//evil.example/x` et `https://evil/x` -- la garde anti-open-redirect est la seule partie de l'UX-DR32 qui a une conséquence de sécurité.
- [x] `_bmad-output/implementation-artifacts/deferred-work.md` -- marquer `RÉSOLU 2026-07-26 (Story 1.9)` sur les quatre entrées `:160`, `:171`, `:175`, `:229` en une ligne chacune, sans supprimer le texte d'origine -- le ledger est append-only et ces quatre reports sont précisément le périmètre livré ici.

**Acceptance Criteria:**
- Given un appareil partagé où A puis B se connectent dans le même onglet, when A quitte la session — que ce soit par déconnexion explicite ou par expiration subie — then aucune donnée de A — transaction, détail, preuve, entrée de file, réponse `/api/` en cache — n'est lisible ni rejouable par B, à aucun instant du parcours et y compris hors ligne (NFR-P8).
- Given une session dont le jeton a été révoqué ou a expiré, when un appel protégé revient en 403 nu, then l'utilisateur est ramené à l'écran de connexion avec sa cible conservée, et se reconnecter l'y ramène (UX-DR32) — tandis qu'un 403 **codé** laisse l'application exactement dans l'état où il l'a trouvée.
- Given des preuves en attente de rejeu appartenant à un utilisateur, when un **autre** utilisateur se connecte sur le même appareil, then ces entrées ne sont ni rejouées sous son jeton, ni supprimées, ni affichées — elles reviennent à leur propriétaire à sa prochaine connexion.
- Given la suite frontend existante, when elle est rejouée, then les 156 tests antérieurs passent tous et aucune de leurs assertions n'est **affaiblie ni supprimée** ; les seules modifications admises sont celles que le stamp `meta.userId` rend factuellement nécessaires — deux égalités de `meta` désormais porteuses de `userId`, deux cibles d'espion (`idb.getAll` → `getAllForUser`) et les fixtures qui doivent ouvrir une session pour continuer à éprouver ce qu'elles éprouvaient — et le contrat d'erreur backend est inchangé (aucun fichier sous `backend/` modifié).
  _(Formulation corrigée à la passe de revue de suivi du 2026-07-26 : l'AC exigeait initialement « sans modification de leurs assertions », ce qu'une story qui change précisément le contenu de `meta` ne peut pas tenir. Elle interdit désormais l'affaiblissement, qui est ce qu'elle voulait dire, et le décompte exact est consigné dans `## Auto Run Result`.)_

## Spec Change Log

## Review Triage Log

### 2026-07-26 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 9: (high 2, medium 5, low 2)
- defer: 4
- reject: 2
- addressed_findings:
  - `[high]` `[patch]` `adoptSession()` n'abaissait pas `hydrated` avant sa tentative : un démarrage sans session le laisse à `true` (le restore a bien abouti, il n'avait personne pour qui lire), si bien qu'une lecture en échec après connexion faisait dire à l'application « file lue, et vide » pendant que l'unique copie des preuves dormait en IndexedDB — `RecoveryView` affichait « Nothing to recover » et sa garde `if (!queue.hydrated)` ne rappelait jamais `init()`. Drapeau abaissé en tête de `adoptSession()`, relevé seulement après une hydratation réussie.
  - `[high]` `[patch]` `endSession({reason:'logout'})` attendait la révocation réseau **avant** la purge locale, inversant la garantie que la Story 1.6 avait écrite en capitales. `logoutUser` est un `fetch` sans timeout ni `AbortController` : sur portail captif, jeton, profil et liste de transactions restaient à l'écran pendant toute la pendaison — sur l'appareil qu'on vient de rendre. Purge locale synchrone remise en premier, révocation toujours attendue ensuite.
  - `[medium]` `[patch]` `flush()` résolvait `pending` une seule fois avant sa boucle, alors qu'elle attend un upload multipart par entrée : une déconnexion suivie d'une reconnexion tient dans cet intervalle, et le reste des entrées partait sous le JWT du suivant — le rejeu inter-utilisateur que la story ferme, atteint par le temps plutôt que par la file. Appartenance re-vérifiée à chaque itération (`break`).
  - `[medium]` `[patch]` `beginSession` ne purgeait le cache de lecture que si le marqueur `escrow_last_user` était **présent** et différent, laissant toute la fenêtre de mise à jour sans garde : une session antérieure à la story remplit `escrow-api-cache`, expire sans écrire de marqueur, et l'utilisateur suivant se voit servir ses réponses `/api/` hors ligne. Un marqueur absent compte désormais comme une divergence.
  - `[medium]` `[patch]` La connexion attendait le rejeu complet de la file : `login` → `applySession` → `beginSession` → `adoptSession` → `await flush()`, qui envoie en série des pièces de plusieurs mégaoctets. Le bouton restait sur « Please wait… » sans borne. Le rejeu est désormais lancé sans être attendu — la connexion attend la *lecture*, pas l'*envoi*, comme au démarrage où `main.js` n'attend pas `init()`.
  - `[medium]` `[patch]` Le commentaire d'en-tête de `stores/session.js` justifiait son existence par un cycle d'imports qu'il évitait — or le cycle est bel et bien fermé (`auth → session → escrow → auth`, et `auth → session` en direct). Commentaire corrigé pour décrire le graphe réel et la seule raison pour laquelle il est inoffensif (aucun croisement à l'évaluation), avec la consigne de maintien.
  - `[medium]` `[patch]` Le test censé couvrir le drapeau `hydrated` était vacant : il appelait `clearMemory()` sur un store neuf, où `hydrated` valait déjà `false`, si bien que l'assertion passait avec ou sans le défaut. Réécrit sur la séquence de production, et vérifié par mutation (le retrait du correctif fait échouer le test).
  - `[low]` `[patch]` Le gestionnaire d'`installSessionExpiryListener` était le seul effet du module sans `try/catch` : une navigation rejetée s'échappait en rejet non géré depuis un écouteur `async`.
  - `[low]` `[patch]` Le compte rendu de la passe dev sous-estimait les assertions préexistantes modifiées (deux égalités et deux cibles d'espion, pas une seule). Corrigé dans `## Auto Run Result`.

### 2026-07-26 — Review pass (suivi)
- intent_gap: 0
- bad_spec: 0
- patch: 12: (high 1, medium 2, low 9)
- defer: 2
- reject: 3
- addressed_findings:
  - `[high]` `[patch]` La passe précédente n'avait déplacé que la moitié de la purge locale devant la révocation réseau : `auth.clearSession()` passait en premier, mais les `$reset()` d'`escrow`/`evidence` et `clearMemory()` restaient **après** l'`await`. `logoutUser` est un `fetch` sans timeout ni `AbortController` et `DashboardView` attend `endSession` avant de naviguer : sur portail captif, seule la ligne d'e-mail s'effaçait pendant que la liste des transactions, le détail ouvert et les preuves restaient à l'écran toute la pendaison — sur l'appareil qu'on vient de rendre. Tout ce qui est local et synchrone passe désormais avant l'appel réseau. Test posé sur une révocation qui ne se résout pas (le seul moyen de distinguer les deux ordres) et vérifié par mutation.
  - `[medium]` `[patch]` La vérification d'appartenance en tête de boucle protège l'entrée **suivante**, jamais celle déjà en vol. Un upload multipart dure des minutes : une déconnexion pendant ce laps supprime l'entrée d'IndexedDB (`clearForUser`), puis le rejet définitif qui arrive ensuite la **regelait et la réécrivait** — Blobs et commentaire compris — sur l'appareil rendu. Appartenance re-vérifiée après l'`await`, avant tout gel et toute écriture. Vérifié par mutation.
  - `[medium]` `[patch]` Un 401/403 nu retardataire de la session **précédente** (rejeu multipart, téléchargement de preuve) pouvait atterrir après une reconnexion — le verrou venant d'être rouvert par `beginSession` — et détruire la session **nouvelle**, renvoyant à l'écran de connexion un utilisateur qui venait de taper son mot de passe. `api/client.js` compare désormais l'en-tête `Authorization` de la requête échouée au jeton stocké, et **échoue ouvert** : seule une requête dont on peut lire ET distinguer positivement le jeton est écartée. Vérifié par mutation.
  - `[low]` `[patch]` `flushing` est un verrou et n'était pas relâché dans un `finally` : une exception hors du `try` par entrée (la coercion d'appartenance, la résolution du store) le laissait levé pour la vie de l'onglet — chaque `flush()` ultérieur, chaque événement `online`, chaque `adoptSession()` sortant aussitôt sur le drapeau périmé, en silence. Boucle enveloppée dans `try/finally`. Vérifié par mutation.
  - `[low]` `[patch]` La garde `typeof window !== 'undefined'` de l'intercepteur était le **dernier** terme de la chaîne `&&`, donc morte : `localStorage` est évalué avant elle et manque partout où `window` manque. Le `ReferenceError` aurait remplacé l'`AxiosError` de l'appelant, et `classifyReplayFailure` aurait lu un rejet définitif comme transitoire, remettant l'entrée en file pour toujours. Garde remise en tête.
  - `[low]` `[patch]` Les deux endroits qui fabriquent l'URL de connexion divergeaient sur le tableau de bord : la garde de routeur omet `redirect` pour `/`, l'écouteur d'expiration écrivait `auth?redirect=/`. Même règle des deux côtés, avec le renvoi croisé en commentaire.
  - `[low]` `[patch]` Le JSDoc d'`auth.logout()` disait « reste le chemin correct pour tout appelant qui n'a que la session à fermer » — une invitation à recâbler un futur bouton de déconnexion sur une action qui ne purge que deux clés de localStorage, rouvrant les quatre reports fermés par cette story avec une suite verte. Réécrit en avertissement explicite, avec le seul point de sortie correct nommé.
  - `[low]` `[patch]` `endSession({reason})` dérivait `explicit = reason === 'logout'` sans branche pour un troisième cas : un appel sans argument, une faute de frappe ou un mode ajouté plus tard tombaient **en silence** sur le chemin qui conserve — donc sur l'appareil partagé qui garde les entrées. Le repli conservateur est gardé (il ne détruit rien), mais il est désormais journalisé et testé.
  - `[low]` `[patch]` `DashboardView.logout()` n'entourait pas `router.replace` : toutes les routes sont des chunks paresseux, et un chunk `AuthView` introuvable (déploiement, précache évincé) laissait l'utilisateur sur un tableau de bord vidé, sans jeton et sans issue, avec un rejet non géré. Filet de rechargement complet ajouté.
  - `[low]` `[patch]` `safeRedirect` refusait `//` mais pas `/\` — que son propre commentaire promettait d'exclure et que navigateurs et parseurs d'URL lisent comme `//`. Inoffensif aujourd'hui parce que vue-router le résout en même origine, c'est-à-dire par une propriété de l'appelant et non de la garde. Condition ajoutée, test ajouté.
  - `[low]` `[patch]` L'AC 4 exigeait que les 156 tests antérieurs passent « sans modification de leurs assertions » — impossible pour une story qui change précisément le contenu de `meta`, et déjà contredit par le livrable. Reformulée en interdiction d'**affaiblir ou supprimer** une assertion, avec l'énumération bornée des modifications admises ; l'attente de vérification correspondante alignée. Traité en `patch` et non en `bad_spec` : le code est correct, c'est la phrase qui était intenable, et une re-dérivation aurait reproduit le même code à l'identique.
  - `[low]` `[patch]` Les Design Notes justifiaient la suppression des entrées orphelines par « la Story 4.5 les a rendues provablement inatteignables » — vérification faite sur la révision de base, 4.5 n'avait retiré que leur **affichage** et `flush()` les rejouait encore. C'est cette story qui les rend inatteignables et les détruit du même geste : arbitrage assumé, pas hérité. Note corrigée.

### 2026-07-26 — Review pass (2e suivi)

- intent_gap: 0
- bad_spec: 0
- patch: 8: (high 2, medium 2, low 4)
- defer: 4
- reject: 6
- addressed_findings:
  - `[high]` `[patch]` **La suite était rouge.** `session.spec.js` échouait sur « empties the screen BEFORE waiting on the network » : la passe précédente avait déplacé `idb.clearForUser` et `purgeReadCache` devant la révocation — deux vrais allers-retours asynchrones — alors que le test ne drainait qu'un seul macrotask, si bien que `logoutUser` n'avait pas encore été appelé au moment de l'assertion. Le correctif de code était bon, sa preuve ne l'était plus. Test réécrit sur `vi.waitFor`, qui ne dépend pas d'un nombre d'`await` fixé à l'avance, **et étendu à tous les effets locaux** : IndexedDB purgée de A (l'entrée de B intacte), cache de lecture supprimé, marqueur `escrow_last_user` effacé — tous asserés pendant que la révocation est encore en vol. C'est la troisième passe consécutive où une moitié de la purge est restée derrière l'`await` sans que la suite le voie, chaque fois parce qu'elle ne contrôlait que la moitié déjà déplacée. Vérifié par mutation.
  - `[high]` `[patch]` **`views/AuthView.vue` n'était plus un fichier texte.** La classe de caractères de contrôle du garde anti-open-redirect avait été écrite avec les **octets bruts** qu'elle matche (NUL, 0x1F, 0x7F). Conséquences vérifiées, pas supposées : `file` répond `data`, `git diff --numstat` répond `- -` (fichier binaire — indiffable, non cherry-pickable, non fusionnable en 3-way), et `grep -rn "safeRedirect" src --include='*.vue'` ne renvoie **rien**, code de sortie 1. Autrement dit l'écran de connexion était sorti du champ de toute recherche textuelle — y compris du grep de vérification de cette story même (`status === 401|403` sur `frontend/src`), qui passait donc en étant aveugle à ce fichier. Regex réécrite en échappements `\u`, comportement identique ; commentaire ajouté expliquant pourquoi la forme littérale ne doit pas revenir.
  - `[medium]` `[patch]` Le filtrage des caractères de contrôle — le contrôle de sécurité le plus récent du lot — n'avait **aucun** test : `authRedirect.spec.js` couvrait `//`, `/\`, l'URL absolue, l'absence et le tableau, jamais le `?redirect=/%09/evil.example` que son propre commentaire de dix lignes existe pour contrer. Deux tests ajoutés : le cas hostile, et son complément (un chemin légitime revient octet pour octet), ce second cas attrapant la dégradation silencieuse d'une classe mal écrite en une classe qui mange les tirets. Vérifié par mutation.
  - `[medium]` `[patch]` `envelopeWasParsed()` avait été livré non testé et non consigné. Il interdit d'annoncer une session morte quand le corps n'a pas pu porter d'enveloppe (`responseType: 'blob'` du téléchargement de preuve), sans quoi un `NOT_A_PARTY` sur un téléchargement — verdict métier ordinaire — déconnecterait l'utilisateur. Aggravant : le harnais `httpError()` de `client.spec.js` fabrique des rejets **sans `config`**, si bien que les deux gardes qui lisent `error.config` dégénéraient en « laisser passer » dans presque tous les cas. Deux tests ajoutés, passant par une vraie requête axios pour que le `responseType` lu soit celui qu'axios pose réellement. Vérifié par mutation.
  - `[low]` `[patch]` Les `$reset()` défensifs qu'une passe précédente avait ajoutés en tête de `beginSession` n'étaient couverts par rien : les neuf lignes de commentaire qui les justifient auraient survécu à leur suppression sur une suite verte. Test ajouté. Vérifié par mutation.
  - `[low]` `[patch]` Aucun test n'assertait la suppression du marqueur `escrow_last_user` à la déconnexion explicite — le seul effet de `endSession` sans preuve. Couvert par le test d'ordonnancement réécrit ci-dessus.
  - `[low]` `[patch]` Le déplacement d'`escrow:sync` dans le `finally` n'était pas verrouillé : **mon premier essai d'assertion l'a été sur le chemin `break`, où la mutation n'était pas détectée** — un `break` sort normalement de la boucle et atteint tout ce qui est écrit après le bloc, seul un `throw` saute la ligne. Assertion déplacée sur le test d'échappement par exception, où elle détecte la régression. L'assertion du chemin `break` est conservée, à sa juste valeur descriptive.
  - `[low]` `[patch]` Le commentaire de `DashboardView.logout()` justifiait l'attente de la révocation par « la navigation avortait la requête en vol » — vrai de l'ancien `window.location.href`, faux de `router.replace`, que **cette story même** a introduit. Une justification périmée est ce qui fait reconduire une attente non bornée pour toujours. Commentaire réécrit sur ce qui est réellement vrai (tout le local est purgé avant le réseau) et ce qui reste faux (le `fetch` n'a ni timeout ni `AbortController`), avec renvoi au report correspondant.

**Rejets, avec leur raison.** ① « `flush()` ne revérifie pas l'appartenance avant `removeFromQueue` sur le chemin de succès » : la requête a été acceptée sous le jeton de son propriétaire, la supprimer est la bonne écriture comptable, les identifiants d'entrée ne se croisent pas et `escrow:sync` ne déclenche qu'un refetch idempotent — même famille que le rejet motivé de la passe précédente. ② « `beginSession` devrait aussi appeler `clearMemory()` » : la garde de routeur (`to.name === 'auth' && isAuthenticated → dashboard`) rend inatteignable un second `applySession` sans `endSession` intercalé, et le *merge* d'`hydrate()` est un choix documenté qu'un `clearMemory()` préalable combattrait en perdant une entrée mise en file pendant la fenêtre. ③ « `getAll()` reste un export non scopé » : la tâche 4 de la spec le conserve explicitement comme seam de test, et le grep de vérification confirme qu'aucun appelant de production ne l'utilise. ④ « Le statut du spec contredit `sprint-status.yaml`, `## Auto Run Result` est absent » : artefact d'une passe en cours, réconcilié à la finalisation. ⑤ « Une expiration déclenchée alors qu'on est déjà sur `/auth` perd le `redirect` en attente » : inatteignable — être sur `/auth` implique de ne pas être authentifié, donc pas de jeton, donc `concernsCurrentSession` renvoie faux et l'événement n'est jamais émis. ⑥ « Le bouton de déconnexion n'a pas de garde de ré-entrance » : le second passage lit `user?.id` déjà nul, ne purge donc que les orphelines (déjà parties) et révoque un jeton nul — aucune conséquence observable.

## Design Notes

**Pourquoi deux modes et pas un.** `epic-1-context.md:52` tranche explicitement : « le cache de lecture et la file offline survivent à la ré-authentification, à distinguer de la déconnexion explicite, qui, elle, purge tout ». Une expiration est **subie** — l'utilisateur n'a pas décidé de partir, ses preuves en attente doivent l'attendre ; une déconnexion est **délibérée**, c'est le geste par lequel on rend l'appareil. Fondre les deux en une seule purge détruirait des preuves à chaque jeton expiré ; les fondre en une seule conservation laisserait tout en place sur l'appareil rendu.

**Ce que « la file est vidée » veut dire exactement.** À la déconnexion de A, on supprime les entrées de A et les orphelines, jamais celles de B. Supprimer tout le store (`clear()`) serait un nouveau bug — on effacerait les preuves d'un tiers pour de l'hygiène. Les orphelines (mises en file avant la Story 4.4, sans `meta.userId`) sont supprimées parce que la Story 4.5 a **décidé** que personne ne les verrait jamais. Précision apportée à la revue de suivi, parce que la formulation initiale (« provablement inatteignables, seuls leurs octets restent ») était fausse au moment où elle a été écrite : avant cette story, `flush()` filtrait sur `!item.frozen` **seul** et rejouait donc les orphelines, qui atteignaient bel et bien le serveur. C'est **cette** story qui les rend inatteignables — hydratation et rejeu scopés — et qui, du même geste, les détruit à la déconnexion suivante. L'arbitrage tient (4.5 avait refusé de leur inventer un propriétaire, et une entrée que personne ne peut ni voir ni rejouer n'est plus qu'un résidu sur un appareil partagé) mais il est **assumé**, pas hérité : une file remplie avant la Story 4.4 perd ici sa dernière voie de livraison. Le risque est consigné en fin de document.

**Pourquoi un marqueur de dernier utilisateur.** Le cache Workbox est indexé par URL, jamais par identité : `GET /api/v1/escrow` a une seule entrée pour tout l'appareil. Conserver ce cache à travers une expiration — ce que le plan demande, et à raison, pour qu'un utilisateur au réseau instable ne perde pas son écran — le rend servable au **suivant** s'il se connecte hors ligne. Le marqueur transforme « survit à la ré-authentification » en « survit à la ré-authentification du même utilisateur », qui est ce que la phrase voulait dire. Il ne coûte qu'une clé et un `if`, et il est effacé à la déconnexion explicite, où le cache est de toute façon détruit.

**Pourquoi un filtre applicatif et pas un index IndexedDB.** Un `DB_VERSION = 2` avec index sur `meta.userId` serait la réponse « propre » à volume réel — mais la file contient quelques entrées, jamais des milliers, et une migration de schéma est le seul changement de ce lot qui pourrait perdre des données à froid. Le scoping est donc mis dans la **signature** (`getAllForUser`, `clearForUser`) plutôt que dans le schéma : un appelant ne peut plus obtenir la file entière par inadvertance, ce qui est la propriété qui compte.

**Pourquoi un événement et pas un import.** `api/client.js` est importé par `api/auth.js`, lui-même importé par `stores/auth.js`, lui-même importé par `router/index.js` : y importer le routeur ferme le cycle et livre un client axios à moitié construit selon l'ordre d'évaluation. L'événement `window` est le motif déjà en place (`escrow:sync`, `offlineQueue.js:252`), et il laisse `client.js` sans aucune dépendance applicative.

**Le drapeau anti-rafale.** Un écran qui charge trois ressources en parallèle produit trois 403 : sans drapeau, trois teardowns et trois navigations concurrentes. Le drapeau est remis à zéro par `beginSession()`, donc à la connexion suivante — pas par un minuteur, qui rouvrirait la fenêtre au mauvais moment.

**Ce qu'on ne corrige délibérément pas.** Le backend continue de répondre 403 nu, sans `code` : lui donner un vocabulaire de session (`SESSION_EXPIRED`) casserait `PartnerSecurityMatcherTest:70-73`, la règle `replayFailure.js:172` et la partition figée d'`ErrorCodeContractTest`. La discrimination par absence de `code` est le contrat maison, déjà éprouvé par 23 tests.

## Verification

**Commands:**
- `cd frontend && npm run test` -- attendu : 0 échec, compteur ≥ 156 + les nouveaux tests ; aucune assertion préexistante **affaiblie ni supprimée** (voir l'AC 4 : les modifications rendues nécessaires par le stamp `meta.userId` sont énumérées et bornées).
- `cd frontend && npm run build` -- attendu : build vert (le nouveau module et l'écouteur de `main.js` sont bien résolus par Vite).
- `git status --porcelain backend/` -- attendu : aucune sortie (story strictement frontend, aucune migration `V10__`).
- `grep -rn "status === 401\|status === 403" frontend/src --include=*.js --include=*.vue` -- attendu : les seules occurrences sont dans `utils/replayFailure.js` (définition unique du prédicat) ; aucune dans `api/client.js`.
- `grep -rn "idb.getAll\b\|getAll()" frontend/src/stores` -- attendu : aucun appel non scopé hors du helper de test.
- `grep -n "escrow-api-cache" frontend/vite.config.js frontend/src/stores/session.js` -- attendu : le même littéral des deux côtés.

**Manual checks (if no CLI):**
- Dans `stores/session.js`, `user.id` est lu **avant** `auth.clearSession()` : l'inverse purgerait avec un identifiant `undefined`, donc les orphelines seulement.
- `offlineQueue.init()` pose toujours les écouteurs `online`/`offline`, session ou non — seule l'hydratation est conditionnée.
- `SyncFailureNotice.vue` et `RecoveryView.vue` sont inchangés (`git diff --stat` ne les liste pas).


## Auto Run Result

### Passe dev

19/19 tâches livrées. **226 tests frontend verts** (156 avant la story), `npm run build` vert, `git status --porcelain backend/` vide.

Fichiers créés : `frontend/src/stores/session.js` (orchestrateur `endSession`/`beginSession`/`installSessionExpiryListener`/`purgeReadCache`), `frontend/src/stores/__tests__/session.spec.js`, `frontend/src/api/__tests__/client.spec.js`, `frontend/src/views/__tests__/authRedirect.spec.js`.

Fichiers modifiés : `utils/replayFailure.js` (extraction d'`isBareAuthFailure`), `api/client.js` (verrou + événement `escrow:session-expired`, plus aucune purge ni `window.location`), `stores/auth.js` (`clearSession`/`revokeOnServer` scindées, `USER_STORAGE_KEY` exporté, `applySession` → `beginSession`), `stores/offlineQueue.idb.js` (`getAllForUser`/`clearForUser`, `getAll` rétrogradé en seam de test), `stores/offlineQueue.js` (hydratation/rejeu scopés, `clearMemory`, `adoptSession`), `router/index.js`, `views/AuthView.vue` (`safeRedirect`), `views/DashboardView.vue`, `main.js`, `deferred-work.md` (4 reports marqués résolus).

### Première passe de revue (2026-07-26)

Deux relecteurs adverses en parallèle sans contexte préalable sur un diff de 2295 lignes : **9 correctifs** (2 hauts, 5 moyens, 2 bas), 4 reports, 2 rejets, aucun `intent_gap`, aucun `bad_spec`. 227 tests verts à l'issue.

### Passe de revue de suivi (2026-07-26)

Deux relecteurs adverses relancés à froid sur le diff complet depuis `d9bed15` : **12 correctifs** (1 haut, 2 moyens, 9 bas), **2 reports**, **3 rejets**, toujours aucun `intent_gap` ni `bad_spec`. Le constat haut était un correctif de la passe précédente resté à moitié appliqué : la purge locale avait été remise devant la révocation réseau pour les seuls identifiants, les `$reset()` et `clearMemory()` restant derrière l'`await`. 235 tests verts. Quatre correctifs éprouvés par mutation.

### 2e passe de revue de suivi (2026-07-26)

**Point de départ inhabituel, à consigner : la passe précédente avait été interrompue.** Le répertoire de travail contenait cinq fichiers modifiés non commités — trois d'entre eux portant de vraies modifications de comportement (`api/client.js`, `stores/offlineQueue.js`, `stores/session.js`) — et **la suite était rouge** (234/235). Ces modifications n'étaient consignées nulle part : ni journal de triage, ni commit, ni `## Auto Run Result` (la section avait été effacée en début de passe et jamais reconstruite). Le diff de revue de cette passe couvre donc l'état complet du répertoire de travail depuis `d9bed15`, committé et non committé confondus : le delta orphelin a été relu comme le reste, et il est inclus dans le commit final.

Deux relecteurs adverses relancés à froid sur les 3290 lignes de ce diff : **8 correctifs** (2 hauts, 2 moyens, 4 bas), **4 reports**, **6 rejets**, aucun `intent_gap`, aucun `bad_spec`.

Les deux constats hauts sont d'une nature qu'aucune assertion existante ne pouvait attraper. Le premier est la suite rouge elle-même : le correctif d'ordonnancement était juste, mais sa preuve ne l'était plus, le test drainant un nombre de macrotasks fixé à l'avance là où deux allers-retours asynchrones venaient de s'ajouter devant la révocation. Le second est que `views/AuthView.vue` **n'était plus un fichier texte** : la classe de caractères de contrôle du garde anti-open-redirect avait été écrite avec les octets bruts qu'elle matche, ce qui rend le fichier binaire pour `git` (indiffable) et invisible à `grep` — y compris au grep de vérification de cette story même, qui passait donc en étant aveugle à l'écran de connexion. Le comportement du garde était correct ; c'est précisément pourquoi rien ne l'avait signalé.

**Aucun comportement de production n'a été modifié par cette passe.** Sept des huit correctifs sont des tests ou des commentaires ; le huitième réécrit une regex en échappements `\u` sans changer ce qu'elle matche. Les trois passes réunies totalisent 29 correctifs, 10 reports et 11 rejets.

**Vérification.** `npm run test` : **240 tests verts, 11 fichiers** (235 en entrée de passe, +5). `npm run build` vert (13 entrées précachées, service worker généré). `git status --porcelain backend/` vide. Les greps de la section Verification sont tous conformes — et pour la première fois ils sont *concluants*, le fichier qui leur échappait étant redevenu lisible : définition unique du prédicat 401/403 dans `utils/replayFailure.js:174`, aucun appel `getAll()` non scopé hors du seam de test et de l'usage interne du module IDB, même littéral `escrow-api-cache` des deux côtés, aucun octet nul nulle part sous `frontend/src`. **Cinq correctifs éprouvés par mutation** (ordonnancement de la purge, filtrage des caractères de contrôle, `envelopeWasParsed`, `$reset()` de `beginSession`, `escrow:sync` dans le `finally`) : retirer le code fait échouer le test correspondant, à chaque fois.

Un mot sur cette dernière vérification, parce qu'elle a servi à autre chose qu'à confirmer : ma première assertion sur `escrow:sync` avait été posée sur le chemin `break`, où la mutation **n'était pas** détectée — un `break` sort normalement de la boucle et atteint tout ce qui est écrit après le bloc, seul un `throw` saute la ligne. L'assertion a été déplacée sur le test d'échappement par exception. Sans la passe de mutation, un test vert et inutile aurait été livré comme preuve.

### Écart assumé sur l'AC 4

L'AC 4 exigeait initialement que les tests antérieurs passent « sans modification de leurs assertions ». Intenable pour une story qui change précisément le contenu de `meta` : elle a été reformulée en interdiction d'**affaiblir ou supprimer** une assertion. Décompte exact des modifications d'assertions préexistantes, inchangé depuis :

- **Deux assertions d'égalité réécrites**, parce que `meta` porte désormais un propriétaire : `escrow.offline.spec.js` (`{ type: 'OPEN_DISPUTE', transactionId: 7 }` → `+ userId: 42`) et `offlineQueue.spec.js` (`{ type: 'CREATE_TRANSACTION' }` → `+ userId: USER.id`).
- **Deux cibles d'espion changées** : `idb.getAll` → `idb.getAllForUser`, la fonction que le code appelle maintenant.
- **Trois fixtures** attendent désormais `applySession` (`escrow.offline.spec.js`, `RecoveryView.spec.js`, et l'aide `signInAndReplay`), la production l'attendant aussi.

Aucune assertion supprimée ni affaiblie. La couverture du cas anonyme n'est pas perdue : le test dédié qui asserte `meta.userId === undefined` sans session est intact. Un test a été *renforcé* à cette passe (l'ordonnancement de la déconnexion, qui ne contrôlait que la mémoire et contrôle désormais aussi IndexedDB, le cache et le marqueur).

### Dette laissée

`SyncFailureNotice.spec.js` et `SyncFailureNotice.recovery.spec.js` n'attendent pas `applySession`. Ils passent — ils ne touchent jamais `hydrated` et n'écrivent jamais en IndexedDB — mais ils partagent la course latente des fixtures corrigées.

### Risques résiduels

- **Fermer l'onglet n'est pas se déconnecter** : JWT de 24 h en `localStorage`, aucune expiration d'inactivité. C'est le chemin le plus probable de l'appareil partagé et la story ne le couvre pas — son AC ne porte que sur une session que l'utilisateur *quitte*. La NFR-P8 n'est donc pas tenue pour ce chemin.
- **Une lecture en vol peut franchir la frontière de session** : `escrow.loadTransactions` écrit sans garde et le `$reset()` d'`evidence` rembobine son compteur anti-course. Les deux `$reset()` réduisent fortement la fenêtre sans pouvoir l'annuler — l'AC 1 (« à aucun instant du parcours ») n'est donc pas tenue au sens strict. Report de cette passe.
- **La déconnexion attend un `fetch` non borné** : sur portail captif, l'écran est vidé mais la navigation ne vient jamais. Hygiène intacte, usage dégradé. Report de cette passe.
- **`purgeReadCache()` peut être doublée par une réponse déjà en vol**, que le service worker écrit après la suppression du bucket. Report de cette passe.
- La déconnexion n'est pas propagée aux autres onglets ; `caches.delete` laisse l'index d'expiration Workbox (métadonnées d'URL, pas de contenu) ; le câblage de production des deux primitives n'a pas de test. Reports des passes précédentes.
- **Aucun ESLint n'est configuré** alors que six directives `eslint-disable` sont écrites dans le code : c'est exactement ce garde-fou absent qui a laissé passer le fichier binaire. Report de cette passe.
- Les entrées orphelines (mises en file avant la Story 4.4) deviennent définitivement non rejouables et sont supprimées à la déconnexion suivante. Arbitrage **délibéré**, assumé par cette story et non hérité de la 4.5.
