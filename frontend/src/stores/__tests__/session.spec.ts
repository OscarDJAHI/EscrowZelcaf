import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient, { TOKEN_STORAGE_KEY } from '@/api/client'
import { logoutUser } from '@/api/auth'
import { USER_STORAGE_KEY, useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useEvidenceStore } from '@/stores/evidence'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import { LOCALE_STORAGE_KEY } from '@/i18n'
import {
  READ_CACHE_NAME,
  beginSession,
  endSession,
  installSessionExpiryListener,
} from '@/stores/session'
import * as idb from '@/stores/offlineQueue.idb'
import { aTransaction, anEvidenceItem, aUser } from '@/test-support/factories'
import type { QueueEntry } from '@/types/queue'
import type { User } from '@/types/domain'
import type { Router } from 'vue-router'

/**
 * What Story 1.9 is worth is as much in what survives as in what goes. Every
 * assertion below is paired: A's entries disappear *and* B's are still there;
 * an expiry clears the memory *and* leaves IndexedDB and the read cache alone.
 * A suite that only checked the deletions would pass just as well on a
 * `clear()` that wiped a third party's only copy of their evidence.
 */

vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
}))
vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal()
  // Only the axios instance is replaced: `TOKEN_STORAGE_KEY` and
  // `resetSessionExpiryLatch` are the real ones, so the keys this suite asserts
  // on are the keys production writes.
  // `importActual` rend `unknown` : le module réel est bien un objet, mais le
  // compilateur ne peut pas le savoir. L'assertion porte sur ce seul fait.
  return { ...(actual as object), default: { request: vi.fn() } }
})

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })
const BOB = aUser({ id: 7, email: 'bob@corp.example' })
const LAST_USER_STORAGE_KEY = 'escrow_last_user'

/**
 * A persisted queue entry, as `enqueue()` really writes one. The `seq` also
 * drives the URL, so "which entry was replayed" is answerable from the request
 * itself rather than from a count that could not tell whose it was.
 */
interface EntryOptions {
  id: string
  /** `null` = entrée sans propriétaire — le cas que Story 4.5 refuse d'attribuer. */
  userId?: number | null
  frozen?: boolean
  seq?: number
}

function entry({ id, userId, frozen = false, seq = 1 }: EntryOptions): QueueEntry {
  return {
    id,
    timestamp: `2026-01-01T00:00:0${seq}.000Z`,
    seq,
    method: 'post',
    url: `/api/v1/escrow/${seq}/event`,
    // `SHIP_GOODS` : `SHIP` n'existe dans aucune énumération. La file transporte la
    // charge sans la lire, mais une fixture doit ressembler à ce qui y transite.
    data: { event: 'SHIP_GOODS' },
    meta: { type: 'SEND_EVENT', transactionId: seq, ...(userId === null ? {} : { userId }) },
    ...(frozen ? { frozen: true, failure: { code: 'ILLEGAL_TRANSITION', status: 400, message: null, at: '2026-01-01T00:05:00.000Z' } } : {}),
  }
}

/** The URL a given seeded entry replays to — read off the same expression. */
const URL_OF = { alicePending: '/api/v1/escrow/1/event', bobPending: '/api/v1/escrow/3/event', orphan: '/api/v1/escrow/4/event' }

/**
 * The device as a shared device really is: two of ALICE's entries (one frozen),
 * one of BOB's, and one queued before the ownership stamp shipped.
 */
async function seedSharedDevice() {
  await idb.put(entry({ id: 'alice-pending', userId: ALICE.id, seq: 1 }))
  await idb.put(entry({ id: 'alice-frozen', userId: ALICE.id, frozen: true, seq: 2 }))
  await idb.put(entry({ id: 'bob-pending', userId: BOB.id, seq: 3 }))
  await idb.put(entry({ id: 'orphan', userId: null, seq: 4 }))
}

async function storedIds() {
  return (await idb.getAll()).map((item) => item.id).sort()
}

