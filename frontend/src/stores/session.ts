import { useAuthStore } from './auth'
import { useEscrowStore } from './escrow'
import { useEvidenceStore } from './evidence'
import { useOfflineQueueStore } from './offlineQueue'
import { resetSessionExpiryLatch } from '@/api/client'
import { readCredential, removeCredential, writeCredential } from '@/utils/credentialStorage'
import {
  forgetActivity,
  isIdleExpired,
  markActivity,
  readLastActivity,
  startIdleWatch,
} from '@/utils/idleTimeout'
import { publishSessionEnd, subscribeSessionEnd } from '@/utils/sessionBroadcast'
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
export type EndSessionReason = 'logout' | 'expired' | 'idle'

/**
 * Les raisons RECONNUES, énumérées en un seul endroit — et c'est le correctif d'un défaut
 * mécanique, pas une élégance.
 *
 * <p>Le test d'origine s'écrivait `!explicit && reason !== 'expired'`. La revue de la
 * Story 1.9 avait déjà relevé qu'une condition écrite ainsi fait tomber toute raison
 * NOUVELLE sur le chemin conservateur EN SILENCE ; la Story 2.7 ajoute `'idle'`, ce qui
 * rouvrait le défaut par le bord opposé — `'idle'` aurait déclenché la plainte « raison
 * inconnue » à chaque expiration d'inactivité, un diagnostic faux à chaque déclenchement
 * normal. Une liste que le compilateur relit (`Set<EndSessionReason>`) rend les deux
 * fautes impossibles : ajouter une raison à l'union sans l'ajouter ici ne compile pas
 * différemment, mais l'oubli se voit à un seul endroit au lieu de deux conditions
 * disséminées.
 */
const KNOWN_REASONS: ReadonlySet<string> = new Set<EndSessionReason>([
  'logout',
  'expired',
  'idle',
])

/**
 * Ce que la personne trouvera écrit sur l'écran d'authentification, et POURQUOI c'est
 * persisté plutôt que passé en paramètre de route.
 *
 * <p>Les deux déclencheurs de l'AC2 n'arrivent pas par le même chemin. La minuterie
 * navigue elle-même et pourrait porter le motif dans l'URL ; le contrôle au DÉMARRAGE,
 * lui, s'exécute avant le montage de l'application, sur un onglet qui vient d'être rouvert
 * — il n'y a ni navigation à décorer, ni mémoire vive à consulter. Un seul mécanisme, écrit
 * dans le substrat, sert les deux : sans quoi le motif ne s'afficherait que dans le cas où
 * l'utilisateur était déjà là pour le voir.
 *
 * <p>Dans le substrat du jeton, comme l'horodatage : une préférence d'appareil survivrait
 * à la fermeture de l'onglet et accueillerait un inconnu avec le message destiné au
 * partant.
 */
export const SESSION_NOTICE_STORAGE_KEY = 'escrow_session_notice'

/** Le seul motif affiché à ce jour. Les autres fins de session n'en produisent pas. */
export type SessionNotice = 'idle'

/**
 * Lit le motif ET l'efface — un seul appel, jamais deux.
 *
 * <p>La lecture destructrice est le contrat : un motif qui survivrait à son affichage
 * réapparaîtrait à chaque retour sur l'écran d'authentification, y compris après une
 * déconnexion volontaire, en expliquant une expiration qui n'a pas eu lieu.
 */
export function takeSessionNotice(): SessionNotice | null {
  const raw = readCredential(SESSION_NOTICE_STORAGE_KEY)
  removeCredential(SESSION_NOTICE_STORAGE_KEY)
  return raw === 'idle' ? 'idle' : null
}

/**
 * D'OÙ vient l'ordre de terminer la session (Story 2.7, AC3).
 *
 * <p>`'this-tab'` — l'utilisateur a agi ici, ou c'est ici que le jeton s'est fait refuser.
 * `'another-tab'` — un autre onglet du même appareil vient de l'annoncer par
 * `BroadcastChannel`.
 *
 * <p>Ce n'est pas une nuance de journalisation : c'est la RÈGLE D'AUTORITÉ de l'AC3, et
 * elle décide de trois choses à elle seule — voir les trois emplacements où
 * `fromAnotherTab` est lu ci-dessous.
 */
