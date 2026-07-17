---
title: 'Story 4.1 : Migrer la file hors-ligne vers IndexedDB (porter le binaire)'
type: 'refactor'
created: '2026-07-17'
status: 'done'
baseline_revision: '9e91506028196d68de0dbff2756f76ecca6e1284'
final_revision: '2603979df3d45e6fab7ae2a9328edb330adbd1ad'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [multiple-goals, oversized]
---

<intent-contract>

## Intent

**Problem:** Le store `offlineQueue` persiste sa file en `localStorage` via `JSON.stringify`, ce qui ne peut pas porter de `Blob` : un dépôt de preuve hors-ligne (≤ 10 Mo) est aujourd'hui impossible à mettre en file, et le quota localStorage serait de toute façon dépassé. Tout l'Epic 4 (litige hors-ligne, réconciliation, récupération) repose sur cette fondation de persistance binaire.

**Approach:** Refondre la persistance du store vers **IndexedDB** (via `idb`), en conservant l'API publique du store et le comportement des entrées existantes sans binaire (`create`, `event`). Une entrée peut désormais porter un tableau `files` de `Blob` persistés tels quels ; au `flush()`, une entrée porteuse de binaires est rejouée en **multipart** (les autres restent rejouées en JSON, à l'identique d'aujourd'hui). Les entrées `localStorage` préexistantes sont migrées vers IndexedDB au premier démarrage, puis la clé localStorage est supprimée.

## Boundaries & Constraints

**Always:**
- L'API publique du store reste : `isOnline`, `queue`, `flushing`, `pendingCount`, `init()`, `enqueue()`, `removeFromQueue()`, `flush()`. `enqueue()` continue de **retourner l'entrée créée** (désormais via une promesse) — `stores/escrow.js` en dépend.
- Les noms de parts multipart restent **`files`** (clé répétée), `comment`, `clientCapturedAt` — identiques à `stores/escrow.js` et `stores/evidence.js`. Ne pas écrire `files[]`.
- Aucune perte silencieuse de binaire : un échec d'écriture IndexedDB doit **remonter** à l'appelant (ne jamais avaler l'erreur comme le fait l'actuel `loadQueue`).
- Non-régression stricte des entrées sans binaire : `CREATE_TRANSACTION` et `SEND_EVENT` gardent exactement leur forme (`method`, `url`, `data`, `meta`) et leur rejeu JSON.
- Les tests prouvant la persistance binaire doivent comparer les **octets** (`await blob.arrayBuffer()`), jamais une égalité de surface sur l'objet `Blob`.

**Block If:**
- Le contrat multipart du serveur (`POST /api/v1/escrow/{id}/dispute`) exigerait des noms de parts différents de ceux déjà utilisés côté front.

**Never:**
- Ne pas classer les échecs transitoires / permanents au `flush()` : le `break` aveugle actuel **reste tel quel** — c'est le périmètre explicite de la Story 4.3.
- Ne pas introduire d'état optimiste `DISPUTED`, de notification de synchro, ni d'écran de récupération (Stories 4.2, 4.4, 4.5).
- Ne pas câbler l'UI de litige hors-ligne (Story 4.2) : cette story livre la fondation de persistance et son rejeu, pas le parcours utilisateur.
- Ne pas encoder les binaires en base64, ni conserver un repli localStorage pour la file.
- Ne pas ajouter de validation de taille/format dans le store : `utils/evidence.js` reste l'autorité côté front.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Mise en file avec binaire | `enqueue({ method:'post', url:'/api/v1/escrow/7/dispute', files:[Blob 3 Mo], data:{comment:'...'} })` hors-ligne | L'entrée est écrite en IndexedDB ; `pendingCount` = 1 ; l'entrée retournée porte un `id` | Erreur d'écriture IDB → la promesse rejette |
| Survie au rechargement | Une entrée avec `Blob` 3 Mo en base, nouveau store instancié + `init()` | Après hydratation : entrée présente, `files[0]` est un `Blob`, `type` et **octets identiques** | — |
| Non-régression sans binaire | `enqueue({ method:'post', url:'/api/v1/escrow', data:{...}, meta:{type:'CREATE_TRANSACTION'} })` | Entrée persistée sans champ `files` ; rejeu via `apiClient.request({method,url,data})` en JSON | Inchangé : `break` sur échec |
| Rejeu multipart | Entrée avec `files:[Blob]`, en ligne, `flush()` | POST multipart : part `files` répétée, `comment` / `clientCapturedAt` si présents ; entrée supprimée d'IndexedDB au succès | Échec → entrée **conservée** (binaire inclus) + `break` |
| Migration localStorage | Clé `escrow_offline_queue` contenant 2 entrées legacy, IndexedDB vide | Les 2 entrées sont importées dans IndexedDB, la clé localStorage est supprimée, `pendingCount` = 2 | JSON corrompu → file vide, clé supprimée, pas de crash |
| Ordre de rejeu | 3 entrées mises en file successivement | `flush()` les rejoue dans l'ordre de mise en file (FIFO) | — |