/** A stand-in for the Cache API, absent from jsdom. */
function stubCaches() {
  const del = vi.fn(async () => true)
  // Un double PARTIEL, assumé : `endSession` n'appelle que `caches.delete`, et jsdom
  // n'implémente pas l'API du tout. Fabriquer `has`/`keys`/`match`/`open` pour satisfaire
  // le type donnerait quatre fonctions que rien n'exerce — un décor, pas une garantie.
  globalThis.caches = { delete: del } as unknown as CacheStorage
  return del
}

/**
 * Signs a user in the way the application does, then lets the replay finish.
 *
 * `adoptSession()` starts `flush()` without awaiting it, so that signing in does
 * not block on a multi-megabyte upload. Awaiting `applySession` therefore only
 * guarantees the queue has been *read*. A macrotask tick drains every microtask
 * the replay is made of — without it these assertions would happen to pass only
 * because `flush()` issues its first request synchronously, which is a race
 * dressed up as a test.
 */
async function signInAndReplay(user: User) {
  await useAuthStore().applySession({ token: `${user.email}-token`, user: { ...user } })
  await new Promise((resolve) => setTimeout(resolve, 0))
}

/** A signed-in session, both halves, without firing `beginSession`. */
function signIn(user: User) {
  const auth = useAuthStore()
  auth.token = `${user.email}-token`
  auth.user = { ...user }
  auth.persist()
  return auth
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  // Story 2.7 : le jeton et le profil vivent dans le substrat COMMUTABLE
  // (sessionStorage par defaut). Vider le seul localStorage laisserait fuir une
  // session d'un test au suivant, et les cas « personne n'est connecte » passeraient
  // en trouvant l'utilisateur du test precedent.
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.mocked(apiClient.request).mockReset()
  vi.mocked(apiClient.request).mockResolvedValue({ data: {} })
  vi.clearAllMocks()
})

afterEach(() => {
  // `delete` sur une globale déclarée non optionnelle : le nettoyage est réel (jsdom
  // n'a pas d'API Cache, la globale n'existe que parce que `stubCaches` l'a posée).
  delete (globalThis as { caches?: CacheStorage }).caches
  vi.restoreAllMocks()
})

