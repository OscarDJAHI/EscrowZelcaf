---
title: 'Story 4.2 : Ouvrir un litige & déposer des preuves hors-ligne'
type: 'feature'
created: '2026-07-17'
status: 'done'
baseline_revision: '370445ffc31b920cd9053d1606689151eff08eed'
final_revision: '9feb6b7e6632189ed4d3f248f1ada6659f742a14'
review_loop_iteration: 0
followup_review_recommended: false
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** `escrow.js#openDispute` est **online-only** (docblock explicite : « never queued offline ») : hors réseau, l'acheteur qui constate un dommage à la réception ne peut pas ouvrir son litige — l'appel échoue et ses photos ne sont nulle part. La Story 4.1 a livré la file IndexedDB capable de porter les `Blob` et de les rejouer en multipart, mais **aucun producteur applicatif ne l'alimente** : le chemin binaire n'est exercé que par les tests.

**Approach:** Câbler `openDispute` sur la file : hors-ligne, l'ouverture **et ses binaires** partent en **une seule entrée** `enqueue()` (`meta.type: 'OPEN_DISPUTE'`) visant l'endpoint composite `POST /api/v1/escrow/{id}/dispute` ; l'UI bascule la transaction en `DISPUTED` de façon optimiste avec un marqueur `_queuedDispute` (miroir du `_queuedEvent` existant), et le rejeu multipart livré en 4.1 fait le reste au retour du réseau, sans modification du store `offlineQueue`.

## Boundaries & Constraints

**Always:**
- **Une seule entrée atomique** : l'ouverture et toutes ses pièces sont dans le même item de file — jamais un item « litige » plus des items « pièce ». Le rejeu réussit ou échoue en bloc (garanti par l'endpoint composite, AD-1).
- **Parts multipart figées (AD-13)** : `files` répétée, `comment`, `clientCapturedAt`. Le `buildFormData()` de `offlineQueue.js` les émet déjà — l'entrée doit donc porter `files: Blob[]` en **racine** et `{comment, clientCapturedAt}` dans `data` ; tout autre champ de `data` est silencieusement ignoré sur une entrée binaire.
- **`clientCapturedAt`** (ISO-8601, horodaté à la mise en file) est renseigné sur le chemin hors-ligne : c'est le canal prévu pour un dépôt différé (AD-11). Le back-end l'accepte en `@RequestParam(required = false)` (`EscrowController.java:65`) ; l'heure serveur reste l'autorité du tri.
- **Enqueue d'abord, optimiste ensuite** (motif de `sendTransactionEvent`, `escrow.js:98-107`) : si `enqueue()` rejette, rien n'a été affiché, donc il n'y a rien à annuler et l'erreur remonte au formulaire.
- L'API publique et le comportement du store `offlineQueue` restent **inchangés** (non-régression `CREATE_TRANSACTION` / `SEND_EVENT`).

**Block If:**
- Le contrat multipart de l'endpoint composite diverge des parts émises par `buildFormData()` (un renommage ⇒ 400 au rejeu).
- Un affichage optimiste correct exigerait un endpoint ou un champ back-end qui n'existe pas.

**Never:**
- Ne pas classer les échecs de rejeu (transitoire vs permanent), ni annuler l'optimiste sur rejet, ni notifier : c'est la **Story 4.3/4.4**. Le `break` aveugle de `flush()` reste tel quel.
- Ne pas toucher au back-end (aucune modification requise), ni à `offlineQueue.js` / `offlineQueue.idb.js` sauf besoin prouvé.
- Pas de base64, pas de compression/miniature, pas d'écran de récupération (Story 4.5).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Litige hors-ligne | `isOnline: false`, 2 `Blob` + commentaire ≥ 10 car., détail chargé sur la transaction | **1** entrée en file : `{method:'post', url:'/api/v1/escrow/{id}/dispute', files:[Blob,Blob], data:{comment, clientCapturedAt}, meta:{type:'OPEN_DISPUTE', transactionId}}` ; `pendingCount === 1` ; retour `null` | Aucune erreur attendue |
| Optimiste | idem, après enqueue réussi | `currentDetail.transaction.state === 'DISPUTED'` et `_queuedDispute === true` ; la ligne correspondante de `transactions` bascule aussi | — |
| Échec de persistance | `idb.put` rejette (quota) | L'erreur remonte à l'appelant ; **aucun** affichage optimiste (état inchangé, pas de `_queuedDispute`) ; `pendingCount === 0` | `throw` relayé, formulaire affiche le message |
| Rejeu au retour réseau | 1 entrée `OPEN_DISPUTE` en file, `flush()` en ligne | **1 seul** POST `multipart/form-data` sur `/api/v1/escrow/{id}/dispute`, 2 parts `files` octet-pour-octet identiques + `comment` + `clientCapturedAt` ; file et IndexedDB vidées ; `escrow:sync` émis | Sur échec : entrée + binaires conservés (comportement 4.1) |
| Litige en ligne | `isOnline: true` | Chemin actuel inchangé : POST direct, `dto.transaction` remplace l'état, retour du `dto` | Inchangé |
| Rechargement PWA | entrée `OPEN_DISPUTE` en file, PWA rechargée hors-ligne | L'entrée est réhydratée depuis IndexedDB avec ses `Blob` intacts et reste rejouable | — |