</intent-contract>

## Code Map

- `frontend/src/stores/offlineQueue.js` -- **cœur de la story** : persistance localStorage → IndexedDB, hydratation async, rejeu multipart. API publique préservée.
- `frontend/src/stores/offlineQueue.idb.js` (nouveau) -- couche d'accès IndexedDB isolée (`openDB`, get/put/delete, migration localStorage), testable seule.
- `frontend/src/main.js:17` -- appelle `init()` sans `await` avant `app.mount()` ; l'hydratation devient async (`pendingCount` vaut 0 le temps d'un tick — acceptable, `OnlineBanner` est réactif).
- `frontend/src/stores/escrow.js:54,89` -- les 2 sites `enqueue()` sans binaire (create/event) : non-régression à préserver ; ils sont déjà dans des actions `async`.
- `frontend/src/utils/evidence.js` -- `MAX_EVIDENCE_SIZE` (10 Mo) et formats autorisés : autorité de validation, non dupliquée dans le store.
- `frontend/src/api/client.js` -- instance axios (défaut JSON) ; le rejeu multipart doit passer `Content-Type: multipart/form-data` explicitement, comme `api/evidence.js` et `api/escrow.js`.
- `frontend/package.json` -- ajout de `idb` en dépendance directe, devDeps de test et script `test`.
- `frontend/vite.config.js` -- **ne pas y toucher** : il importe `defineConfig` depuis `vite` (pas `vitest/config`) ; le harnais vit dans un `vitest.config.js` séparé qui redéclare l'alias `@`.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/package.json` -- ajouter `idb` en **dependencies** (présent aujourd'hui seulement en transitif via workbox — ne pas s'y fier) ; ajouter en devDependencies `vitest`, `jsdom`, `fake-indexeddb`, `@vue/test-utils` ; ajouter le script `"test": "vitest run"` -- amorcer le harnais absent du repo. **Écart assumé :** `undici` a dû être ajouté en devDependency (voir Design Notes — le `FormData` de jsdom refuse un `Blob` Node).
- [x] `frontend/vitest.config.js` -- créer : `environment: 'jsdom'`, alias `@` → `./src`, `setupFiles: ['./vitest.setup.js']` -- l'alias est requis (tout le code importe via `@/`).
- [x] `frontend/vitest.setup.js` -- créer : `import 'fake-indexeddb/auto'` **puis** restaurer `globalThis.Blob` (et `File`) depuis `node:buffer` -- **obligatoire** : voir Design Notes, le `Blob` de jsdom est silencieusement détruit par fake-indexeddb.
- [x] `frontend/src/stores/offlineQueue.idb.js` -- créer la couche IDB : base `escrow-offline`, object store `queue` (`keyPath: 'id'`), fonctions `getAll/put/remove`, et `migrateFromLocalStorage()` (import des entrées legacy puis suppression de la clé `escrow_offline_queue`) -- isole l'async et rend la migration testable.
- [x] `frontend/src/stores/offlineQueue.js` -- refondre : `queue: []` dans `state()`, `hydrate()` async appelée par `init()`, `enqueue`/`removeFromQueue` async écrivant en IDB, `flush()` reconstruisant un `FormData` quand l'entrée porte `files` -- le `state()` synchrone ne peut plus lire une source async.
- [x] `frontend/src/stores/__tests__/offlineQueue.spec.js` -- créer : couvrir chaque ligne de la matrice I/O ci-dessus, avec comparaison **octet par octet** après round-trip -- premier test front du repo, il matérialise la promesse « aucune perte de binaire ».