describe('endSession — an explicit logout hands the device back', () => {
  it('survivesEndSession : la langue choisie N’EST PAS purgée — c’est une préférence d’appareil', async () => {
    // Décision de la Story 2.1, exigée « prouvée par test » par ses propres Dev Notes et
    // relevée manquante en revue : la purge retire des clés NOMMÉES, donc `escrow_locale`
    // survit par CONSTRUCTION. Ce test transforme cette construction en INTENTION : la
    // Story 2.7 réécrira `session.js`, et si elle ajoute la langue à la purge, c'est ici
    // que ça rougira.
    stubCaches()
    await seedSharedDevice()
    signIn(ALICE)
    localStorage.setItem(LOCALE_STORAGE_KEY, 'fr')

    await endSession({ reason: 'logout' })

    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('fr')
    // Contre-épreuve dans le même souffle : ce qui DOIT partir est bien parti, sinon le
    // test ci-dessus prouverait seulement qu'aucune purge n'a eu lieu.
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
  })

  it('revokes, clears every store, drops only this user\'s entries and the read cache', async () => {
    const del = stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)
    const escrow = useEscrowStore()
    const evidence = useEvidenceStore()
    const queue = useOfflineQueueStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'SHIPPED' }), auditLogs: [] }
    escrow.transactionsFetchedAt = '2026-01-01T00:00:00.000Z'
    evidence.items = [anEvidenceItem({ id: 1, originalFilename: 'invoice.pdf' })]
    evidence.loadedId = 7
    queue.queue = [entry({ id: 'alice-pending', userId: ALICE.id })]

    await endSession({ reason: 'logout' })

    // The server is told, with the token that was still valid a moment ago.
    expect(logoutUser).toHaveBeenCalledWith('alice@corp.example-token')
    // Credentials gone, from memory and from localStorage.
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    // Read through the exported keys, never through a literal: a rename must
    // move this assertion with the production code, not leave it green.
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(USER_STORAGE_KEY)).toBeNull()
    // Memory: nothing of ALICE's is left for the next person to read.
    expect(escrow.transactions).toEqual([])
    expect(escrow.currentDetail).toBeNull()
    expect(escrow.transactionsFetchedAt).toBeNull()
    expect(evidence.items).toEqual([])
    expect(evidence.loadedId).toBeNull()
    expect(queue.queue).toEqual([])
    // The 24 h of cached `/api/` responses go with them.
    expect(del).toHaveBeenCalledWith(READ_CACHE_NAME)
  })

  it('leaves BOB\'s entry untouched while deleting ALICE\'s and the ownerless one', async () => {
    stubCaches()
    await seedSharedDevice()
    signIn(ALICE)

    await endSession({ reason: 'logout' })

    // The whole point: hygiene must not be paid for with a third party's only
    // copy of their evidence. `clear()` here would be a new bug, not a fix.
    expect(await storedIds()).toEqual(['bob-pending'])
  })

  it('finishes the local purge even when revocation, IndexedDB and caches all fail', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    globalThis.caches = {
      delete: vi.fn(async () => {
        throw new Error('cache API unavailable')
      }),
    } as unknown as CacheStorage
    await seedSharedDevice()
    const auth = signIn(ALICE)
    vi.mocked(logoutUser).mockRejectedValueOnce(new Error('network down'))
    vi.spyOn(idb, 'clearForUser').mockRejectedValueOnce(new Error('IndexedDB unavailable'))

    // A logout that could reject would leave the user signed in on a device they
    // have just handed back.
    await expect(endSession({ reason: 'logout' })).resolves.toBeUndefined()

    expect(auth.token).toBeNull()
    expect(useOfflineQueueStore().queue).toEqual([])
  })

  it('empties the device BEFORE waiting on the network, not after it', async () => {
    // `logoutUser` is a bare `fetch` with no timeout and no AbortController, and
    // `DashboardView` awaits `endSession` before it navigates: whatever is still
    // on the device when the revocation hangs is still there, on a captive
    // portal, for as long as it hangs — and a user who then closes the tab
    // leaves it there for good, since nothing is left running to remove it. So
    // the ordering is the guarantee, not an implementation detail. A revocation
    // that never settles is the only way to assert it: with a resolved mock the
    // two orders are indistinguishable.
    //
    // EVERY local effect is asserted here, in memory *and* on disk. A previous
    // pass moved only the credentials in front of the wait and left the stores
    // behind it; the pass after that moved the stores and left IndexedDB, the
    // read cache and the marker behind it. Each time the suite stayed green
    // because it only ever checked the half that had already moved.
    const del = stubCaches()
    const auth = signIn(ALICE)
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))
    await seedSharedDevice()
    const escrow = useEscrowStore()
    const evidence = useEvidenceStore()
    const queue = useOfflineQueueStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'SHIPPED' }), auditLogs: [] }
    evidence.items = [anEvidenceItem({ id: 1, originalFilename: 'invoice.pdf' })]
    queue.queue = [entry({ id: 'alice-pending', userId: ALICE.id })]

    let releaseRevocation: (() => void) | undefined
    vi.mocked(logoutUser).mockReturnValueOnce(new Promise((resolve) => { releaseRevocation = resolve }))

    const pending = endSession({ reason: 'logout' })
    // Waited for rather than drained with a fixed tick: the purges that now run
    // ahead of the revocation are real IndexedDB and Cache API round-trips, and
    // a hard-coded number of macrotasks silently becomes wrong the moment one
    // more `await` joins the chain. The revocation is still in flight from here
    // on, which is the state every assertion below is about.
    await vi.waitFor(() => expect(logoutUser).toHaveBeenCalled())

    expect(auth.token).toBeNull()
    expect(escrow.transactions).toEqual([])
    expect(escrow.currentDetail).toBeNull()
    expect(evidence.items).toEqual([])
    expect(queue.queue).toEqual([])
    // On disk too — and BOB's entry survives, as everywhere else in this suite.
    expect(await storedIds()).toEqual(['bob-pending'])
    expect(del).toHaveBeenCalledWith(READ_CACHE_NAME)
    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBeNull()

    releaseRevocation!()
    await expect(pending).resolves.toBeUndefined()
  })

  it('purges the ownerless entries alone when the session carries no user id', async () => {
    stubCaches()
    await seedSharedDevice()
    const auth = useAuthStore()
    auth.token = 'a-token-with-no-user'
    auth.user = null

    await endSession({ reason: 'logout' })

    // No id means nobody to purge for. Guessing would delete a stranger's files.
    expect(await storedIds()).toEqual(['alice-frozen', 'alice-pending', 'bob-pending'])
  })
})

