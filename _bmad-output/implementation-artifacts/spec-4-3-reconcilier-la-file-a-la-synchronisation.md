---
title: 'Story 4.3 : Réconcilier la file à la synchronisation'
type: 'feature'
created: '2026-07-17'
status: 'done'
baseline_revision: 'da291d9'
final_revision: 'bcd59ed2b9ed7b46b5d6d6d65f8e21e103495666'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings: [oversized]
---

<intent-contract>

## Intent

**Problem:** `flush()` (`offlineQueue.js:173-174`) fait un **`break` aveugle** au premier échec : il ne lit rien du body de la réponse et ne distingue pas une coupure réseau d'un rejet définitif du serveur. Un litige rejoué sur une transaction déjà résolue par l'autre partie échouera donc **à chaque reconnexion, indéfiniment**, en tête de file — bloquant toutes les entrées derrière lui — pendant que l'UI continue d'afficher un `DISPUTED` optimiste que le serveur n'a jamais accepté. L'utilisateur voit un mensonge et ne peut rien y faire.

**Approach:** Classer chaque échec de rejeu par le champ `code` de l'enveloppe d'erreur — livré par la Story 5.3, désormais consommable. **Transitoire** (réseau, `5xx`, `408`/`429`, `401`, `CONCURRENT_MODIFICATION`, `FILE_READ_ERROR`, `STORAGE_UNAVAILABLE`) : `break`, l'entrée reste intacte et sera re-tentée. **Permanent** (tout le reste) : **geler** l'entrée — `frozen: true` + motif persisté, **binaire conservé**, plus jamais re-tentée automatiquement — puis `continue` pour libérer la file, et émettre `escrow:sync` afin que le refetch déjà câblé écrase l'affichage optimiste par la vérité serveur.

## Boundaries & Constraints

**Always:**
- **Rien n'est jamais supprimé sur un échec.** Une entrée gelée conserve son `id`, ses `Blob` et ses `data` en IndexedDB. La seule suppression reste celle qui suit un succès HTTP (`flush():180-190`, inchangée). L'acquittement explicite et la récupération sont la Story 4.5.
- **Le verdict vient du `code`, jamais du texte du `message`** (interpolé, non verrouillé par test) ni de la seule classe HTTP (`400` et `409` portent chacun des verdicts opposés).
- **Un `code` absent n'autorise aucune supposition.** Les chemins hors `@RestControllerAdvice` (401 Spring Security, 404 de route, `/error` par défaut) ne portent pas de `code` : la politique est décidée par le statut **avant** de lire le code (cf. ordre des règles en Design Notes).
- **Le gel doit borner l'auto-retry, pas la file** : une entrée gelée est **sautée** par `flush()`, elle ne bloque jamais les entrées suivantes.
- L'entrée gelée est persistée par `idb.put()` (upsert sur `keyPath: 'id'`) **avant** que `flush()` ne poursuive : un rechargement ne doit pas ressusciter l'auto-retry d'un rejet définitif.
- Non-régression : `enqueue()`, `buildFormData()`, le schéma IndexedDB (`DB_VERSION` inchangée), le chemin de succès et les 29 tests existants restent intacts.

**Block If:**
- Un code applicatif nécessaire au verdict manque à l'énumération backend (`ErrorCode.java`) — c'est un conflit à remonter, jamais un prétexte à retomber sur la classe HTTP (AD-10).
- Annuler l'affichage optimiste exigerait un endpoint ou un champ backend inexistant.