**Acceptance Criteria:**
- Given une entrée portant un `Blob` de 3 Mo mise en file hors-ligne, when la PWA est rechargée (nouveau store + `init()`), then l'entrée est hydratée depuis IndexedDB avec son `Blob` intact (`type` conservé, octets identiques) et reste rejouable.
- Given des entrées `create`/`event` déjà en file, when la refonte est déployée, then elles continuent d'être mises en file, persistées et rejouées en JSON sans changement de forme ni d'ordre.
- Given une file existante en `localStorage` (utilisateur déjà installé), when le store s'initialise pour la première fois après la migration, then les entrées sont reprises dans IndexedDB et la clé `escrow_offline_queue` est supprimée — aucune entrée perdue.
- Given le harnais de test amorcé, when `npm run test` est lancé dans `frontend/`, then la suite s'exécute et passe au vert.
- Given `localStorage` n'est plus la persistance de la file, when on inspecte `frontend/src/stores/offlineQueue.js`, then plus aucune écriture de la file vers `localStorage` ne subsiste (`escrow_token`/`escrow_user` de `auth.js` ne sont pas concernés).

## Spec Change Log

## Review Triage Log

### 2026-07-17 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 11: (high 1, medium 5, low 5)
- defer: 0
- reject: 5
- addressed_findings:
  - `[high]` `[patch]` `flush()` traitait un échec de suppression IndexedDB **après un POST accepté** comme un échec d'envoi : `break` puis renvoi de la requête au flush suivant — transaction dupliquée. La suppression sort du `try` de la requête ; un échec de bookkeeping retire l'entrée de la mémoire et n'est plus rejoué. Le `break` aveugle sur échec de *requête* reste intact (périmètre 4.3). Test prouvé non-vacuant par mutation (sans le garde : 2 POST).
  - `[medium]` `[patch]` `escrow.js` laissait une carte optimiste fantôme si `enqueue()` rejetait (rien en base, rien à synchroniser) : rollback ajouté. **Le test a révélé un bug dans le correctif lui-même** — Pinia stocke un proxy réactif, donc le filtre par identité (`t !== optimistic`) ne retirait rien ; correction par `id`.
  - `[medium]` `[patch]` `init()` posait `initialized = true` avant les `await` sans try/catch : une erreur de stockage vidait la file **pour toute la session** (et remontait en unhandled rejection, `main.js` n'attendant pas). Séparation `initialized` (écouteurs, une fois) / `hydrated` (réessayable) ; `init()` ne rejette plus jamais.
  - `[medium]` `[patch]` `hydrate()` écrasait la file au lieu de fusionner : une entrée mise en file pendant l'hydratation (fenêtre réelle car `main.js` n'attend pas `init()`) disparaissait de l'UI jusqu'au prochain rechargement. Fusion par `id`.
  - `[medium]` `[patch]` `getDB()` mémoïsait une promesse **rejetée** : toute panne d'ouverture transitoire désactivait la file jusqu'au rechargement. `.catch()` qui purge le memo et relaie l'erreur.
  - `[medium]` `[patch]` FIFO cassé pour deux entrées de la même milliseconde : `timestamp` (résolution ms) n'est pas un ordre total et le tri retombait sur le suffixe **aléatoire** de l'`id`. Ajout d'un compteur monotone `seq` comme départage.
  - `[low]` `[patch]` `migrateFromLocalStorage()` : accès `localStorage` protégé par try/catch (l'accesseur **lève** SecurityError si les cookies sont bloqués — le garde `typeof` ne l'attrape pas).
  - `[low]` `[patch]` `migrateFromLocalStorage()` retournait `legacy.length` au lieu du nombre réellement importé, contredisant sa JSDoc.
  - `[low]` `[patch]` `buildFormData()` : `if (data.comment)` droppait un commentaire vide (le rejet appartient au serveur) → `!= null` ; contrat des parts figées documenté pour la Story 4.2.
  - `[low]` `[patch]` Commentaire « no quota ceiling to trip over » faux : IndexedDB est soumis au quota et lève `QuotaExceededError` (le test le simule).
  - `[low]` `[patch]` Commentaire « lets axios compute the boundary » inexact : l'adaptateur retire l'en-tête, c'est le navigateur qui pose la frontière.