describe('endSession — an expiry is suffered, not chosen', () => {
  it('clears credentials and memory but keeps IndexedDB and the read cache', async () => {
    const del = stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    queue.queue = [entry({ id: 'alice-pending', userId: ALICE.id })]

    await endSession({ reason: 'expired' })

    expect(auth.token).toBeNull()
    expect(escrow.transactions).toEqual([])
    expect(queue.queue).toEqual([])
    expect(queue.hydrated).toBe(false)
    // The user never chose to leave: their pending evidence waits for them, and
    // so does the screen they were looking at.
    expect(await storedIds()).toEqual(['alice-frozen', 'alice-pending', 'bob-pending', 'orphan'])
    expect(del).not.toHaveBeenCalled()
  })

  it('never calls the revocation endpoint — the token is already refused', async () => {
    signIn(ALICE)

    await endSession({ reason: 'expired' })

    expect(logoutUser).not.toHaveBeenCalled()
  })

  it('keeps the last-user marker, which is what still guards the surviving cache', async () => {
    stubCaches()
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))
    signIn(ALICE)

    await endSession({ reason: 'expired' })

    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBe(String(ALICE.id))
  })
})

describe('endSession — an unrecognised reason keeps, and says so', () => {
  it('falls back to the retaining path and complains, rather than deleting quietly', async () => {
    // There are two reasons and no third. If one is ever added and spelled
    // wrong, the failure must land on the side that keeps a user's only copy of
    // their evidence — and it must be visible, because that same side is the one
    // that leaves entries on a shared device.
    const complain = vi.spyOn(console, 'error').mockImplementation(() => {})
    const del = stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)

    await endSession({ reason: 'signout' })

    expect(auth.token).toBeNull()
    expect(logoutUser).not.toHaveBeenCalled()
    expect(del).not.toHaveBeenCalled()
    expect(await storedIds()).toEqual(['alice-frozen', 'alice-pending', 'bob-pending', 'orphan'])
    expect(complain).toHaveBeenCalledWith(expect.stringContaining('unknown end-of-session reason'))
  })
})

describe('beginSession — the read cache belongs to whoever filled it', () => {
  it('keeps the cache when the same user signs back in after an expiry', async () => {
    const del = stubCaches()
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))

    await beginSession(ALICE.id)

    // Losing it here would cost a user on an unstable network their screen for
    // no security gain: it is their own data.
    expect(del).not.toHaveBeenCalled()
  })

  it('purges the cache when a different user signs in on the same device', async () => {
    const del = stubCaches()
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))

    await beginSession(BOB.id)

    // Workbox keys by URL and never by identity: `GET /api/v1/escrow` has one
    // entry for the whole device, so BOB signing in offline would be served
    // ALICE's list. This is the only thing standing between them.
    expect(del).toHaveBeenCalledWith(READ_CACHE_NAME)
    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBe(String(BOB.id))
  })

  it('compares the marker as a string — 42 and \'42\' are the same person', async () => {
    const del = stubCaches()
    localStorage.setItem(LAST_USER_STORAGE_KEY, '42')

    await beginSession(42)

    expect(del).not.toHaveBeenCalled()
  })

  it('purges on a sign-in with no marker at all, rather than assume a fresh device', async () => {
    // An absent marker is ambiguous and this code cannot disambiguate it: it is
    // a brand-new device, where the purge costs nothing because there is nothing
    // cached — or a session predating Story 1.9, whose user filled
    // `escrow-api-cache` and expired without ever writing a marker. Reading it as
    // "fresh" would leave the entire upgrade window unguarded, which is the one
    // window where a device is certain to hold someone's cached responses.
    const del = stubCaches()

    await beginSession(ALICE.id)

    expect(del).toHaveBeenCalledWith(READ_CACHE_NAME)
    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBe(String(ALICE.id))
  })

  it('completes the sign-in even when the Cache API throws', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    globalThis.caches = {
      delete: vi.fn(async () => {
        throw new Error('cache API unavailable')
      }),
    } as unknown as CacheStorage
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))

    await expect(beginSession(BOB.id)).resolves.toBeUndefined()

    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBe(String(BOB.id))
  })

  it('empties the previous session\'s stores on the way IN as well as on the way out', async () => {
    // Belt to `endSession`'s braces, and not redundant with it: a read issued
    // just before a sign-out can land just after. `escrow.loadTransactions`
    // writes `this.transactions = await fetchTransactions()` with no session
    // guard, so ALICE's in-flight response re-populates the store *behind* the
    // teardown, and nothing after that would ever clear it — BOB would render
    // ALICE's transaction list for as long as his own fetch takes. Resetting at
    // the start of a session closes that window whatever happened at the end of
    // the last one. Without this test the two blocks are dead code to the suite
    // and the next person to simplify the file deletes them on a green run.
    stubCaches()
    const escrow = useEscrowStore()
    const evidence = useEvidenceStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'SHIPPED' }), auditLogs: [] }
    evidence.items = [anEvidenceItem({ id: 1, originalFilename: 'invoice.pdf' })]

    await beginSession(BOB.id)

    expect(escrow.transactions).toEqual([])
    expect(escrow.currentDetail).toBeNull()
    expect(evidence.items).toEqual([])
  })
})

