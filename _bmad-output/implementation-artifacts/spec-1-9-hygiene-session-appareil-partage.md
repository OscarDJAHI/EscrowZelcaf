---
title: 'Story 1.9 — Hygiène de session sur appareil partagé'
type: 'feature'
created: '2026-07-26'
status: 'done'
baseline_revision: 'd9bed15874b871efe7665966a90811afa146ef9e'
final_revision: 'd431d5f39fbe18e1365af4910589948ef45a2bff'
review_loop_iteration: 0
followup_review_recommended: true
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
- Given la suite frontend existante, when elle est rejouée, then les 156 tests antérieurs passent sans modification de leurs assertions, et le contrat d'erreur backend est inchangé (aucun fichier sous `backend/` modifié).

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

## Design Notes

**Pourquoi deux modes et pas un.** `epic-1-context.md:52` tranche explicitement : « le cache de lecture et la file offline survivent à la ré-authentification, à distinguer de la déconnexion explicite, qui, elle, purge tout ». Une expiration est **subie** — l'utilisateur n'a pas décidé de partir, ses preuves en attente doivent l'attendre ; une déconnexion est **délibérée**, c'est le geste par lequel on rend l'appareil. Fondre les deux en une seule purge détruirait des preuves à chaque jeton expiré ; les fondre en une seule conservation laisserait tout en place sur l'appareil rendu.

**Ce que « la file est vidée » veut dire exactement.** À la déconnexion de A, on supprime les entrées de A et les orphelines, jamais celles de B. Supprimer tout le store (`clear()`) serait un nouveau bug — on effacerait les preuves d'un tiers pour de l'hygiène. Les orphelines (mises en file avant la Story 4.4, sans `meta.userId`) sont supprimées parce que la Story 4.5 a **décidé** que personne ne les verrait jamais : elles sont provablement inatteignables, seuls leurs octets restent sur un appareil partagé.

**Pourquoi un marqueur de dernier utilisateur.** Le cache Workbox est indexé par URL, jamais par identité : `GET /api/v1/escrow` a une seule entrée pour tout l'appareil. Conserver ce cache à travers une expiration — ce que le plan demande, et à raison, pour qu'un utilisateur au réseau instable ne perde pas son écran — le rend servable au **suivant** s'il se connecte hors ligne. Le marqueur transforme « survit à la ré-authentification » en « survit à la ré-authentification du même utilisateur », qui est ce que la phrase voulait dire. Il ne coûte qu'une clé et un `if`, et il est effacé à la déconnexion explicite, où le cache est de toute façon détruit.

**Pourquoi un filtre applicatif et pas un index IndexedDB.** Un `DB_VERSION = 2` avec index sur `meta.userId` serait la réponse « propre » à volume réel — mais la file contient quelques entrées, jamais des milliers, et une migration de schéma est le seul changement de ce lot qui pourrait perdre des données à froid. Le scoping est donc mis dans la **signature** (`getAllForUser`, `clearForUser`) plutôt que dans le schéma : un appelant ne peut plus obtenir la file entière par inadvertance, ce qui est la propriété qui compte.

**Pourquoi un événement et pas un import.** `api/client.js` est importé par `api/auth.js`, lui-même importé par `stores/auth.js`, lui-même importé par `router/index.js` : y importer le routeur ferme le cycle et livre un client axios à moitié construit selon l'ordre d'évaluation. L'événement `window` est le motif déjà en place (`escrow:sync`, `offlineQueue.js:252`), et il laisse `client.js` sans aucune dépendance applicative.

**Le drapeau anti-rafale.** Un écran qui charge trois ressources en parallèle produit trois 403 : sans drapeau, trois teardowns et trois navigations concurrentes. Le drapeau est remis à zéro par `beginSession()`, donc à la connexion suivante — pas par un minuteur, qui rouvrirait la fenêtre au mauvais moment.