## Design Notes

**Piège de test validé par spike (décision non négociable).** Sous `environment: 'jsdom'`, `fake-indexeddb` sérialise le `Blob` de jsdom en un objet vide `{}` : **aucune erreur levée**, `size` devient `undefined`, les octets sont perdus. `happy-dom` échoue de la même façon. Un `expect(entry.files[0]).toEqual(blob)` passerait donc *à vide* en détruisant le binaire. Recette prouvée : garder `jsdom`, puis dans le setup restaurer le `Blob` natif de Node (structured-cloneable), qui round-trip intact et reste accepté par le `FormData` de jsdom.

```js
// vitest.setup.js
import 'fake-indexeddb/auto'              // installe les globals IDB*
import { Blob, File } from 'node:buffer'  // le Blob de jsdom est détruit par fake-indexeddb
globalThis.Blob = Blob
globalThis.File = File
```

C'est un artefact **du harnais de test uniquement** : dans un vrai navigateur, `Blob` est nativement structured-cloneable et IndexedDB le stocke sans conversion.

**Corollaire découvert à l'implémentation : le `FormData` doit venir du même realm que le `Blob`.** Le spike initial concluait à tort que le `FormData` de jsdom acceptait un `Blob` Node (l'assertion `fd.get(...) != null` passait… sur la chaîne `"[object Blob]"`). En réalité, jsdom brand-check `Blob` contre sa **propre** implémentation : un `Blob` de `node:buffer` échoue au test, tombe dans la coercition en chaîne et le binaire est détruit exactement là où le rejeu multipart doit prouver qu'il survit. Le setup bascule donc aussi `globalThis.FormData` sur celui d'`undici` (l'implémentation derrière le `FormData` global de Node), qui accepte le `Blob` Node.

Cet import d'`undici` **doit rester dynamique et sous le swap de `Blob`** : `undici` résout son brand-check `webidl.is.Blob` contre `globalThis.Blob` à l'évaluation du module, et un `import` statique est hissé au-dessus des affectations — il recapturerait le `Blob` de jsdom et réintroduirait silencieusement le bug.

**Hydratation async.** `state()` ne peut plus appeler `loadQueue()` : IndexedDB est asynchrone. `init()` (déjà appelée sans `await` depuis `main.js`) devient async et enchaîne `migrateFromLocalStorage()` → `hydrate()` → `flush()` si en ligne. Conséquence assumée : `pendingCount` vaut 0 pendant un tick au démarrage ; `OnlineBanner` étant réactif, il se corrige seul.

**Rejeu.** Une entrée sans `files` est rejouée exactement comme aujourd'hui (`apiClient.request({method, url, data})`). Une entrée avec `files` construit un `FormData` (part `files` répétée + `comment`/`clientCapturedAt` optionnels) et force `Content-Type: multipart/form-data`. Le `break` aveugle sur échec est **conservé volontairement** : sa correction est la Story 4.3.

## Verification

**Commands:**
- `cd frontend && npm install` -- expected: installation propre, `idb` en dependencies
- `cd frontend && npm run test` -- expected: la suite `offlineQueue.spec.js` passe, y compris l'assertion octet-par-octet après round-trip et les cas de non-régression create/event
- `cd frontend && npm run build` -- expected: build Vite sans erreur (garde-fou historique du repo)

**Manual checks (if no CLI):**
- `grep -rn "escrow_offline_queue" frontend/src` -- attendu : plus qu'une seule occurrence, dans le chemin de **migration** (`offlineQueue.idb.js`), aucune écriture de la file.

## Auto Run Result

Status: `done` — implémenté, revu (2 relecteurs adverses en parallèle), corrigé et commité.

### Changement livré

La file hors-ligne persiste désormais dans **IndexedDB** au lieu de `localStorage` et porte les `Blob` (≤ 10 Mo) d'une entrée, sans base64. Une entrée porteuse de binaire est rejouée en multipart (parts figées `files` répétée / `comment` / `clientCapturedAt`) ; les entrées sans binaire (`create`, `event`) gardent leur forme et leur rejeu JSON exacts. Les files `localStorage` existantes sont reprises au premier démarrage puis la clé est supprimée. Le repo passe de **0 à 21 tests front** : c'est aussi l'amorçage du harnais Vitest (d'où le warning `multiple-goals`, assumé — les Stories 4.2→4.5 en dépendent).