describe('a shared device, from ALICE signing out to BOB signing in', () => {
  it('replays BOB\'s own entry and nothing else after ALICE signed out', async () => {
    stubCaches()
    await seedSharedDevice()
    signIn(ALICE)
    await endSession({ reason: 'logout' })

    // BOB signs in in the same tab — no reload, which is exactly the case the
    // old `window.location.href` logout papered over.
    const queue = useOfflineQueueStore()
    queue.isOnline = true
    await signInAndReplay(BOB)

    // One request, and it is his. It went out under his JWT because it is his
    // entry; ALICE's would have come back NOT_A_PARTY — permanent — and been
    // frozen out of her own reach for good.
    expect(vi.mocked(apiClient.request).mock.calls.map((c) => c[0].url)).toEqual([URL_OF.bobPending])
    // Accepted, so it is gone; ALICE's and the ownerless one went at her logout.
    expect(await storedIds()).toEqual([])
    expect(queue.queue).toEqual([])
  })

  it('replays only BOB\'s after ALICE expired, leaving hers on the device untouched', async () => {
    stubCaches()
    await seedSharedDevice()
    signIn(ALICE)
    await endSession({ reason: 'expired' })

    const queue = useOfflineQueueStore()
    queue.isOnline = true
    await signInAndReplay(BOB)

    // The scenario the ledger flagged: ALICE's session died, BOB signed in, and
    // her queued mutation used to go out under his token.
    expect(vi.mocked(apiClient.request).mock.calls.map((c) => c[0].url)).toEqual([URL_OF.bobPending])
    expect(await storedIds()).toEqual(['alice-frozen', 'alice-pending', 'orphan'])
  })

  it('shows BOB nothing of ALICE while offline, though her entries are still there', async () => {
    stubCaches()
    await seedSharedDevice()
    signIn(ALICE)
    await endSession({ reason: 'expired' })

    const queue = useOfflineQueueStore()
    queue.isOnline = false // offline, so nothing is replayed and the queue is only read
    await signInAndReplay(BOB)

    expect(queue.queue.map((item) => item.id)).toEqual(['bob-pending'])
    // ALICE's are untouched on the device and come back to her, not to him.
    expect(await storedIds()).toEqual(['alice-frozen', 'alice-pending', 'bob-pending', 'orphan'])
  })

  it('gives ALICE her own entries back — frozen one included — when she returns', async () => {
    stubCaches()
    await seedSharedDevice()
    const queue = useOfflineQueueStore()
    queue.isOnline = false

    await signInAndReplay(ALICE)

    expect(queue.queue.map((item) => item.id)).toEqual(['alice-pending', 'alice-frozen'])
    expect(queue.frozenCount).toBe(1)
  })

  it('never hydrates the ownerless entry, so nobody ever replays or sees it', async () => {
    stubCaches()
    await seedSharedDevice()
    const queue = useOfflineQueueStore()
    queue.isOnline = true

    await signInAndReplay(ALICE)

    // Story 4.5 decided nobody would be shown it; inventing an owner now would
    // reopen exactly the leak that decision closed.
    expect(queue.queue.map((item) => item.id)).not.toContain('orphan')
    expect(vi.mocked(apiClient.request).mock.calls.map((c) => c[0].url)).not.toContain(URL_OF.orphan)
    // Not resurrected, and not destroyed either — only an explicit logout does
    // that, once nobody can reach it any more.
    expect(await storedIds()).toContain('orphan')
  })
})

