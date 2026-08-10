import { useAuthStore } from './auth'
import { useEscrowStore } from './escrow'
import { useEvidenceStore } from './evidence'
import { useOfflineQueueStore } from './offlineQueue'
import { resetSessionExpiryLatch } from '@/api/client'
import * as idb from './offlineQueue.idb'
import type { Router } from 'vue-router'

/**
 * The beginning and the end of a session, in one place — and deliberately NOT a
 * `defineStore`. It owns no state of its own; it is the only module that knows
 * the *order* in which the four stores, IndexedDB and the read cache have to be
 * touched, and a store would have invited state to accrete around that order.
 *
 * It lives outside `stores/auth.js` to keep that store to one job. Be clear
 * about what this does and does not buy: the import graph IS cyclic either way
 * — `auth.js → session.js → escrow.js → auth.js`, and `auth.js → session.js`
 * directly. What makes it harmless is that no module here reads a cyclic binding
 * or calls `useXStore()` at evaluation time; every crossing happens inside a
 * function body, long after all four modules have finished evaluating. Keep it
 * that way: a `useEscrowStore()` at module scope in any of them turns this into
 * a TDZ `ReferenceError` at boot. Putting the orchestration inside the auth
 * store would not have removed the cycle, only made a store that every view
 * loads responsible for the teardown of three others.
 *
 * Two modes, two scopes, and the difference is the whole point (epic-1-context
 * §UX): an **expiry** is suffered — the user never chose to leave, so their
 * pending evidence and their read cache must wait for them — while a **logout**
 * is deliberate, the gesture by which a shared device is handed back. Folding
 * the two into one purge would destroy evidence on every expired token; folding
 * them into one retention would leave everything behind on the returned device.
 */

/**
 * Source of truth: `vite.config.js:53`, the Workbox `runtimeCaching` rule that
 * creates it. The same literal on both sides is checked by a `grep` in the
 * story's verification steps — there is no import path between a Vite config and
 * application code.
 *
 * Only this one is ever dropped: `escrow-app-shell` and `escrow-images` hold
 * HTML/CSS/JS and pictures, no user data, and clearing them would cost the next
 * user an offline-capable app for nothing.
 */
export const READ_CACHE_NAME = 'escrow-api-cache'

/**
 * Which user this device last saw signed in — an opaque id and nothing else,
 * where the `escrow_user` key carries a whole profile.
 *
 * It exists because the Workbox cache is keyed by URL and never by identity:
 * `GET /api/v1/escrow` has exactly one entry for the whole device. Keeping that
 * cache across an expiry — which is right, so an unstable network does not cost
 * a user their screen — makes it servable to whoever signs in *next* if they do
 * so offline. This marker turns "survives re-authentication" into "survives
 * re-authentication *by the same user*", which is what the sentence meant.
 *
 * <p>EXPORTÉE depuis la Story 2.7. Elle était `const` privée, et `session.spec.ts`
 * redéclarait le littéral `'escrow_last_user'` en dur : un renommage en production aurait
 * laissé ces assertions VERTES en interrogeant une clé que plus personne n'écrit. C'est la
 * classe de test creux que la rétrospective de l'Epic 1 a érigée en règle, et les trois
 * autres clés du dépôt (`TOKEN_STORAGE_KEY`, `USER_STORAGE_KEY`, `READ_CACHE_NAME`) sont
 * déjà importées depuis leur module par les tests. Celle-ci manquait à l'appel.
 *
 * <p>Reste en `localStorage` et NON dans le substrat commutable : c'est un marqueur
 * d'APPAREIL, pas un identifiant. Il doit survivre à la fermeture de l'onglet, sinon il ne
 * répond plus à la question qu'il pose — « qui cet appareil a-t-il vu en dernier ? » — et
 * le cache de lecture du précédent utilisateur ne serait plus jamais purgé.
 */
export const LAST_USER_STORAGE_KEY = 'escrow_last_user'

/**
 * Drops the 24 h of cached `/api/` responses the departing user leaves behind.
 *
 * `caches` is absent in jsdom and in a non-secure context, and the whole point
 * of this call is hygiene on the way out: an unavailable Cache API must never be
 * what stops a logout.
 * @returns {Promise<boolean>} whether a cache was actually deleted
 */