**Ce qu'on ne corrige délibérément pas.** Le backend continue de répondre 403 nu, sans `code` : lui donner un vocabulaire de session (`SESSION_EXPIRED`) casserait `PartnerSecurityMatcherTest:70-73`, la règle `replayFailure.js:172` et la partition figée d'`ErrorCodeContractTest`. La discrimination par absence de `code` est le contrat maison, déjà éprouvé par 23 tests.

## Verification

**Commands:**
- `cd frontend && npm run test` -- attendu : 0 échec, compteur ≥ 156 + les nouveaux tests ; aucune assertion préexistante modifiée.
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

19/19 tâches livrées. **226 tests frontend verts** (156 avant la story, soit +70), `npm run build` vert, `git status --porcelain backend/` vide.

Fichiers créés : `frontend/src/stores/session.js` (orchestrateur `endSession`/`beginSession`/`installSessionExpiryListener`/`purgeReadCache`), `frontend/src/stores/__tests__/session.spec.js` (21 tests), `frontend/src/api/__tests__/client.spec.js` (10 tests), `frontend/src/views/__tests__/authRedirect.spec.js` (9 tests).

Fichiers modifiés : `utils/replayFailure.js` (extraction d'`isBareAuthFailure`), `api/client.js` (verrou + événement `escrow:session-expired`, plus aucune purge ni `window.location`), `stores/auth.js` (`clearSession`/`revokeOnServer` scindées, `USER_STORAGE_KEY` exporté, `applySession` → `beginSession`), `stores/offlineQueue.idb.js` (`getAllForUser`/`clearForUser`, `getAll` rétrogradé en seam de test), `stores/offlineQueue.js` (hydratation/rejeu scopés, `clearMemory`, `adoptSession`), `router/index.js`, `views/AuthView.vue` (`safeRedirect`), `views/DashboardView.vue`, `main.js`, `deferred-work.md` (4 reports marqués résolus).

### Deux correctifs apportés après le retour du sous-agent

1. **`adoptSession()` ne repositionnait pas `hydrated`.** `clearMemory()` abaisse le drapeau à la fin de session ; l'adoption qui suit lisait bien la file mais sans le consigner. Pas de perte de données (`RecoveryView` se rattrapait en rappelant `init()`), mais le drapeau mentait et coûtait un aller-retour IndexedDB redondant plus une frame de spinner. Corrigé dans le `try`, après `hydrate()`, comme `init()` — un échec doit rester rejouable. Deux tests ajoutés (succès et échec de stockage).
2. **Course révélée par ce correctif** : `RecoveryView.spec.js` n'attendait pas `applySession`, dont la promesse `beginSession` atterrissait en cours de test et écrasait un `hydrated = false` volontaire. La fixture attend désormais `applySession`, comme la production (`auth.login` l'attend) et comme `escrow.offline.spec.js` le fait déjà.

### Écart assumé sur l'AC 4

L'AC 4 exigeait que les tests antérieurs passent **sans modification de leurs assertions**. Ce n'est pas tenu, et la revue a montré que mon premier compte rendu le sous-estimait — voici le décompte exact :