</intent-contract>

## Code Map

- `frontend/src/stores/escrow.js:121-142` -- **cœur de la story** : `openDispute()` gagne sa branche hors-ligne. Motifs à reprendre : `sendTransactionEvent` (`:94-119`, enqueue puis marqueur, retour `null`) et le compare `String(id)` du chemin en ligne (`:135-139`).
- `frontend/src/stores/offlineQueue.js:24-33,119-138,146-199` -- `buildFormData()` (parts figées), signature `enqueue({method,url,data,files,meta})`, branche multipart de `flush()`. **À consommer, pas à modifier.**
- `frontend/src/views/TransactionDetailView.vue:88-95,144-150,179-185` -- `onDisputeOpened()` recharge inconditionnellement (hors-ligne ⇒ `escrowStore.error` masque la transaction) ; le bandeau `_queuedEvent` (`:145`) est le modèle exact du bandeau `_queuedDispute`. `offlineQueue` est déjà importé et utilisé en `:78`.
- `frontend/src/components/OpenDisputeForm.vue:43-59` -- `handleSubmit()` : hors-ligne `openDispute` renvoie `null` sans lever, donc `emit('opened')` fonctionne déjà ; seul un indice « hors-ligne » est à ajouter.
- `frontend/src/utils/stateMachine.js:111-118` -- `canOpenDispute()` lit `transaction.state` : l'optimiste `DISPUTED` referme le formulaire tout seul.
- `frontend/src/stores/__tests__/escrow.offline.spec.js` -- conventions de test (mock `@/api/client` + `@/api/escrow`, `openDispute: vi.fn()` déjà mocké `:14`, `isOnline = false` par assignation directe).
- `frontend/src/stores/__tests__/offlineQueue.spec.js:15-40` -- helpers `makeBlob()` / `expectSameBytes()` (comparaison `Buffer.compare`, jamais `toEqual` sur des Mo) à réutiliser.
- `backend/src/main/java/com/zlecaf/escrow/web/EscrowController.java:59-67` -- contrat cible du rejeu (référence, **aucune modification**).

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/stores/escrow.js` -- ajouter la branche `if (!offlineQueue.isOnline)` en tête de `openDispute()` : `enqueue()` de l'entrée unique (`files` en racine, `data:{comment, clientCapturedAt: new Date().toISOString()}`, `meta:{type:'OPEN_DISPUTE', transactionId: id}`), **puis** marquage optimiste `state:'DISPUTED'` + `_queuedDispute: true` sur `currentDetail.transaction` et sur la ligne de `transactions` (compare `String(id)`), retour `null`. Corriger le docblock « Online only ». -- c'est le seul point où la file peut être alimentée en binaire.
- [x] `frontend/src/views/TransactionDetailView.vue` -- guarder `onDisputeOpened()` par `offlineQueue.isOnline` avant `load()`/`loadEvidence()`, et ajouter un bandeau `v-if="transaction._queuedDispute"` calqué sur celui de `_queuedEvent` -- sans le garde, un rechargement hors-ligne remplace la transaction par une erreur ; sans le bandeau, l'utilisateur ne sait pas que son litige est en attente.
- [x] `frontend/src/components/OpenDisputeForm.vue` -- afficher un indice hors-ligne (`useOfflineQueueStore().isOnline === false`) indiquant que le litige et ses pièces partiront à la reconnexion -- l'utilisateur doit comprendre qu'il n'est pas bloqué avant de soumettre.
- [x] `frontend/src/stores/__tests__/escrow.offline.spec.js` -- couvrir chaque ligne de la matrice I/O : entrée unique et sa forme, optimiste `DISPUTED`/`_queuedDispute`, échec `idb.put` sans optimiste, **rejeu de bout en bout** (`isOnline = true` + `flush()` ⇒ 1 POST multipart, parts vérifiées octet-pour-octet), non-régression du chemin en ligne -- le rejeu est la preuve que l'entrée produite ici est réellement consommable par 4.1.

**Acceptance Criteria:**
- Given une transaction `FUNDS_LOCKED` affichée hors-ligne, when j'ouvre un litige avec 2 pièces et un commentaire, then le formulaire se referme sans erreur et le détail affiche `DISPUTED` avec le bandeau « en attente de synchronisation ».
- Given cette entrée en file, when la PWA est rechargée hors-ligne puis revient en ligne, then l'entrée est rejouée en un seul POST multipart et le détail se recharge sur l'`escrow:sync` déjà émis par `flush()`.
- Given le store `offlineQueue`, when la story est livrée, then ni `offlineQueue.js` ni `offlineQueue.idb.js` n'ont changé de comportement et les 21 tests préexistants passent toujours.
- Given `npm run test` dans `frontend/`, when la suite s'exécute, then elle est verte, tests de la story inclus.

## Spec Change Log

## Review Triage Log

### 2026-07-17 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 6: (high 0, medium 3, low 3)
- defer: 5: (high 0, medium 4, low 1)
- reject: 3: (high 0, medium 1, low 2)
- addressed_findings:
  - `[medium]` `[patch]` `_queuedDispute` était écrit sur la ligne de `transactions` mais **lu nulle part** : `TransactionCard.vue` ne teste que `_queuedOffline`. Le tableau de bord affichait donc un badge `DISPUTED` franc pour un litige que le serveur n'a jamais vu — exactement le mensonge que le bandeau du détail existe pour éviter. Pastille « Pending sync » ajoutée à côté du badge.
  - `[medium]` `[patch]` `openDispute(id, {files: []})` hors-ligne mettait en file une entrée sans binaire : `flush()` dispatchant sur `files.length`, elle serait rejouée en **JSON** vers un endpoint multipart-only ⇒ 400 définitif en tête de file, bloquant toutes les entrées derrière (le `break` aveugle est 4.3). Garde ajoutée en tête de branche ; l'action du store est la frontière publique, le formulaire n'est pas la garde.
  - `[medium]` `[patch]` La coercition `String(id)` ajoutée par la story n'était **pinnée par aucun test** : tous passaient `7` (nombre), alors qu'en production `TransactionDetailView` déclare `id: {type: String}` et que l'API renvoie des ids numériques — la seule asymétrie qui justifie la coercition. Test d'ids mixtes ajouté, **prouvé non-vacuant par mutation** (sans `String()`, lui seul tombe).
  - `[low]` `[patch]` Le commentaire de `clientCapturedAt` affirmait « the only record of when the user actually captured it » : c'est l'heure de **soumission**, pas de capture — et `EvidenceDeposit.vue:51-53` avait justement refusé d'envoyer une heure d'upload comme métadonnée d'audit trompeuse. La valeur reste (mandatée par AD-11), le commentaire dit désormais la vérité.
  - `[low]` `[patch]` Rien ne documentait pourquoi `openDispute` réécrit `state` là où `sendTransactionEvent`, 40 lignes plus haut, s'y refuse délibérément. Docblock complété : la bascule est ce qui empêche un second litige (`canOpenDispute` lit `state`).
  - `[low]` `[patch]` Aucune assertion sur l'`escrow:sync` du rejeu d'un litige, alors que c'est lui qui purge `_queuedDispute` via le refetch des vues. Assertion ajoutée au test de rejeu — assurance contre un refactor 4.3 qui l'oublierait.

## Design Notes

**Pourquoi enqueue avant l'optimiste.** `createNewTransaction` (`escrow.js:37-75`) affiche d'abord puis annule par `id` si `enqueue()` rejette — inévitable, car il invente l'`id` local. Ici la transaction existe déjà : mettre en file d'abord supprime tout besoin de rollback (motif de `sendTransactionEvent`). Un `enqueue()` qui rejette ne doit laisser **aucune** trace optimiste : rien ne sera jamais synchronisé.

**Forme exacte de l'entrée** (les parts sont figées, cf. `buildFormData` `offlineQueue.js:24-33` — `files` doit être en **racine**, pas dans `data`) :

```js
await offlineQueue.enqueue({
  method: 'post',
  url: `/api/v1/escrow/${id}/dispute`,
  files: [...files],                                   // parts `files` répétées
  data: { comment, clientCapturedAt: new Date().toISOString() },
  meta: { type: 'OPEN_DISPUTE', transactionId: id },   // `meta` ne part jamais au serveur
})
```

**Piège du harnais (hérité de 4.1, non négociable).** `vitest.setup.js` remplace `Blob`/`File`/`FormData` par ceux de Node/undici : sous jsdom, `fake-indexeddb` réduit un `Blob` jsdom à `{}` **sans lever d'erreur**. Toute assertion binaire passe donc par `expectSameBytes()` (`Buffer.compare`), jamais par `toEqual`/`toBeInstanceOf` après un aller-retour `FormData`.

## Verification

**Commands:**
- `cd frontend && npm run test` -- expected: suite verte, ≥ 21 tests préexistants + ceux de la story ; le test de rejeu prouve 1 seul POST multipart avec parts `files` identiques octet-pour-octet.
- `cd frontend && npm run build` -- expected: build Vite sans erreur (garde-fou historique du repo).

**Manual checks (if no CLI):**
- `git diff --stat frontend/src/stores/offlineQueue.js frontend/src/stores/offlineQueue.idb.js` -- attendu : **vide** (la story consomme la file, elle ne la refond pas).

## Auto Run Result

Status: `done` — implémenté, revu (2 relecteurs adverses en parallèle), corrigé et commité.

### Changement livré

`openDispute()` cesse d'être « online only » : hors réseau, l'ouverture **et ses binaires** partent en **une seule entrée** de file (`meta.type: 'OPEN_DISPUTE'`) visant l'endpoint composite, avec `clientCapturedAt` horodaté à la soumission ; la transaction bascule en `DISPUTED` de façon optimiste (marqueur `_queuedDispute`, bandeau au détail, pastille « Pending sync » au tableau de bord) et le rejeu multipart livré par la Story 4.1 fait le reste au retour du réseau. Le chemin binaire de la file, jusqu'ici exercé par les seuls tests, a enfin son producteur applicatif. Ni `offlineQueue.js` ni `offlineQueue.idb.js` n'ont été touchés, ni le back-end.

### Fichiers

- `frontend/src/stores/escrow.js` -- branche hors-ligne de `openDispute()` : entrée unique (`files` en racine, `data:{comment, clientCapturedAt}`), garde « au moins un fichier », marquage optimiste sur le détail et la ligne du tableau de bord, retour `null`.
- `frontend/src/views/TransactionDetailView.vue` -- `onDisputeOpened()` gardé par `isOnline` (sans quoi le rechargement hors-ligne remplace le détail optimiste par une erreur) ; bandeau `_queuedDispute`.
- `frontend/src/components/OpenDisputeForm.vue` -- indice hors-ligne avant soumission : l'utilisateur voit qu'il n'est pas bloqué.
- `frontend/src/components/TransactionCard.vue` -- pastille « Pending sync » (correctif de revue : le marqueur était écrit mais lu nulle part).
- `frontend/src/stores/__tests__/escrow.offline.spec.js` -- 8 tests ajoutés (21 → 29 au total) : forme de l'entrée, optimiste, ids mixtes, garde sans fichier, échec de persistance, rejeu multipart bout-en-bout, réhydratation, non-régression du chemin en ligne.

### Revue

6 patchs appliqués (3 medium, 3 low), 0 intent_gap, 0 bad_spec, 5 defer, 3 rejets. Détail dans le Review Triage Log. Les deux relecteurs ont convergé sur le même angle mort : la story **écrivait** un marqueur et une coercition que **rien ne lisait ni ne prouvait** — `_queuedDispute` ignoré par `TransactionCard`, `String(id)` non pinné par des tests qui ne passaient que des nombres là où la route passe une chaîne. Rejets : la disparition du bouton « Mark as shipped » après une bascule optimiste (fidèle à ce que ferait le serveur) ; le cas `currentDetail` null (le formulaire n'est monté que depuis le détail) ; le libellé d'erreur d'un quota dépassé (cosmétique).

### Vérification

- `npm run test` -- **29/29 au vert** (2 fichiers), 2,0 s.
- `npm run build` -- succès, service worker généré (12 entrées de precache).
- `git diff --stat` sur `offlineQueue.js` / `offlineQueue.idb.js` -- **vide**, comme exigé.
- Mutation-testing du test d'ids mixtes : en retirant `String()`, lui seul tombe — il n'est pas vacuant.

### Risques résiduels

1. **L'affichage optimiste est scopé à la session** : un rechargement hors-ligne du détail montre un panneau d'erreur, sans trace du litige en attente. L'entrée et ses binaires restent en base et rejouables (prouvé au niveau du store), mais l'utilisateur ne les voit plus — c'est le terrain de l'écran de récupération (Story 4.5). Déféré.
2. **Rien ne classe encore les échecs de rejeu** : un rejet définitif (transaction déjà résolue, plus de 20 fichiers) reste en tête de file et bloque les entrées derrière, par le `break` aveugle conservé volontairement. C'est le cœur de la Story 4.3 et la suite immédiate.
3. **Un POST parti en ligne qui meurt en vol n'est pas mis en file** (`navigator.onLine` est le seul déclencheur) : c'est désormais des photos qui se perdent, pas un événement JSON. Déféré — un repli exige d'arbitrer le risque de double soumission.
4. **Les trois comportements UI n'ont aucun test de composant**, bien que `@vue/test-utils` soit installé. Déféré.