export type EndSessionSource = 'this-tab' | 'another-tab'

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
  source = 'this-tab',
}: { reason?: EndSessionReason | (string & {}); source?: EndSessionSource } = {}): Promise<void> {
  const auth = useAuthStore()
  const fromAnotherTab = source === 'another-tab'

  // RÈGLE D'AUTORITÉ ENTRE ONGLETS (AC3), CONSÉQUENCE N° 1 SUR 3.
  //
  // `explicit` commande TOUT le bloc du bas : purge IndexedDB de l'utilisateur, cache de
  // lecture, marqueur `escrow_last_user`, révocation serveur. Ces quatre effets portent
  // sur de l'état PARTAGÉ PAR L'APPAREIL ou sur le réseau, et l'onglet où l'action a eu
  // lieu vient de les traiter selon la raison — il est l'émetteur unique. Les rejouer ici
  // serait au mieux N tours d'IndexedDB pour un travail déjà fait, au pire N révocations
  // sur `/auth/*`, que NFR-P2 limite en débit : une déconnexion ordinaire se
  // transformerait en rafale anti-bruteforce dirigée contre l'utilisateur lui-même.
  //
  // Ce qui reste à faire ici, et que personne d'autre ne peut faire, est au-dessus de la
  // ligne : les identifiants de CET onglet. Par l'AC1 ils vivent en `sessionStorage`,
  // cloisonné par onglet, donc la purge de l'émetteur ne les atteint pas. C'est la raison
  // d'être du canal.
  const explicit = reason === 'logout' && !fromAnotherTab

  // Three reasons and no fourth. An unrecognised one falls through to the
  // RETAINING path, which is the safe direction — destroying a user's queued
  // evidence because a mode was added and spelled wrong is not a recoverable
  // mistake — but it is also the one that leaves data on a shared device, so it
  // must not be silent. Logged rather than thrown: this runs from an event
  // listener and the contract above says it never rejects.
  if (typeof reason !== 'string' || !KNOWN_REASONS.has(reason)) {
    console.error(
      `[session] unknown end-of-session reason ${JSON.stringify(reason)}; keeping stored data (expiry semantics)`,
    )
  }

  // RÈGLE D'AUTORITÉ, CONSÉQUENCE N° 2 : LE RÉCEPTEUR NE RÉ-ÉMET PAS.
  //
  // Sans cette condition, A annonce à B, B annonce à A, et deux onglets se renvoient une
  // fin de session pour la vie de la page. Une seule condition ferme la boucle, à
  // l'endroit où l'information « d'où vient l'ordre » existe encore.
  //
  // ÉMIS TÔT, avant la moindre purge et surtout avant l'attente réseau. La révocation du
  // bas de cette fonction est un `fetch` sans délai d'expiration : sur un portail captif
  // elle peut pendre des minutes, et annoncer APRÈS laisserait les autres onglets afficher
  // une interface authentifiée pendant tout ce temps — c'est-à-dire exactement le défaut
  // que l'AC3 ferme. Rien dans la terminaison de cet onglet-ci n'est un préalable à celle
  // des autres : elles sont indépendantes et doivent courir en parallèle.
  if (!fromAnotherTab) publishSessionEnd(reason)

  // ÉCRIT AVANT LA PURGE, pendant que le substrat actif est encore celui de la session qui
  // s'achève. `auth.clearSession()` ne touche que le jeton et le profil, donc l'ordre est
  // libre aujourd'hui — mais l'écrire ici le met du bon côté de la seule frontière qui
  // compte dans ce fichier : tout ce qui est local se fait avant l'appel réseau.
  //
  // `'idle'` SEULEMENT. L'expiration par 403 nu (`'expired'`) garde le comportement muet
  // que la Story 1.9 lui a donné : lui ajouter un motif serait un changement d'UX hors du
  // périmètre de l'AC2, et une story qui élargit son AC en passant est une story dont
  // personne n'a validé la moitié.
  if (reason === 'idle') writeCredential(SESSION_NOTICE_STORAGE_KEY, 'idle')

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

  // L'horodatage d'inactivité part avec le jeton, QUELLE QUE SOIT la raison.
  //
  // Il décrit la session qui s'achève et rien d'autre. Laissé derrière, il serait lu par
  // le contrôle au démarrage de la session SUIVANTE comme la fraîcheur de celle-ci : la
  // personne d'après hériterait du compteur de la précédente — trop frais si elle vient
  // de partir, périmé si elle est partie hier, et faux dans les deux cas.
  try {
    forgetActivity()
  } catch (err) {
    console.error('[session] could not clear the idle-activity stamp', err)
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

  // Le compteur d'inactivité part D'ICI et pas de la première frappe : sans cet
  // horodatage, une session fraîche n'a rien à mesurer, et le contrôle au démarrage la
  // traiterait comme la session d'avant-hier dont l'horodatage s'est perdu. C'est aussi
  // la SEULE remise à zéro liée à une connexion — le verrou `sessionExpiryAnnounced`
  // ci-dessus reste, lui, abaissé par `beginSession` et par rien d'autre (`client.ts:30`) :
  // la minuterie d'inactivité est un mécanisme DISTINCT et ne le touche jamais.
  try {
    markActivity()
  } catch (err) {
    console.error('[session] could not stamp the initial activity', err)
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
 * La cible à rapporter sur l'écran d'authentification (UX-DR32).
 *
 * <p>Read before the teardown, not after: nothing below navigates, but the target is the
 * one piece of state a teardown handler cannot reconstruct.
 *
 * <p>No target worth coming back to: already on the sign-in screen (where
 * `auth?redirect=/auth` would be a small loop written into the URL), or on the dashboard,
 * which is where the sign-in screen sends people anyway. The second exception mirrors
 * `router/index.ts` on purpose — the places that build this URL must not disagree about
 * what deserves a `redirect`. Ils sont désormais DEUX à l'appeler (expiration par 403 nu
 * et expiration d'inactivité), ce qui est justement la raison de l'extraire : recopié,
 * l'un des deux aurait fini par écrire `redirect=/`.
 */
function signInQuery(router: Router): { redirect?: string } {
  const from = router.currentRoute?.value
  const target = from?.name === 'auth' ? null : from?.fullPath
  return !target || target === '/' ? {} : { redirect: target }
}

/**
 * Same `try/catch`-per-effect rule as `endSession`, and here it is not decoration: the
 * callers are async event listeners and timers, so a rejected navigation (a `beforeEach`
 * that throws, a lazy route chunk that fails to load) would escape as an unhandled
 * rejection. The teardown that precedes it has already happened, which is the part that
 * must not be lost.
 */
async function returnToSignIn(router: Router, query: { redirect?: string }): Promise<void> {
  try {
    await router.replace({ name: 'auth', query })
  } catch (err) {
    console.error('[session] could not return to the sign-in screen', err)
  }
}

/**
 * Le contrôle AU DÉMARRAGE — celui qui attrape l'onglet rouvert (AC2).
 *
 * <p><b>Pourquoi la minuterie ne suffit pas, et pourquoi ce contrôle n'est pas une
 * ceinture de plus.</b> Une minuterie ne vit que dans l'onglet qui la porte. Fermé
 * l'onglet, la mesure disparaît avec lui : la personne qui rouvre l'application le
 * lendemain sur un poste où « rester connecté » avait été coché n'a JAMAIS été inactive
 * du point de vue d'une minuterie — celle-ci n'existait plus. Pire, le shell applicatif
 * est précaché par le service worker (UX-DR46) : l'interface authentifiée s'affiche AVANT
 * le premier appel API, donc avant même que le serveur ait pu refuser quoi que ce soit.
 * Sans ce contrôle, la politique se contourne d'une simple réouverture.
 *
 * <p><b>Asynchrone, mais tout ce qui compte se fait AVANT le premier `await`.</b>
 * `main.ts` ne l'attend pas — il ne peut pas, `app.mount()` n'attend rien. Il n'en a pas
 * besoin : `endSession` vide les identifiants et les quatre stores de façon SYNCHRONE, et
 * la sémantique `expired` la fait sortir avant le moindre `await`. Au retour de cet appel,
 * la session est donc déjà morte et la garde du routeur enverra vers `/auth` à la première
 * navigation. Cette propriété est load-bearing et elle est asservie par un test qui
 * n'attend PAS la promesse.
 *
 * <p>Rend `true` si elle a mis fin à une session — pour que le fait soit observable
 * autrement que par ses effets de bord.
 */
export async function enforceIdlePolicy(): Promise<boolean> {
  const auth = useAuthStore()
  // Personne n'est connecté : rien à expirer. Un horodatage traînant — laissé par un
  // appel anonyme, le module d'inactivité ne connaissant aucune clé métier — ne doit pas
  // se transformer en fin de session pour un visiteur qui n'en a pas.
  if (!auth.isAuthenticated) return false

  // Session sans horodatage : ouverte avant que cette story n'existe, ou horodatage
  // perdu. L'absence n'établit PAS l'inactivité, et la lire ainsi déconnecterait tout le
  // monde au déploiement pour un fait que personne n'a constaté. On l'horodate donc
  // sur-le-champ : elle devient mesurable, au prix d'une seule fenêtre de 15 minutes, une
  // seule fois. L'écart est nommé plutôt que coché.
  if (readLastActivity() === null) {
    markActivity()
    return false
  }

  if (!isIdleExpired()) return false

  await endSession({ reason: 'idle' })
  return true
}

/**
 * La minuterie d'inactivité, celle qui attrape l'onglet RESTÉ ouvert (AC2).
 *
 * <p>L'autre moitié du dispositif. Elle ne remplace pas le contrôle au démarrage et n'en
 * est pas remplaçable : celui-ci ne s'exécute qu'une fois, au boot.
 *
 * <p>Le garde d'authentification est dans le gestionnaire et non dans la minuterie : la
 * veille tourne pour la vie de l'onglet, y compris sur l'écran d'authentification, et une
 * échéance atteinte là-bas ne doit produire ni purge ni navigation — seulement le tour
 * suivant.
 */
export function installIdleTimeout(router: Router): () => void {
  return startIdleWatch(() => {
    // Volontairement non attendue : `startIdleWatch` appelle un rappel SYNCHRONE, et
    // `endSession` ne rejette jamais — il n'y a donc rien à rattraper ici, et rien qui
    // doive retarder le réarmement de la veille.
    void expireForIdleTimeout(router)
  })
}

async function expireForIdleTimeout(router: Router): Promise<void> {
  if (!useAuthStore().isAuthenticated) return
  const query = signInQuery(router)
  await endSession({ reason: 'idle' })
  await returnToSignIn(router, query)
}

/**
 * LA PROPAGATION INTER-ONGLETS, CÔTÉ RÉCEPTEUR (AC3).
 *
 * <p>Un autre onglet du même appareil vient d'annoncer la fin de la session. Celui-ci
 * termine la sienne « sans intervention », et de façon OBSERVABLE : il navigue vers
 * l'écran d'authentification, exactement comme le fait déjà `installSessionExpiryListener`
 * pour un 403 nu. Surtout pas de `location.reload()` — la convention Frontend du spine
 * interdit le rechargement silencieux, et un rechargement ferait de surcroît perdre à
 * l'utilisateur la saisie en cours d'un onglet qu'il n'a peut-être même pas regardé.
 *
 * <p>La raison VOYAGE avec le message, pour que l'onglet récepteur affiche le motif que
 * l'onglet émetteur affiche : une expiration d'inactivité y écrit le même motif (`'idle'`,
 * via `endSession`), une déconnexion volontaire n'en écrit aucun — l'utilisateur l'a
 * voulue, il n'y a rien à lui expliquer.
 *
 * <p><b>Le veto du récepteur (décision D-F, tranchée par le PO le 2026-08-11).</b> La
 * raison `'idle'` est mesurée PAR ONGLET quand le substrat est `sessionStorage` (le
 * défaut) : chaque onglet a son propre horodatage. Livrée telle quelle, la propagation
 * laissait donc un onglet resté en arrière-plan quinze minutes mettre fin à la session
 * d'un onglet où l'utilisateur était en train de travailler. Un onglet qui n'est pas
 * lui-même inactif IGNORE désormais une annonce `'idle'` — voir
 * `endSessionFromAnotherTab` ci-dessous, où la règle est écrite et bornée.
 */
export function installSessionBroadcastListener(router: Router): () => void {
  return subscribeSessionEnd((reason) => {
    // Non attendue, comme le rappel de la minuterie : `endSessionFromAnotherTab` ne
    // rejette pas (tout y est déjà gardé), et l'émetteur du message n'attend personne.
    void endSessionFromAnotherTab(router, reason)
  })
}

async function endSessionFromAnotherTab(router: Router, reason: string | undefined): Promise<void> {
  // RÈGLE D'AUTORITÉ, CONSÉQUENCE N° 3 : un onglet qui n'a rien à terminer ne fait RIEN.
  //
  // Sans ce garde, un onglet déjà posé sur l'écran d'authentification se ferait renvoyer
  // vers l'écran d'authentification à chaque annonce — une navigation visible, sans objet,
  // qui écraserait au passage le paramètre `redirect` que l'utilisateur venait d'obtenir.
  if (!useAuthStore().isAuthenticated) return

  // LE VETO DU RÉCEPTEUR (décision D-F, tranchée par le PO le 2026-08-11).
  //
  // Le défaut qu'il ferme : l'horodatage d'inactivité vit dans le substrat du jeton, donc
  // en `sessionStorage` par défaut (AC1) — il est PROPRE À CHAQUE ONGLET. Un onglet laissé
  // en arrière-plan atteint son échéance au bout de quinze minutes, annonce `'idle'`, et
  // détruisait jusqu'ici la session d'un onglet où quelqu'un était en train de travailler.
  //
  // La règle : un onglet qui n'est pas LUI-MÊME inactif poursuit sa session. On relit donc
  // la primitive de T3 — `isIdleExpired()`, jamais un calcul recopié : un seuil à deux
  // endroits diverge au premier ajustement, et c'est le même horodatage que lit le
  // contrôle au démarrage. NFR-P8 est intact : un appareil réellement abandonné a TOUS ses
  // onglets inactifs, donc tous terminent. Un horodatage absent rend `false`, ce qui range
  // l'onglet du côté « pas de fait constaté, on ne termine pas » — la même lecture que
  // `enforceIdlePolicy`, qui horodate au lieu de déconnecter.
  //
  // ⚠️ LE VETO NE VAUT QUE POUR `'idle'`, et l'élargir serait un DÉFAUT DE SÉCURITÉ, pas
  // une amélioration. `'logout'` est un geste délibéré — la personne rend l'appareil — et
  // `'expired'` est un verdict du serveur sur un jeton qu'il refuse déjà. Ni l'un ni
  // l'autre ne se discute au niveau du récepteur : un onglet actif qui les vétoerait
  // resterait authentifié sur un poste rendu, ou avec un jeton mort. Seule l'inactivité
  // est une DÉDUCTION locale, et c'est la seule chose qu'un onglet soit en droit de
  // contredire — parce qu'il en sait plus que l'émetteur sur sa propre activité.
  if (reason === 'idle' && !isIdleExpired()) return

  const query = signInQuery(router)
  await endSession({ reason, source: 'another-tab' })
  await returnToSignIn(router, query)
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
    const query = signInQuery(router)
    await endSession({ reason: 'expired' })
    await returnToSignIn(router, query)
  }

  window.addEventListener('escrow:session-expired', onExpired)
  return () => window.removeEventListener('escrow:session-expired', onExpired)
}