- **Deux assertions d'égalité réécrites**, toutes deux parce que `meta` porte désormais un propriétaire : `escrow.offline.spec.js` (`{ type: 'OPEN_DISPUTE', transactionId: 7 }` → `+ userId: 42`) et `offlineQueue.spec.js` (`{ type: 'CREATE_TRANSACTION' }` → `+ userId: USER.id`). Ces deux tests mettaient en file **anonymement** ; depuis la story une entrée sans `meta.userId` n'est plus jamais hydratée ni rejouée, donc ils devaient ouvrir une session pour continuer à éprouver ce qu'ils éprouvaient (l'aller-retour binaire), et non le chemin anonyme.
- **Deux cibles d'espion changées** : `idb.getAll` → `idb.getAllForUser`, la fonction que le code appelle maintenant.
- **Trois fixtures** attendent désormais `applySession` (`escrow.offline.spec.js`, `RecoveryView.spec.js`, et l'aide `signInAndReplay` des tests de cette story), la production l'attendant aussi (`auth.login`).

La couverture du cas anonyme n'est pas perdue : le test dédié qui asserte `meta.userId === undefined` sans session est intact. La substance de l'AC (aucune régression fonctionnelle, backend intact) tient — 227/227 verts, `backend/` non touché — mais l'AC telle qu'écrite était trop stricte pour une story qui change précisément ce que `meta` doit contenir : elle aurait dû interdire l'affaiblissement d'une assertion, pas toute modification.

### Dette laissée

`SyncFailureNotice.spec.js` et `SyncFailureNotice.recovery.spec.js` n'attendent pas non plus `applySession`. Ils passent — ils ne touchent jamais `hydrated` et n'écrivent jamais en IndexedDB, si bien que l'adoption tardive est sans effet observable pour eux — mais ils partagent la même course latente que les deux fixtures corrigées.

### Passe de revue (2026-07-26)

Deux relecteurs adverses lancés en parallèle sans contexte préalable (Blind Hunter, Edge Case Hunter) sur un diff de 2295 lignes. Après déduplication et vérification : **9 correctifs appliqués** (2 hauts, 5 moyens, 2 bas), **4 reports** au ledger, **2 rejets**, aucun `intent_gap`, aucun `bad_spec` — le détail est dans le journal de triage ci-dessus.

Les deux correctifs hauts touchaient des garanties, pas du confort : `adoptSession()` pouvait faire passer un échec de stockage pour « file lue et vide » et condamner l'écran de récupération, et `endSession` attendait le réseau avant de vider la session locale, laissant l'écran d'un utilisateur en place pendant toute une pendaison de `fetch` sur l'appareil qu'il venait de rendre. Un correctif moyen ferme en outre un rejeu inter-utilisateur atteint par le temps (bascule de session pendant un upload multipart) — le cœur même de la story, que le filtre initial, calculé une fois avant la boucle, ne couvrait pas.

**Rejets, avec leur raison.** ① « Un 403 nu peut aussi venir du `CorsFilter` de Spring (`SecurityConfig.java:126-129`), donc une origine mal configurée déconnecterait en boucle » : le chemin n'est pas atteignable depuis un navigateur — cette réponse ne porte pas d'en-tête `Access-Control-Allow-Origin`, le navigateur la bloque, et axios reçoit une erreur réseau **sans** `response`, d'où `{code: null, status: null}` qui ne satisfait pas `isBareAuthFailure`. ② « Le bouton de déconnexion est réentrant faute d'état désactivé » : réel avant le correctif d'ordonnancement, sans conséquence après — un second clic ne trouve plus ni jeton ni état à purger.

**Vérification de la passe de revue.** `npm run test` : **227 tests verts, 11 fichiers** (156 avant la story). `npm run build` vert. `git status --porcelain backend/` vide. Les deux nouveaux garde-fous ont été **éprouvés par mutation** : le retrait de la coupure de rejeu en vol et celui de l'abaissement de `hydrated` font chacun échouer leur test.

### Risques résiduels

- La déconnexion n'est pas propagée aux autres onglets (report 1) : un second onglet ouvert reste dans une UI authentifiée non fonctionnelle jusqu'à son propre rechargement. Sans régression par rapport à l'état antérieur, mais la story ne ferme pas ce cas.
- `caches.delete` laisse l'index d'expiration Workbox (report 2) : les URL `/api/` visitées par le partant — donc les identifiants des transactions ouvertes — restent lisibles sur l'appareil jusqu'à éviction. Métadonnées seulement, aucun contenu.
- Le câblage de production des deux nouvelles primitives (bouton de déconnexion, `main.js`) n'a pas de test (report 3) : une régression de câblage laisserait la fonctionnalité verte.
- Les entrées orphelines (mises en file avant la Story 4.4) deviennent définitivement non rejouables et sont supprimées à la déconnexion suivante. C'est un arbitrage **délibéré**, documenté à trois endroits, et la Story 4.5 avait déjà refusé de leur inventer un propriétaire — mais la conséquence sur le *rejeu* est nouvelle ici, là où 4.5 ne portait que sur l'*affichage*.