export async function purgeReadCache() {
  if (typeof caches === 'undefined') return false
  return caches.delete(READ_CACHE_NAME)
}

/**
 * Ends the session and takes down exactly as much as the reason calls for.
 *
 * `logout` — deliberate, and in this order: credentials and every in-memory
 * store are cleared first and synchronously, then the departing user's queued
 * entries (and the ownerless ones) are deleted from IndexedDB, the read cache is
 * dropped and the last-user marker removed — and only THEN is the server
 * revocation awaited (revue 1.6). Everything that leaves data behind on the
 * device happens before the one step that can hang. The order is the guarantee,
 * not an implementation detail — see the comments on both blocks.
 * `expired` — suffered: credentials and memory only. IndexedDB and the read
 * cache survive, and come back to their owner at the next sign-in.
 *
 * Every effect sits in its own `try/catch`, on purpose: these run on the way out
 * and each one is independent. An unavailable Cache API must not be the reason
 * the queue is left on the device, and a rejected revocation must not be the
 * reason the credentials stay in localStorage. Never rejects.
 */
export type EndSessionReason = 'logout' | 'expired'

/**
 * `reason` est typé LARGE, et c'est le comportement documenté juste en dessous : « Two
 * reasons and no third. An unrecognised one falls through to the RETAINING branch. » Une
 * raison inconnue n'est donc pas une erreur d'appel, c'est un cas TRAITÉ — celui qui
 * conserve les données plutôt que de les purger sur un mot qu'on n'a pas compris.
 * `session.spec` passe `'signout'` exprès. Restreindre à l'union aurait interdit au test
 * d'exprimer le cas que la fonction est écrite pour absorber.
 */
export async function endSession({
  reason,
}: { reason?: EndSessionReason | (string & {}) } = {}): Promise<void> {
  const auth = useAuthStore()
  const explicit = reason === 'logout'

  // Two reasons and no third. An unrecognised one falls through to the RETAINING
  // path, which is the safe direction — destroying a user's queued evidence
  // because a mode was added and spelled wrong is not a recoverable mistake —
  // but it is also the one that leaves data on a shared device, so it must not
  // be silent. Logged rather than thrown: this runs from an event listener and
  // the contract above says it never rejects.
  if (!explicit && reason !== 'expired') {
    console.error(
      `[session] unknown end-of-session reason ${JSON.stringify(reason)}; keeping stored data (expiry semantics)`,
    )
  }

  // Read BEFORE anything is cleared. `clearSession()` nulls `user`, and a purge
  // keyed on `undefined` would spare every one of this user's entries and take
  // only the ownerless ones — the exact inverse of what was asked.
  const userId = auth.user?.id
  const revokedToken = auth.token

  // EVERYTHING LOCAL AND SYNCHRONOUS COMES FIRST — the guarantee Story 1.6
  // spelled out, and it covers the screen as much as the credentials.
  // `logoutUser` is a `fetch` with no timeout and no AbortController: on a
  // captive portal it can hang for minutes, and `DashboardView` awaits this
  // whole function before navigating. Clearing only the credentials before that
  // wait would blank the email line while the transaction list, the open detail
  // and the evidence stayed on screen for the entire hang — on the very device
  // that was just handed back. Nothing below the network call may hold anything
  // a bystander could read.
  try {
    auth.clearSession()
  } catch (err) {
    console.error('[session] could not clear the stored credentials', err)
  }

  // `$reset()` and not a hand-written blanking: these stores gained
  // `transactionsFetchedAt` / `currentDetailFetchedAt` after they were written,
  // and a field list here would have to be maintained in step with them.
  try {
    useEscrowStore().$reset()
  } catch (err) {
    console.error('[session] could not reset the escrow store', err)
  }
  try {
    useEvidenceStore().$reset()
  } catch (err) {
    console.error('[session] could not reset the evidence store', err)
  }
  try {
    useOfflineQueueStore().clearMemory()
  } catch (err) {
    console.error('[session] could not clear the in-memory queue', err)
  }

  if (!explicit) return

  // THE PERSISTED PURGES COME BEFORE THE NETWORK CALL TOO, and for the same
  // reason as the block above — a reason worth spelling out because a previous
  // pass moved only the in-memory half and left these three behind the `await`.
  // `logoutUser` can hang for the life of the tab; a user who then closes it
  // would leave their queued evidence Blobs and 24 h of `/api/` responses on the
  // device they just handed back, with nothing ever running to remove them.
  // Everything below this point must be recoverable from a purge that already
  // happened — which is why the revocation, the only step whose outcome nothing
  // local depends on, goes last.
  try {
    await idb.clearForUser(userId)
  } catch (err) {
    console.error('[session] could not clear this user\'s queued entries', err)
  }
  try {
    await purgeReadCache()
  } catch (err) {
    console.error('[session] could not drop the read cache', err)
  }
  try {
    // The cache it guards has just been destroyed, so the marker has nothing
    // left to protect. An expiry keeps it — that is where it earns its keep.
    // Removed before the network wait as well: a marker left behind while the
    // revocation hangs is a marker the *next* user's `beginSession` would read.
    localStorage.removeItem(LAST_USER_STORAGE_KEY)
  } catch (err) {
    console.error('[session] could not clear the last-user marker', err)
  }

  // CE QUI N'EST DÉLIBÉRÉMENT PAS PURGÉ : `escrow_locale` (Story 2.1).
  //
  // La langue est une préférence d'APPAREIL, pas une donnée de session. Un poste
  // francophone qui repasserait en anglais à chaque déconnexion serait hostile, et la
  // langue choisie ne dit rien de l'identité du partant. Cette purge retire des clés
  // NOMMÉES et ne fait jamais de `localStorage.clear()` : la clé survit donc par
  // construction — mais par construction n'est pas par intention, d'où ce commentaire
  // et le test `survivesEndSession` de `__tests__/session.spec.js` qui l'asservit.
  //
  // ⚠️ Story 2.7 (politique de session sur appareil partagé) réécrira ce fichier :
  // ne pas ajouter `escrow_locale` à la purge sans rouvrir la décision.

  try {
    // Awaited nonetheless (revue 1.6): navigating away used to abort the
    // request in flight, leaving the token accepted server-side until it
    // expired. `keepalive` is the second line of defence; awaiting is what
    // makes it deterministic. Nothing above depends on the outcome, and by the
    // time this hangs there is nothing left — on screen or on disk — to leak.
    await auth.revokeOnServer(revokedToken)
  } catch (err) {
    // `revokeOnServer` already swallows its own failures; this is the belt to
    // its braces, so that a client left offline still signs out locally.
    console.error('[session] server revocation failed; signing out locally anyway', err)
  }
}