**Never:**
- **Pas d'UI de notification** (message, bandeau dédié, motif affiché, compteur de gelées à l'écran) : c'est la **Story 4.4**. Cette story produit le verdict et l'expose au store ; elle ne le raconte pas à l'utilisateur.
- Pas d'écran de récupération ni d'acquittement (**Story 4.5**), pas de re-tentative manuelle d'une entrée gelée.
- Pas de compteur de tentatives, pas de backoff, pas d'auto-retry programmé : aucun besoin prouvé, et `flush()` reste déclenché par le seul listener `online`.
- Aucune modification backend (le contrat de codes est livré et testé), pas de bump de `DB_VERSION`, pas de `meta.previousState` (le refetch est déjà l'autorité — cf. Design Notes).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Rejet permanent codé | 1 entrée `OPEN_DISPUTE` (2 `Blob`), rejeu → `409 {code:'DISPUTE_ALREADY_RESOLVED'}` | Entrée **conservée**, `frozen === true`, `failure === {code:'DISPUTE_ALREADY_RESOLVED', status:409, message, at}` ; **binaires intacts octet-pour-octet** après relecture IndexedDB ; `pendingCount === 0`, `frozenCount === 1` ; `escrow:sync` émis | Aucune perte ; `console.error` attendu |
| Gel persistant | idem, puis nouveau `flush()` (et après réhydratation) | **Aucun** appel HTTP supplémentaire pour cette entrée : l'auto-retry est gelé, y compris après rechargement | — |
| Échec transitoire codé | rejeu → `409 {code:'CONCURRENT_MODIFICATION'}` | Entrée **non gelée**, intacte, re-tentée au `flush()` suivant ; `pendingCount === 1`, `frozenCount === 0` ; pas d'`escrow:sync` | Comportement actuel préservé |
| Panne réseau | rejeu rejeté sans `response` (`new Error('network down')`) | Transitoire : entrée conservée, non gelée, `break` — non-régression des 2 tests existants | — |
| Transitoire sous 400 | rejeu → `400 {code:'FILE_READ_ERROR'}` | Transitoire malgré le `4xx` : non gelée (preuve que le statut ne décide pas) | — |
| Session expirée | rejeu → `401` (sans `code`, Spring Security) | Transitoire : **jamais gelé** — après ré-authentification le rejeu est valide | L'intercepteur (`client.js:27-32`) redirige vers `/auth` ; la file survit |
| 4xx sans `code` | rejeu → `404` de route ou `/error` sans enveloppe | Permanent : gelé avec `failure.code === null` | Aucune supposition, aucun crash |
| La gelée ne bloque pas | file = [entrée gelée par un rejet permanent, entrée `SEND_EVENT` valide] | La 2ᵉ entrée est rejouée et **supprimée** ; la gelée reste ; `pendingCount === 0`, `frozenCount === 1` | — |
| Annulation de l'optimiste | transaction affichée `DISPUTED` + `_queuedDispute`, rejeu → rejet permanent, serveur en `RELEASED` | `escrow:sync` déclenche le refetch déjà câblé : la transaction revient à `RELEASED`, le marqueur disparaît avec l'objet remplacé | — |
| Succès | rejeu accepté | Chemin actuel **inchangé** : entrée supprimée, `escrow:sync` émis | Inchangé |

</intent-contract>

## Code Map

- `frontend/src/utils/replayFailure.js` -- **à créer** : `classifyReplayFailure(err)` (fonction pure → `'transient' | 'permanent'`) + `TRANSIENT_CODES`. Isolée pour être testable sans store et réutilisable par 4.4. Voisin de convention : `frontend/src/utils/stateMachine.js`.
- `frontend/src/stores/offlineQueue.js:146-199` -- **cœur de la story** : `catch (err)` + `break` (`:173-174`) → classification, gel, `continue`. `pending = [...this.queue]` (`:150`) doit exclure les gelées. Le second `try/catch` (`:180-190`, suppression post-succès) et `buildFormData()` (`:24-33`) restent **intacts**. `escrow:sync` (`:196-198`) : condition à étendre. Getter `pendingCount` (`:58`).
- `frontend/src/stores/offlineQueue.idb.js:13-14,34-48` -- `put(item)` est un **upsert** documenté sur `keyPath: 'id'` : persister une entrée gelée ne demande ni index ni bump de `DB_VERSION` (`:3-5`).
- `frontend/src/api/client.js:24-36` -- l'intercepteur relaie l'`AxiosError` intact (`err.response.data` = enveloppe ; `err.response === undefined` = panne réseau). **Piège** : `:27-32` redirige durement vers `/auth` sur `401` — d'où le classement transitoire du `401`.
- `backend/src/main/java/com/zlecaf/escrow/domain/ErrorCode.java:85,92,95` -- source de vérité : les **3 seuls** codes `TRANSIENT` (`CONCURRENT_MODIFICATION`, `FILE_READ_ERROR`, `STORAGE_UNAVAILABLE`) ; les 18 autres sont `PERMANENT`. La retryabilité **n'est pas sérialisée** — le front miroite. `web/GlobalExceptionHandler.java:35-43` : `code` est une string plate (`code.name()`).
- `backend/src/test/java/com/zlecaf/escrow/domain/ErrorCodeContractTest.java:62` -- `transientPartitionIsExact` verrouille la partition par **égalité exacte** : c'est l'alarme qui préviendra une dérive front/back (cf. Design Notes).
- `frontend/src/stores/escrow.js:164-173` -- marqueurs optimistes `_queuedDispute` + bascule `state`, **jamais** effacés explicitement : seul le refetch (objet serveur qui remplace l'objet local) les efface. `loadTransactions()` (`:23`) **réassigne** `this.transactions` — d'où la disparition d'une carte `local-…`.
- `frontend/src/views/TransactionDetailView.vue:101-113`, `frontend/src/views/DashboardView.vue:16-26` -- les deux écouteurs d'`escrow:sync` qui refetchent : le mécanisme d'annulation existe déjà, la story se contente de le déclencher.
- `frontend/src/stores/__tests__/offlineQueue.spec.js:10-12,42-50,165,223` -- conventions : mock `@/api/client`, `store.isOnline = true/false` par assignation, `mockRejectedValueOnce(new Error(...))` (les 2 seuls échecs simulés, tous deux **sans** `response`), helpers `makeBlob()`/`expectSameBytes()`, `vi.spyOn(console, 'error')` obligatoire.
- `frontend/src/components/OnlineBanner.vue:7-9` -- lit `pendingCount` : `visible = !isOnline || pendingCount > 0`. Consommateur direct du changement de sémantique du getter.

## Tasks & Acceptance

**Execution:**
- [x] `frontend/src/utils/replayFailure.js` -- créer `TRANSIENT_CODES` (miroir des 3 codes `TRANSIENT` d'`ErrorCode.java`, avec commentaire pointant le fichier faisant foi) et `classifyReplayFailure(err)` appliquant les règles **dans l'ordre** de la Design Note (réseau → `401` → `408`/`429`/`5xx` → `code` transitoire → tout le reste permanent) ; exporter aussi le motif extrait (`{code, status, message}`) pour que 4.4 n'ait pas à re-parser -- fonction pure : c'est le seul endroit où le verdict se décide, et il doit être testable sans IndexedDB ni Pinia.
- [x] `frontend/src/stores/offlineQueue.js` -- remplacer le `break` aveugle (`:173-174`) : classer via `classifyReplayFailure(err)` ; **transitoire** → `break` (FIFO préservé, réseau mort non martelé) ; **permanent** → `item.frozen = true`, `item.failure = {code, status, message, at: ISO}`, `await idb.put(item)` (échec de persistance : garder le gel en mémoire et poursuivre, ne jamais le convertir en échec de sync), `continue`. Exclure les gelées de `pending` (`:150`). Getters : `pendingCount` = non gelées, + `frozenCount` / `frozenEntries`. Émettre `escrow:sync` si `syncedAny || reconciledAny` -- c'est ce dispatch qui annule l'optimiste via les refetch déjà câblés.
- [x] `frontend/src/utils/__tests__/replayFailure.spec.js` -- créer : couvrir chaque règle et son ordre — réseau (pas de `response`), `401` sans code, `429`/`503`, `409 CONCURRENT_MODIFICATION`, `400 FILE_READ_ERROR`, `502 STORAGE_UNAVAILABLE`, `409 DISPUTE_ALREADY_RESOLVED`, `400 EVIDENCE_INVALID`, `404` sans code, code inconnu sous 4xx -- verrouille la partition dont dépendent 4.4 et 4.5.
- [x] `frontend/src/stores/__tests__/offlineQueue.spec.js` -- couvrir chaque ligne de la matrice I/O au niveau du store : gel avec **binaires vérifiés octet-pour-octet après relecture IndexedDB** (`expectSameBytes`), absence de re-tentative après un second `flush()` **et après réhydratation**, non-blocage de l'entrée suivante, `escrow:sync` émis sur rejet permanent sans aucun succès, non-régression des 2 échecs réseau existants -- le test du binaire conservé est la matérialisation de « aucune perte silencieuse » (preuve exigée par AD-10) ; le mocking d'une réponse HTTP d'erreur (`{response:{status,data:{code}}}`) est un gabarit inédit dans ce repo.

**Acceptance Criteria:**
- Given une entrée gelée par un rejet permanent, when la PWA est rechargée puis revient en ligne, then aucun appel HTTP n'est émis pour elle, son binaire est toujours relisible octet-pour-octet, et son motif (`failure.code`) est intact — ce que 4.4 notifiera et 4.5 récupérera.
- Given la partition de `replayFailure.js`, when `ErrorCode.java` gagne un code `TRANSIENT`, then `ErrorCodeContractTest.transientPartitionIsExact` échoue côté backend : la dérive front/back est bruyante, jamais silencieuse.
- Given `cd frontend && npm run test`, when la suite s'exécute, then elle est verte, les **29 tests préexistants inclus**, avec aucun changement de comportement de `enqueue()`, `buildFormData()` ni du chemin de succès.
- Given `git diff backend/`, when la story est livrée, then il est **vide** : le contrat de codes est consommé, pas re-livré.

## Spec Change Log

## Review Triage Log

### 2026-07-17 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 1, medium 1, low 2)
- defer: 3: (high 0, medium 3, low 0)
- reject: 8: (high 0, medium 3, low 5)
- addressed_findings:
  - `[high]` `[patch]` **La règle « session expirée » visait le mauvais statut.** La matrice I/O supposait qu'un JWT expiré rend `401` ; ce backend rend **`403`**. `SecurityConfig.java:35-49` ne configure ni `httpBasic`, ni `formLogin`, ni `AuthenticationEntryPoint` : le token expiré est avalé par `JwtAuthFilter.java:54-57`, la requête échoue anonyme sur `anyRequest().authenticated()` et sort par le `Http403ForbiddenEntryPoint` par défaut de Spring (confirmé par le commentaire de `PartnerSecurityMatcherTest.java:72`). Ce `403` nu tombait donc dans le défaut `permanent` : **un litige hors-ligne et ses photos étaient gelés définitivement sur une simple expiration de session** — précisément la perte que la story existe pour empêcher. Le correctif suggéré par le relecteur (`status === 401 || status === 403 → transient`) a été **écarté comme faux** : il classerait `NOT_A_PARTY` et `UNAUTHORIZED_TRANSITION` (403 **codés**, verdicts réels) comme retryables. C'est l'**absence d'enveloppe** qui identifie une expiration, jamais le statut : la règle est donc `code === null && (401 || 403)`, placée après les règles de statut et avant la table des codes. Le `401` est conservé pour le jour où un vrai entry point serait configuré. Deux tests ajoutés (403 nu transitoire, 403 codé permanent), **prouvés non-vacuants par mutation**.
  - `[medium]` `[patch]` **Le test « miroir » était une tautologie.** Il comparait `TRANSIENT_CODES` à un littéral écrit dans le même fichier : aucune dérive backend ne pouvait le casser. L'AC « la dérive front/back est bruyante, jamais silencieuse » reposait donc sur un **commentaire** — `ErrorCodeContractTest.transientPartitionIsExact` compare l'enum Java à un littéral Java et ignore l'existence du front : un dev backend ajoutant un code `TRANSIENT` aurait corrigé le littéral Java et laissé le front geler en silence des entrées rejouables. Le test lit désormais **réellement** `ErrorCode.java` et compare sa partition `TRANSIENT` au `Set` JS. Prouvé non-vacuant par mutation. (Ancré sur le CWD Vitest : sous jsdom `import.meta.url` n'est pas une URL `file:`.)
  - `[low]` `[patch]` `extractFailureReason` **devinait le message là où le code s'y refuse** : le repli `err.message` stocke `"Request failed with status code 404"` — chaîne interne d'axios, en anglais, verrouillée par aucun contrat — que la Story 4.4 est censée montrer à un utilisateur francophone. Aligné sur la doctrine du `code` : `message: null` en l'absence d'enveloppe (sans perte : un échec sans réponse est transitoire et ne gèle jamais).
  - `[low]` `[patch]` Le garde de `flush()` testait `queue.length === 0` : une file ne contenant que des gelées — jamais purgées — entrait dans le corps de la fonction et payait un tour à vide (bascule `flushing`) à chaque événement `online` et chaque démarrage, indéfiniment. Remplacé par `pendingCount === 0`.

## Design Notes

**L'ordre des règles est le contrat** (le statut décide *avant* le code, car un `code` peut être absent) :

```js
export function classifyReplayFailure(err) {
  const res = err?.response
  if (!res) return 'transient'                      // panne réseau/timeout : aucune réponse
  const { status } = res
  if (status === 401) return 'transient'            // session expirée : valide après ré-auth, jamais un rejet métier
  if (status === 408 || status === 429 || status >= 500) return 'transient'
  const code = res.data?.code ?? null               // hors @RestControllerAdvice : pas d'enveloppe (401 Security, /error)
  if (TRANSIENT_CODES.has(code)) return 'transient' // CONCURRENT_MODIFICATION (409), FILE_READ_ERROR (400)
  return 'permanent'                                // 4xx codé, code inconnu, ou 4xx nu : un rejet client ne se répare pas en re-tentant
}
```

**Pourquoi miroiter la liste TRANSIENT et non les 21 codes.** Le backend ne sérialise pas la retryabilité (choix documenté, `ErrorCode.java:18-23`) : le front doit trancher seul. N'énumérer que les **3** codes transitoires — tout le reste étant permanent par défaut — réduit la surface de dérive au strict minimum : un nouveau code *permanent* backend est classé correctement sans toucher au front, et le seul cas dangereux (un nouveau code *transitoire*) fait échouer `ErrorCodeContractTest.transientPartitionIsExact`, dont l'assertion est une **égalité exacte**. La dérive devient un test rouge, pas une entrée gelée à tort.

**Pourquoi `break` sur transitoire mais `continue` sur permanent.** Le `break` transitoire préserve le FIFO et évite de marteler un réseau mort — c'est le comportement actuel, et il est juste. Le `continue` permanent est ce que la story ajoute : une entrée définitivement rejetée n'est plus une file d'attente, c'est un bouchon. La sauter est la seule façon de tenir « le gel borne l'auto-retry, pas la file ».

**Pourquoi pas de `meta.previousState`.** `flush()` ne s'exécute **qu'en ligne** : au moment où un verdict permanent tombe, le serveur est joignable et fait autorité. Émettre `escrow:sync` déclenche les refetch déjà câblés (`DashboardView:16-26`, `TransactionDetailView:101-113`), qui **remplacent** l'objet local — emportant avec lui `state` optimiste et `_queuedDispute`. Mémoriser l'état d'origine reviendrait à restaurer une valeur périmée là où la vérité est disponible. Corollaire assumé : une carte `local-…` (`CREATE_TRANSACTION` gelé) disparaît du tableau de bord au refetch — son entrée et ses données restent en base, et l'exposer est précisément l'objet de la Story 4.5.

**Piège du harnais (hérité de 4.1/4.2, non négociable).** `vitest.setup.js` substitue les `Blob`/`FormData` de Node/undici : sous jsdom, `fake-indexeddb` réduit un `Blob` jsdom à `{}` **sans lever**. Toute assertion binaire passe par `expectSameBytes()` (`Buffer.compare`), jamais par `toEqual`/`toBeInstanceOf` — un test de « binaire conservé » écrit avec `toEqual` serait **vacant**, donc pire qu'absent.

## Verification

**Commands:**
- `cd /Users/Oscard/Projects/Escrow_claude/frontend && npm run test` -- expected: suite verte ; ≥ 29 tests préexistants + ceux de la story ; le test de gel prouve binaire intact après relecture IndexedDB et zéro appel HTTP au flush suivant.
- `cd /Users/Oscard/Projects/Escrow_claude/frontend && npm run build` -- expected: build Vite sans erreur (garde-fou historique du repo).

**Manual checks (if no CLI):**
- `git diff --stat backend/` -- attendu : **vide**.
- `git diff frontend/src/stores/offlineQueue.idb.js` -- attendu : **vide** (`put` est déjà l'upsert nécessaire ; aucun changement de schéma).

## Auto Run Result

Status: `done` — implémenté, revu (2 relecteurs adverses en parallèle), corrigé et commité.

### Changement livré

`flush()` cesse de faire un `break` aveugle : chaque échec de rejeu est désormais classé par le champ `code` de l'enveloppe d'erreur livrée par la Story 5.3. Un échec **transitoire** (réseau, `5xx`, `408`/`429`, session expirée, `CONCURRENT_MODIFICATION`, `FILE_READ_ERROR`, `STORAGE_UNAVAILABLE`) arrête le tour et conserve la file intacte — comportement actuel préservé. Un rejet **permanent** **gèle** l'entrée (`frozen: true` + motif persisté en IndexedDB, **binaires conservés**), la fait sauter par les `flush()` suivants — y compris après rechargement — et laisse passer les entrées derrière elle : le bouchon disparaît sans que rien ne soit perdu. L'émission d'`escrow:sync` est étendue aux réconciliations sans succès, ce qui suffit à faire annuler l'affichage optimiste par les refetch déjà câblés. Le contrat de codes backend est consommé, pas re-livré : `git diff backend/` est vide.

### Fichiers

- `frontend/src/utils/replayFailure.js` -- **créé** : `TRANSIENT_CODES` (miroir des 3 codes `TRANSIENT` d'`ErrorCode.java`), `classifyReplayFailure(err)` (fonction pure, l'ordre des règles est le contrat : réseau → `408`/`429`/`5xx` → session expirée sans enveloppe → table des codes → permanent par défaut) et `extractFailureReason(err)` pour que 4.4 n'ait pas à re-parser l'`AxiosError`.
- `frontend/src/stores/offlineQueue.js` -- `break` aveugle remplacé par la classification ; gel persisté (`idb.put`) puis `continue` ; `pending` et le garde d'entrée excluent les gelées ; getters `frozenCount`/`frozenEntries` ; `escrow:sync` émis sur `syncedAny || reconciledAny`.
- `frontend/src/utils/__tests__/replayFailure.spec.js` -- **créé**, 15 tests : chaque règle et son ordre, les deux paires `409`/`400` à verdicts opposés, le 403 nu vs codé, et la garde anti-dérive qui lit réellement `ErrorCode.java`.
- `frontend/src/stores/__tests__/offlineQueue.spec.js` -- 9 tests couvrant chaque ligne de la matrice I/O au niveau du store.

### Revue

4 patchs appliqués (1 high, 1 medium, 2 low), 0 intent_gap, 0 bad_spec, 3 reports, 8 rejets. Détail dans le Review Triage Log. Le patch high est une **erreur de fait de la spec** : elle supposait qu'une session expirée rend `401` alors que ce backend rend `403` (aucun `AuthenticationEntryPoint` configuré → `Http403ForbiddenEntryPoint`), si bien qu'une expiration gelait définitivement un litige et ses photos. Le correctif proposé par le relecteur était lui-même faux (il aurait rendu `NOT_A_PARTY` retryable) : c'est l'absence d'enveloppe, jamais le statut, qui identifie une expiration. Le patch medium a transformé une garde qui n'était qu'un commentaire en garde réelle. Rejets notables, tous trois vérifiés avant d'être écartés : la « perte de l'ordre causal » du `continue` (une entrée n'est gelée que si le serveur la refuse **définitivement** — l'ancien `break` n'aurait jamais fait passer le prédécesseur, il aurait bouché la file pour toujours ; chaque entrée reste validée indépendamment par la state machine serveur, qui est l'autorité) ; l'accumulation non bornée de binaires gelés (c'est le prix assumé de l'invariant « rien n'est jamais supprimé » de l'epic — l'acquittement est la Story 4.5) ; la duplication du helper `httpError` entre deux fichiers de test (conforme à la convention du repo, où `makeBlob`/`expectSameBytes` sont eux aussi locaux).

### Vérification

- `npm run test` -- **52/52 au vert** (3 fichiers), 2,1 s, dont les 29 tests préexistants intacts.
- `npm run build` -- succès, service worker généré (12 entrées de precache).
- `git diff --stat backend/` et `git diff frontend/src/stores/offlineQueue.idb.js` -- **vides**, comme exigé.
- Mutation-testing des deux gardes critiques : retirer un code du `Set` fait tomber le test miroir ; retirer le `403` de la règle de session fait tomber son test — aucun des deux n'est vacuant. Les tests de gel relisent réellement l'entrée depuis IndexedDB (`expectSameBytes`) et comptent les appels HTTP après réhydratation.

### Risques résiduels

1. **Entre 4.3 et 4.4, une entrée gelée est invisible.** `pendingCount` excluant les gelées, `OnlineBanner` ne s'affiche plus pour elles : le seul signal est un `console.error`. C'est le périmètre explicite de la Story 4.4 (notification du motif), suite immédiate — mais l'intervalle est un silence, là où le code précédent affichait un « waiting to sync » qui, lui, était un mensonge.
2. **`frozenCount`/`frozenEntries` n'ont aucun consommateur en production** jusqu'à 4.4/4.5 : leur unique cliente actuelle est la suite de tests.
3. **Aucun test de vue** ne couvre l'annulation de l'affichage optimiste (le repo n'en a aucun, bien que `@vue/test-utils` soit installé) : la ligne « Annulation de l'optimiste » de la matrice repose sur le mécanisme de refetch déjà éprouvé en 4.2, pas sur une preuve directe.
4. **Aucune idempotence côté serveur** (ni `Idempotency-Key`, ni dédup) : un rejeu dont la réponse se perd en vol double-applique l'action. La state machine rejette la seconde transition, mais `POST /api/v1/escrow` créerait une seconde transaction. Pré-existant, hors périmètre.
5. Trois reports versés au ledger : file non clefée par utilisateur, `escrow:sync` possiblement émis avant le montage des écouteurs, et intercepteur ne traitant pas le 403 nu comme une session expirée.
</content>
</invoke>