### Fichiers

- `frontend/src/stores/offlineQueue.js` -- refonte : hydratation async fusionnante, `enqueue`/`removeFromQueue` async, branche multipart, `seq` monotone.
- `frontend/src/stores/offlineQueue.idb.js` (nouveau) -- couche IndexedDB isolée + migration localStorage one-shot.
- `frontend/src/stores/escrow.js` -- les 2 sites `enqueue()` attendent la persistance ; rollback de la carte optimiste si elle échoue.
- `frontend/src/stores/__tests__/offlineQueue.spec.js` (nouveau) -- 18 tests : matrice I/O + chemins d'échec.
- `frontend/src/stores/__tests__/escrow.offline.spec.js` (nouveau) -- 3 tests : conséquences d'un `enqueue()` qui rejette.
- `frontend/vitest.config.js`, `frontend/vitest.setup.js` (nouveaux) -- harnais ; le setup contient la recette Blob/FormData (voir Design Notes).
- `frontend/package.json` -- `idb` en dependencies ; `vitest`/`jsdom`/`fake-indexeddb`/`@vue/test-utils`/`undici` en devDeps ; script `test`.

### Revue

11 patchs appliqués (1 high, 5 medium, 5 low), 0 intent_gap, 0 bad_spec, 0 defer, 5 rejets. Détail dans le Review Triage Log. Les deux relecteurs ont convergé sur une **cause racine unique** : la conversion sync→async a rendu faillibles quatre points d'appel qui ne pouvaient pas échouer avant, et aucun n'avait acquis la gestion d'erreur correspondante. Le plus grave (rejeu d'une requête déjà acceptée ⇒ transaction dupliquée) a été prouvé par mutation.

Rejets : assertion multipart contre un mock (limite inhérente à un test unitaire ; le motif est celui, déjà livré, de `api/evidence.js`) ; `resetDBForTests` exporté (tree-shaké) ; `flush()` pré-`init` sur file vide (les écouteurs ne sont posés que dans `init`) ; perte du `try/catch` de `loadQueue` (couverte par le garde de `init()`) ; réalisme du harnais (voir risques résiduels).

### Vérification

- `npm run test` -- **21/21 au vert** (2 fichiers), 1,9 s.
- `npm run build` -- succès, service worker généré.
- `grep -rn "escrow_offline_queue" frontend/src` -- 2 occurrences, toutes deux dans le chemin de migration (code + test).
- Mutation-testing du correctif high : sans le garde, 2 POST au lieu d'1 — le test n'est pas vacuant.

### Risques résiduels

1. **La preuve « octets identiques » porte sur un `Blob` Node, pas sur celui d'un navigateur.** Le harnais remplace `Blob`/`File`/`FormData` (jsdom + fake-indexeddb détruisent silencieusement un Blob jsdom). Le trio testé n'est donc celui d'aucun navigateur réel. Une validation manuelle en navigateur est recommandée **avant que la Story 4.2 ne bâtisse dessus** ; c'est le principal angle mort et la raison de `followup_review_recommended: true`.
2. **Duplication possible malgré tout** si la suppression IndexedDB échoue durablement : l'entrée est retirée de la mémoire (plus de rejeu dans la session) mais peut survivre en base et repartir après un rechargement. Une vraie garantie exige une clé d'idempotence côté serveur — hors périmètre.
3. **`main.js` n'attend pas `init()`** : `pendingCount` vaut 0 pendant un tick au démarrage (écart assumé par le spec, `OnlineBanner` étant réactif).
4. Le chemin binaire n'a **aucun producteur applicatif** tant que la Story 4.2 n'a pas câblé l'UI : il n'est exercé que par les tests. C'est le découpage voulu de l'epic, pas un oubli.