/**
 * Starts a session in a tab that is already running: re-arms the session-expiry
 * announcement, drops the read cache if the person signing in is not the one
 * this device last saw, records who that is now, and adopts this user's queued
 * entries.
 *
 * The cache purge comes *before* anything is adopted or rendered: after an
 * expiry the cache was kept on purpose, and a different user signing in offline
 * would otherwise be served the previous one's `/api/` responses by Workbox.
 *
 * Same `try/catch`-per-effect rule as `endSession`, and for a sharper reason:
 * this runs inside `applySession`, so an exception here would turn a successful
 * login into a failed one. Never rejects.
 */
export async function beginSession(userId: string | number | null | undefined): Promise<void> {
  // The latch is lowered here and never on a timer: a timer would reopen the
  // teardown window at an arbitrary moment, which is precisely what the burst of
  // parallel 403s makes dangerous.
  try {
    resetSessionExpiryLatch()
  } catch (err) {
    console.error('[session] could not re-arm the session-expiry latch', err)
  }

  // Belt to `endSession`'s braces, and not redundant with it. `endSession`
  // clears these stores on the way out, but a read issued just before it can
  // land just after: `escrow.loadTransactions` writes `this.transactions =
  // await fetchTransactions()` with no session guard, so a response in flight
  // when the user signed out re-populates the store behind the teardown. Nothing
  // would then clear it, and the next person to sign in renders the previous
  // one's transaction list for as long as their own fetch takes. Resetting at
  // the *start* of a session closes that window whatever happened at the end of
  // the last one, and costs nothing: nobody has anything worth keeping here yet.
  try {
    useEscrowStore().$reset()
  } catch (err) {
    console.error('[session] could not reset the escrow store', err)
  }
  try {
    useEvidenceStore().$reset()
  } catch (err) {
    console.error('[session] could not reset the evidence store', err)
  }

  // Compared as strings: the id round-trips through localStorage on one side and
  // through a JSON payload on the other, so the same user can present as 42 and
  // as '42'. A mismatch here would purge a cache that was legitimately theirs.
  const current = userId == null ? null : String(userId)
  let lastUser = null
  let markerRead = false
  try {
    lastUser = localStorage.getItem(LAST_USER_STORAGE_KEY)
    markerRead = true
  } catch (err) {
    // Unreadable marker: treated exactly like an absent one below, which is the
    // purging side. Guessing "same user" off a failed read is the one reading
    // that can serve A's cached responses to B.
    console.error('[session] could not read the last-user marker', err)
  }

  // An ABSENT marker counts as a mismatch, deliberately. It means one of two
  // things and this code cannot tell them apart: a genuinely fresh device, where
  // the purge costs nothing because there is no cache — or a session that
  // predates this story, whose user filled `escrow-api-cache` and then expired
  // without ever writing a marker. Reading "absent" as "fresh" would leave the
  // whole upgrade window unguarded, which is the one window where a device is
  // certain to hold somebody's cached responses. The price of the safe reading
  // is that the first sign-in after an upgrade re-fetches; the price of the
  // other one is user A's transaction list served to user B.
  //
  // Absence is tested EXPLICITLY and not through `lastUser !== current`: that
  // comparison silently agreed with itself when the marker was absent AND the
  // session carried no user id (`null !== null` is false), skipping the purge in
  // the very case the paragraph above says it exists for — and a session with no
  // `user.id` is realistic enough that this file already handles it elsewhere.
  const markerAbsent = !markerRead || lastUser === null
  if (markerAbsent || lastUser !== current) {
    try {
      await purgeReadCache()
    } catch (err) {
      console.error('[session] could not drop the previous user\'s read cache', err)
    }
  }

  try {
    if (current == null) localStorage.removeItem(LAST_USER_STORAGE_KEY)
    else localStorage.setItem(LAST_USER_STORAGE_KEY, current)
  } catch (err) {
    console.error('[session] could not record the last-user marker', err)
  }

  try {
    await useOfflineQueueStore().adoptSession()
  } catch (err) {
    console.error('[session] could not adopt this session\'s queued entries', err)
  }
}