describe('starting up with no session at all', () => {
  it('binds the connectivity listeners but hydrates nothing and replays nothing', async () => {
    await seedSharedDevice()
    const queue = useOfflineQueueStore()
    queue.isOnline = true
    const getAllForUser = vi.spyOn(idb, 'getAllForUser')

    await queue.init()

    // The listeners are what makes the app react to reconnection for the rest of
    // its life; they are bound whether or not anybody is signed in.
    expect(queue.initialized).toBe(true)
    expect(queue.queue).toEqual([])
    expect(getAllForUser).not.toHaveBeenCalled()
    expect(apiClient.request).not.toHaveBeenCalled()
    // Nothing was destroyed either: the device's entries wait for their owners.
    expect(await storedIds()).toEqual(['alice-frozen', 'alice-pending', 'bob-pending', 'orphan'])
  })
})

describe('installSessionExpiryListener — back to sign-in, target kept', () => {
  /** A router reduced to what the listener actually uses. */
  function fakeRouter(fullPath: string, name = 'escrow-detail') {
    // Double PARTIEL, et le commentaire ci-dessus le dit déjà : le listener ne lit que
    // `currentRoute.value` et `replace`. Fabriquer les quarante membres de `Router`
    // n'ajouterait aucune garantie — seulement du décor à maintenir.
    return {
      currentRoute: { value: { name, fullPath } },
      replace: vi.fn(async () => undefined),
    } as unknown as Router
  }

  it('tears the session down and carries the target to the sign-in screen', async () => {
    const del = stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)
    const router = fakeRouter('/escrow/42')
    const stop = installSessionExpiryListener(router)

    window.dispatchEvent(new CustomEvent('escrow:session-expired'))
    // The handler is async: let it run to completion before asserting.
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())
    stop()

    expect(auth.token).toBeNull()
    expect(router.replace).toHaveBeenCalledWith({ name: 'auth', query: { redirect: '/escrow/42' } })
    // An expiry, not a logout: the device keeps what it held.
    expect(del).not.toHaveBeenCalled()
    expect(await storedIds()).toHaveLength(4)
  })

  it('carries no target when the user is already on the sign-in screen', async () => {
    stubCaches()
    signIn(ALICE)
    const router = fakeRouter('/auth?redirect=/escrow/42', 'auth')
    const stop = installSessionExpiryListener(router)

    window.dispatchEvent(new CustomEvent('escrow:session-expired'))
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())
    stop()

    // `auth?redirect=/auth` would be a small loop written into the URL.
    expect(router.replace).toHaveBeenCalledWith({ name: 'auth', query: {} })
  })

  it('carries no target from the dashboard, exactly as the router guard does not', async () => {
    // `router/index.js:50` deliberately omits `redirect` for `/`. Two places now
    // build this same URL, and a `redirect=/` written by one of them is the URL
    // the other says is not worth writing.
    stubCaches()
    signIn(ALICE)
    const router = fakeRouter('/', 'dashboard')
    const stop = installSessionExpiryListener(router)

    window.dispatchEvent(new CustomEvent('escrow:session-expired'))
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())
    stop()

    expect(router.replace).toHaveBeenCalledWith({ name: 'auth', query: {} })
  })

  it('stops listening once removed', async () => {
    stubCaches()
    signIn(ALICE)
    const router = fakeRouter('/escrow/42')
    installSessionExpiryListener(router)()

    window.dispatchEvent(new CustomEvent('escrow:session-expired'))
    await Promise.resolve()

    expect(router.replace).not.toHaveBeenCalled()
    expect(useAuthStore().token).not.toBeNull()
  })
})