/**
 * Turns the `escrow:session-expired` event raised by `api/client.js` into a
 * teardown and a trip back to the sign-in screen, with the user's target kept
 * (UX-DR32).
 *
 * The router arrives as an argument rather than through an import: that is what
 * makes this testable without mounting the application, and this is the only
 * place in the codebase that legitimately knows both the router and the stores.
 *
 * <p>Rend la fonction de retrait de l'écouteur — pour les tests et par symétrie.
 */
export function installSessionExpiryListener(router: Router): () => void {
  if (typeof window === 'undefined') return () => {}

  const onExpired = async () => {
    // Read before the teardown, not after: nothing below navigates, but the
    // target is the one piece of state this handler cannot reconstruct.
    const from = router.currentRoute?.value
    // No target worth coming back to: already on the sign-in screen (where
    // `auth?redirect=/auth` would be a small loop written into the URL), or on
    // the dashboard, which is where the sign-in screen sends people anyway. The
    // second exception mirrors `router/index.js:50` on purpose — the two places
    // that build this URL must not disagree about what deserves a `redirect`.
    const target = from?.name === 'auth' ? null : from?.fullPath
    const query = !target || target === '/' ? {} : { redirect: target }

    await endSession({ reason: 'expired' })
    try {
      await router.replace({ name: 'auth', query })
    } catch (err) {
      // Same `try/catch`-per-effect rule as `endSession`, and here it is not
      // decoration: this is an async event listener, so a rejected navigation
      // (a `beforeEach` that throws, a lazy route chunk that fails to load)
      // would escape as an unhandled rejection. The teardown above has already
      // happened, which is the part that must not be lost.
      console.error('[session] could not return to the sign-in screen', err)
    }
  }

  window.addEventListener('escrow:session-expired', onExpired)
  return () => window.removeEventListener('escrow:session-expired', onExpired)
}
