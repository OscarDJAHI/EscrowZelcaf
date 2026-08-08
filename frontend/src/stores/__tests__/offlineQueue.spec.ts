import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import * as idb from '@/stores/offlineQueue.idb'

const LEGACY_STORAGE_KEY = 'escrow_offline_queue'

// `TOKEN_STORAGE_KEY` and `resetSessionExpiryLatch` are re-exported because a
// whole-module factory drops everything it does not name, and `stores/auth` —
// reached from `stores/offlineQueue` since Story 1.9 scopes the queue by owner —
// imports the first, while `stores/session` imports the second.
vi.mock('@/api/client', () => ({
  default: { request: vi.fn() },
  TOKEN_STORAGE_KEY: 'escrow_token',
  resetSessionExpiryLatch: vi.fn(),
}))

/** The signed-in user every fixture below belongs to. */
const USER = { id: 42, email: 'alice@corp.example' }

/**
 * Stamps a queued request with its owner, exactly as `stores/escrow.js` does at
 * every real enqueue site.
 *
 * Load-bearing since Story 1.9: `hydrate()` and `flush()` only ever see the
 * signed-in user's entries, so an unstamped fixture is an *ownerless* entry —
 * which by design nobody hydrates, replays or is shown. Left out, the tests
 * below would pass vacuously by exercising nothing.
 */
function owned(request) {
  return { ...request, meta: { ...(request.meta ?? {}), userId: USER.id } }
}

/** Deterministic 3 MB payload — big enough that a lossy round-trip cannot hide. */
function makeBlob(sizeBytes = 3 * 1024 * 1024, type = 'image/jpeg') {
  const bytes = new Uint8Array(sizeBytes)
  for (let i = 0; i < sizeBytes; i += 1) bytes[i] = i % 256
  return new Blob([bytes], { type })
}

/**
 * Byte-level comparison — the whole point of this suite. A `toEqual` /
 * `toBeInstanceOf` check alone passes vacuously even when the bytes are
 * destroyed, so this reads both sides out and compares the raw buffers.
 *
 * Not asserting `toBeInstanceOf(Blob)` here: jsdom's FormData re-wraps an
 * appended Node Blob into a jsdom File, so the constructor legitimately differs
 * once a value has crossed FormData. The anti-vacuity guard is kept instead by
 * requiring a real `size` and a working `arrayBuffer()` — a Blob flattened to
 * `{}` by fake-indexeddb has neither. The IndexedDB-path tests below add an
 * explicit `toBeInstanceOf(Blob)` where it is meaningful.
 */
async function expectSameBytes(actual, expected) {
  expect(typeof actual?.arrayBuffer).toBe('function')
  expect(actual.size).toBe(expected.size)
  const [a, b] = [await actual.arrayBuffer(), await expected.arrayBuffer()]
  // Buffer.compare is O(n) native; expect(...).toEqual on a multi-MB typed
  // array builds a structural diff and takes minutes.
  expect(Buffer.compare(Buffer.from(a), Buffer.from(b))).toBe(0)
}

beforeEach(() => {
  // Fresh IndexedDB + fresh idb connection for every test, so no state leaks.
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  setActivePinia(createPinia())
  // A real session, both halves — and persisted, so the "simulate a reload"
  // tests below find it again through a brand-new Pinia. Without a signed-in
  // user the queue hydrates and replays nothing at all (Story 1.9).
  //
  // Written out rather than through `applySession`, which since Story 1.9 also
  // fires `beginSession()` → `adoptSession()`: that is the queue reading itself
  // back, i.e. the very thing these tests drive by hand, and letting it run in
  // the background would open the database and flush behind their backs.
  // `stores/__tests__/session.spec.js` is where that path is exercised.
  const auth = useAuthStore()
  auth.token = 'alice-token'
  auth.user = { ...USER }
  auth.persist()
  apiClient.request.mockReset()
  apiClient.request.mockResolvedValue({ data: {} })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('offlineQueue — enqueue with binary', () => {
  it('persists the entry to IndexedDB and returns it with an id', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    const blob = makeBlob()

    const item = await store.enqueue({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [blob],
      data: { comment: 'Colis endommagé à la livraison' },
    })

    expect(item.id).toBeTruthy()
    expect(store.pendingCount).toBe(1)

    const persisted = await idb.getAll()
    expect(persisted).toHaveLength(1)
    expect(persisted[0].files[0]).toBeInstanceOf(Blob)
    await expectSameBytes(persisted[0].files[0], blob)
  })

  it('rejects (and does not enqueue) when the IndexedDB write fails', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))

    await expect(
      store.enqueue({ method: 'post', url: '/api/v1/escrow/7/dispute', files: [makeBlob(1024)] }),
    ).rejects.toThrow('QuotaExceededError')

    expect(store.pendingCount).toBe(0)
  })
})

describe('offlineQueue — survives a reload', () => {
  it('rehydrates the Blob with its type and bytes intact', async () => {
    const blob = makeBlob(3 * 1024 * 1024, 'image/jpeg')

    const first = useOfflineQueueStore()
    first.isOnline = false
    await first.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [blob],
      data: { comment: 'Preuve hors-ligne' },
    }))

    // Simulate the reload: brand-new Pinia + a brand-new store instance,
    // reading back the same underlying IndexedDB.
    setActivePinia(createPinia())
    idb.resetDBForTests()
    const reloaded = useOfflineQueueStore()
    reloaded.isOnline = false
    await reloaded.init()

    expect(reloaded.pendingCount).toBe(1)
    const [entry] = reloaded.queue
    expect(entry.url).toBe('/api/v1/escrow/7/dispute')
    expect(entry.data.comment).toBe('Preuve hors-ligne')
    expect(entry.files[0]).toBeInstanceOf(Blob)
    expect(entry.files[0].type).toBe('image/jpeg')
    await expectSameBytes(entry.files[0], blob)
  })
})

describe('offlineQueue — non-regression for binary-less entries', () => {
  it('persists CREATE_TRANSACTION without a files field and replays it as JSON', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    const payload = { sellerEmail: 'seller@example.com', amount: 1500, currency: 'XOF' }

    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow',
      data: payload,
      meta: { type: 'CREATE_TRANSACTION' },
    }))

    const [persisted] = await idb.getAll()
    expect(persisted.files).toBeUndefined()
    expect(persisted.method).toBe('post')
    expect(persisted.url).toBe('/api/v1/escrow')
    expect(persisted.data).toEqual(payload)
    // `userId` alongside the type: `meta` still round-trips verbatim, and since
    // Story 1.9 a replayable entry is one that names its owner.
    expect(persisted.meta).toEqual({ type: 'CREATE_TRANSACTION', userId: USER.id })

    store.isOnline = true
    await store.flush()

    expect(apiClient.request).toHaveBeenCalledOnce()
    expect(apiClient.request).toHaveBeenCalledWith({
      method: 'post',
      url: '/api/v1/escrow',
      data: payload,
    })
    expect(store.pendingCount).toBe(0)
  })

  it('replays SEND_EVENT as JSON and keeps the entry queued on failure', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/9/event',
      data: { event: 'SHIP' },
      meta: { type: 'SEND_EVENT', transactionId: 9 },
    }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(new Error('network down'))
    store.isOnline = true
    await store.flush()

    expect(store.pendingCount).toBe(1)
    expect(await idb.getAll()).toHaveLength(1)
  })
})

describe('offlineQueue — multipart replay', () => {
  it('posts repeated `files` parts plus comment/clientCapturedAt and clears the entry', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    const a = makeBlob(2048, 'image/png')
    const b = makeBlob(4096, 'application/pdf')

    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [a, b],
      data: { comment: 'Deux preuves jointes', clientCapturedAt: '2026-07-17T06:00:00.000Z' },
    }))

    store.isOnline = true
    await store.flush()

    expect(apiClient.request).toHaveBeenCalledOnce()
    const config = apiClient.request.mock.calls[0][0]
    expect(config.method).toBe('post')
    expect(config.url).toBe('/api/v1/escrow/7/dispute')
    expect(config.headers).toEqual({ 'Content-Type': 'multipart/form-data' })
    expect(config.data).toBeInstanceOf(FormData)

    // Repeated key `files` — never `files[]`.
    const sent = config.data.getAll('files')
    expect(sent).toHaveLength(2)
    await expectSameBytes(sent[0], a)
    await expectSameBytes(sent[1], b)
    expect(config.data.get('comment')).toBe('Deux preuves jointes')
    expect(config.data.get('clientCapturedAt')).toBe('2026-07-17T06:00:00.000Z')
    expect(config.data.getAll('files[]')).toHaveLength(0)

    expect(store.pendingCount).toBe(0)
    expect(await idb.getAll()).toHaveLength(0)
  })

  it('keeps the entry — binary included — when the multipart replay fails', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    const blob = makeBlob(8192, 'image/jpeg')
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [blob],
      data: { comment: 'Preuve conservée' },
    }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(new Error('offline again'))
    store.isOnline = true
    await store.flush()

    expect(store.pendingCount).toBe(1)
    const [kept] = await idb.getAll()
    await expectSameBytes(kept.files[0], blob)
  })
})

describe('offlineQueue — localStorage migration', () => {
  it('imports legacy entries, removes the key and keeps them replayable', async () => {
    const legacy = [
      {
        id: '1000-aaa',
        timestamp: '2026-07-16T10:00:00.000Z',
        method: 'post',
        url: '/api/v1/escrow',
        data: { amount: 10 },
        meta: { type: 'CREATE_TRANSACTION', userId: USER.id },
      },
      {
        id: '1001-bbb',
        timestamp: '2026-07-16T11:00:00.000Z',
        method: 'post',
        url: '/api/v1/escrow/3/event',
        data: { event: 'SHIP' },
        meta: { type: 'SEND_EVENT', transactionId: 3, userId: USER.id },
      },
    ]
    localStorage.setItem(LEGACY_STORAGE_KEY, JSON.stringify(legacy))

    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()

    expect(store.pendingCount).toBe(2)
    expect(localStorage.getItem(LEGACY_STORAGE_KEY)).toBeNull()
    expect(store.queue.map((i) => i.id)).toEqual(['1000-aaa', '1001-bbb'])
    expect(await idb.getAll()).toHaveLength(2)
  })

  it('drops a corrupt payload without crashing and empties the key', async () => {
    localStorage.setItem(LEGACY_STORAGE_KEY, '{ this is not: valid JSON')

    const store = useOfflineQueueStore()
    store.isOnline = false
    await expect(store.init()).resolves.toBeUndefined()

    expect(store.pendingCount).toBe(0)
    expect(localStorage.getItem(LEGACY_STORAGE_KEY)).toBeNull()
  })
})

describe('offlineQueue — failures on the newly-async paths', () => {
  it('does not replay an already-accepted request when dropping the entry fails', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow', data: { amount: 10 } }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.spyOn(idb, 'remove').mockRejectedValueOnce(new Error('IDB delete failed'))

    store.isOnline = true
    await store.flush()
    // The POST succeeded; a bookkeeping failure must not re-send it.
    await store.flush()

    expect(apiClient.request).toHaveBeenCalledOnce()
    expect(store.pendingCount).toBe(0)
  })

  it('leaves the queue retryable — never permanently empty — when hydration fails', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const getAll = vi.spyOn(idb, 'getAllForUser').mockRejectedValueOnce(new Error('IDB unavailable'))

    // init() is called un-awaited from main.js: it must never reject.
    await expect(store.init()).resolves.toBeUndefined()
    expect(store.hydrated).toBe(false)

    // ...and the failure must not have latched: a later init() retries.
    getAll.mockRestore()
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow', data: {} }))
    await store.init()

    expect(store.hydrated).toBe(true)
    expect(store.pendingCount).toBe(1)
  })

  it('keeps an entry queued during hydration instead of clobbering it', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    // Entry that lands after getAll() snapshotted an empty store — the window
    // that exists because main.js does not await init().
    vi.spyOn(idb, 'getAllForUser').mockImplementationOnce(async () => {
      await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow/1/event', data: { event: 'SHIP' } }))
      return []
    })

    await store.hydrate()

    expect(store.pendingCount).toBe(1)
  })

  it('does not memoise a failed database open', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    // A transient open failure must not disable queuing for the whole session.
    const broken = new IDBFactory()
    broken.open = () => {
      throw new Error('IndexedDB blocked')
    }
    globalThis.indexedDB = broken
    await expect(store.enqueue({ method: 'post', url: '/api/v1/escrow', data: {} })).rejects.toThrow()

    globalThis.indexedDB = new IDBFactory()
    await expect(
      store.enqueue({ method: 'post', url: '/api/v1/escrow', data: {} }),
    ).resolves.toBeTruthy()
  })

  it('survives a localStorage accessor that throws (cookies blocked)', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('access denied', 'SecurityError')
    })

    await expect(store.init()).resolves.toBeUndefined()
    expect(store.hydrated).toBe(true)
  })
})

/**
 * Shapes an AxiosError the way `client.js` relays it — the two pre-existing
 * simulated failures are bare network errors with no `response` at all, so this
 * is the first HTTP error envelope this suite mocks.
 */
function httpError(status, data) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data },
  })
}

describe('offlineQueue — reconciling on a permanent rejection', () => {
  it('freezes the entry with its reason, keeps its binary, and dispatches escrow:sync', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    const a = makeBlob(4096, 'image/jpeg')
    const b = makeBlob(2048, 'image/png')
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [a, b],
      data: { comment: 'Litige déposé hors ligne' },
      meta: { type: 'OPEN_DISPUTE', transactionId: 7 },
    }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(
      httpError(409, { code: 'DISPUTE_ALREADY_RESOLVED', message: 'Le litige a déjà été arbitré' }),
    )
    const listener = vi.fn()
    window.addEventListener('escrow:sync', listener)
    store.isOnline = true
    await store.flush()
    window.removeEventListener('escrow:sync', listener)

    expect(store.pendingCount).toBe(0)
    expect(store.frozenCount).toBe(1)
    // Nothing synced, yet the refetch must still run: that dispatch is what
    // overwrites the optimistic DISPUTED with the server's truth.
    expect(listener).toHaveBeenCalledOnce()

    // Read back from IndexedDB — not from the in-memory object — so the assertion
    // proves the freeze survived the round-trip with its bytes.
    const persisted = await idb.getAll()
    expect(persisted).toHaveLength(1)
    const [kept] = persisted
    expect(kept.frozen).toBe(true)
    expect(kept.failure.code).toBe('DISPUTE_ALREADY_RESOLVED')
    expect(kept.failure.status).toBe(409)
    expect(kept.failure.message).toBe('Le litige a déjà été arbitré')
    expect(kept.failure.at).toEqual(expect.any(String))
    expect(kept.files[0]).toBeInstanceOf(Blob)
    await expectSameBytes(kept.files[0], a)
    await expectSameBytes(kept.files[1], b)
  })

  it('never retries a frozen entry — neither on a later flush nor after a reload', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [makeBlob(1024, 'image/jpeg')],
      data: { comment: 'Litige déposé hors ligne' },
    }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(httpError(409, { code: 'DISPUTE_ALREADY_RESOLVED' }))
    store.isOnline = true
    await store.flush()
    expect(apiClient.request).toHaveBeenCalledOnce()

    await store.flush()
    // Still one: the freeze bounds the auto-retry within the session...
    expect(apiClient.request).toHaveBeenCalledOnce()

    // ...and survives the reload, which is the whole point of persisting it.
    setActivePinia(createPinia())
    idb.resetDBForTests()
    const reloaded = useOfflineQueueStore()
    reloaded.isOnline = true
    await reloaded.init()

    await reloaded.flush()

    expect(apiClient.request).toHaveBeenCalledOnce()
    expect(reloaded.pendingCount).toBe(0)
    expect(reloaded.frozenCount).toBe(1)
    expect(reloaded.frozenEntries[0].failure.code).toBe('DISPUTE_ALREADY_RESOLVED')
  })

  it('does not block the entries behind it: the freeze bounds the retry, not the queue', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [makeBlob(1024, 'image/jpeg')],
      data: { comment: 'Litige déposé hors ligne' },
    }))
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/9/event',
      data: { event: 'SHIP' },
      meta: { type: 'SEND_EVENT', transactionId: 9 },
    }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(httpError(409, { code: 'DISPUTE_ALREADY_RESOLVED' }))
    store.isOnline = true
    await store.flush()

    // The second entry went through and was dropped; only the frozen one is left.
    expect(apiClient.request).toHaveBeenCalledTimes(2)
    expect(apiClient.request.mock.calls[1][0].url).toBe('/api/v1/escrow/9/event')
    expect(store.pendingCount).toBe(0)
    expect(store.frozenCount).toBe(1)
    expect(await idb.getAll()).toHaveLength(1)
  })

  it('freezes a 4xx carrying no code, with a null reason rather than a guess', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow/404/event', data: { event: 'SHIP' } }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(httpError(404, ''))
    store.isOnline = true
    await store.flush()

    const [kept] = await idb.getAll()
    expect(kept.frozen).toBe(true)
    expect(kept.failure.code).toBeNull()
    expect(kept.failure.status).toBe(404)
  })

  it('keeps the freeze for the session when persisting it fails', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow/7/event', data: { event: 'SHIP' } }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))
    apiClient.request.mockRejectedValueOnce(httpError(400, { code: 'ILLEGAL_TRANSITION' }))
    store.isOnline = true
    await store.flush()

    // A storage hiccup must not turn back into a sync failure that re-corks the queue.
    expect(store.frozenCount).toBe(1)
    expect(store.pendingCount).toBe(0)
  })
})

describe('offlineQueue — transient rejections stay queued', () => {
  it('does not freeze a coded transient conflict (409 CONCURRENT_MODIFICATION)', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow/7/event', data: { event: 'SHIP' } }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(httpError(409, { code: 'CONCURRENT_MODIFICATION' }))
    const listener = vi.fn()
    window.addEventListener('escrow:sync', listener)
    store.isOnline = true
    await store.flush()
    window.removeEventListener('escrow:sync', listener)

    expect(store.pendingCount).toBe(1)
    expect(store.frozenCount).toBe(0)
    expect(listener).not.toHaveBeenCalled()

    // Retried on the next flush, and accepted this time.
    await store.flush()
    expect(apiClient.request).toHaveBeenCalledTimes(2)
    expect(store.pendingCount).toBe(0)
  })

  it('does not freeze FILE_READ_ERROR even though it arrives as a 400', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    const blob = makeBlob(2048, 'image/jpeg')
    await store.enqueue(owned({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [blob],
      data: { comment: 'Preuve à re-transférer' },
    }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(httpError(400, { code: 'FILE_READ_ERROR' }))
    store.isOnline = true
    await store.flush()

    expect(store.frozenCount).toBe(0)
    expect(store.pendingCount).toBe(1)
    const [kept] = await idb.getAll()
    expect(kept.frozen).toBeUndefined()
    await expectSameBytes(kept.files[0], blob)
  })

  it('does not freeze an expired session (401): the replay is valid once re-authenticated', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow/7/event', data: { event: 'SHIP' } }))

    vi.spyOn(console, 'error').mockImplementation(() => {})
    apiClient.request.mockRejectedValueOnce(httpError(401, ''))
    store.isOnline = true
    await store.flush()

    expect(store.frozenCount).toBe(0)
    expect(store.pendingCount).toBe(1)
  })
})

describe('offlineQueue — replay order', () => {
  it('keeps FIFO for entries sharing a timestamp, whatever their key order', async () => {
    // Two entries queued in the same millisecond: `timestamp` ties, so only the
    // monotonic `seq` can order them. Written straight to IndexedDB (rather than
    // via fake timers, which deadlock fake-indexeddb's internal scheduling) with
    // ids whose random suffix sorts opposite to the enqueue order — so a sort
    // that fell back to key order would visibly invert them.
    const sameMs = '2026-07-17T08:00:00.000Z'
    await idb.put({ id: '1000-zzz', timestamp: sameMs, seq: 1, method: 'post', url: '/api/v1/escrow/1/event', data: { event: 'SHIP' } })
    await idb.put({ id: '1000-aaa', timestamp: sameMs, seq: 2, method: 'post', url: '/api/v1/escrow/2/event', data: { event: 'DELIVER' } })

    const persisted = await idb.getAll()

    expect(persisted.map((i) => i.url)).toEqual(['/api/v1/escrow/1/event', '/api/v1/escrow/2/event'])
  })

  it('assigns a monotonically increasing seq to each entry', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false

    const first = await store.enqueue({ method: 'post', url: '/api/v1/escrow/1/event', data: {} })
    const second = await store.enqueue({ method: 'post', url: '/api/v1/escrow/2/event', data: {} })

    expect(second.seq).toBeGreaterThan(first.seq)
  })

  it('flushes entries FIFO, in the order they were queued', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false

    for (const url of ['/api/v1/escrow/1/event', '/api/v1/escrow/2/event', '/api/v1/escrow/3/event']) {
      // eslint-disable-next-line no-await-in-loop
      await store.enqueue(owned({ method: 'post', url, data: { event: 'SHIP' } }))
    }

    // Hydrating from IndexedDB must preserve that order too, not just the
    // in-memory push order.
    setActivePinia(createPinia())
    idb.resetDBForTests()
    const reloaded = useOfflineQueueStore()
    reloaded.isOnline = false
    await reloaded.init()

    reloaded.isOnline = true
    await reloaded.flush()

    expect(apiClient.request.mock.calls.map((c) => c[0].url)).toEqual([
      '/api/v1/escrow/1/event',
      '/api/v1/escrow/2/event',
      '/api/v1/escrow/3/event',
    ])
    expect(reloaded.pendingCount).toBe(0)
  })

  it('dispatches escrow:sync once anything synced', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow', data: {} }))

    const listener = vi.fn()
    window.addEventListener('escrow:sync', listener)
    store.isOnline = true
    await store.flush()
    window.removeEventListener('escrow:sync', listener)

    expect(listener).toHaveBeenCalledOnce()
  })
})

describe('offlineQueue — the queue is device-global, the session is not (Story 1.9)', () => {
  const STRANGER = { id: 7, email: 'bob@corp.example' }

  /** Writes an entry straight to IndexedDB, as a previous session left it. */
  function persisted({ id, userId, seq }) {
    return {
      id,
      timestamp: `2026-01-01T00:00:0${seq}.000Z`,
      seq,
      method: 'post',
      url: `/api/v1/escrow/${seq}/event`,
      data: { event: 'SHIP' },
      meta: { type: 'SEND_EVENT', transactionId: seq, ...(userId === null ? {} : { userId }) },
    }
  }

  async function seedThreeOwners() {
    await idb.put(persisted({ id: 'mine', userId: USER.id, seq: 1 }))
    await idb.put(persisted({ id: 'theirs', userId: STRANGER.id, seq: 2 }))
    await idb.put(persisted({ id: 'ownerless', userId: null, seq: 3 }))
  }

  it('hydrates the signed-in user\'s entries only, leaving the others on the device', async () => {
    await seedThreeOwners()
    const store = useOfflineQueueStore()
    store.isOnline = false

    await store.init()

    expect(store.queue.map((item) => item.id)).toEqual(['mine'])
    // Not hydrated is not deleted: they belong to their owners and come back to
    // them, not to whoever happens to be signed in now.
    expect((await idb.getAll()).map((item) => item.id).sort()).toEqual(['mine', 'ownerless', 'theirs'])
  })

  it('replays the signed-in user\'s entries only — never a stranger\'s under their JWT', async () => {
    // The failure this closes: `client.js` attaches whatever token is current at
    // *send* time, so a stranger's replay earns NOT_A_PARTY, which is permanent,
    // which freezes their evidence beyond their own reach.
    await seedThreeOwners()
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()

    store.isOnline = true
    await store.flush()

    const sent = apiClient.request.mock.calls.map((c) => c[0].url)
    expect(sent).toEqual(['/api/v1/escrow/1/event'])
    expect(sent).not.toContain('/api/v1/escrow/2/event')
    expect(sent).not.toContain('/api/v1/escrow/3/event')
  })

  it('replays nothing when the in-memory queue holds only another user\'s entries', async () => {
    // A queue that reached memory some other way (a hydrate that predates the
    // sign-out) must not be sent either: the filter is on the replay, not only
    // on the read.
    const store = useOfflineQueueStore()
    store.queue = [persisted({ id: 'theirs', userId: STRANGER.id, seq: 2 })]
    store.isOnline = true

    await store.flush()

    expect(apiClient.request).not.toHaveBeenCalled()
    // And the entry is still there: not replayed is not discarded.
    expect(store.queue).toHaveLength(1)
  })

  it('binds the connectivity listeners without hydrating when nobody is signed in', async () => {
    await seedThreeOwners()
    const auth = useAuthStore()
    auth.token = null
    auth.user = null
    auth.persist()
    const store = useOfflineQueueStore()
    store.isOnline = true
    const getAllForUser = vi.spyOn(idb, 'getAllForUser')

    await store.init()

    // Booting on the sign-in screen: the listeners are what makes the app react
    // to reconnection later, so they go up regardless...
    expect(store.initialized).toBe(true)
    // ...but there is nobody to read the queue *for*, and reading it anyway is
    // what used to replay the previous user's mutations under the next one.
    expect(getAllForUser).not.toHaveBeenCalled()
    expect(store.queue).toEqual([])
    expect(apiClient.request).not.toHaveBeenCalled()
    expect((await idb.getAll())).toHaveLength(3)
  })

  it('leaves the queue alone when hydrate() runs with no session', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.enqueue(owned({ method: 'post', url: '/api/v1/escrow', data: {} }))
    const auth = useAuthStore()
    auth.token = null
    auth.user = null

    await store.hydrate()

    // An entry queued during this very session is legitimately in memory and is
    // not hydrate()'s to drop.
    expect(store.pendingCount).toBe(1)
  })

  it('clearMemory() empties this session\'s view and keeps IndexedDB intact', async () => {
    await seedThreeOwners()
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()
    expect(store.queue).toHaveLength(1)

    store.clearMemory()

    expect(store.queue).toEqual([])
    expect(store.hydrated).toBe(false)
    // The listeners stay bound: it is the same tab, and re-binding them per
    // session would stack duplicates.
    expect(store.initialized).toBe(true)
    expect(await idb.getAll()).toHaveLength(3)
  })

  it('adoptSession() records that it read the queue, so nothing reads it twice', async () => {
    // `clearMemory()` lowered the flag on the way out and `adoptSession()` is the
    // read that answers it. Left false, the flag would claim the queue is unread
    // while it is loaded: `RecoveryView` renders its spinner and calls `init()`
    // for a second, redundant trip to IndexedDB (`RecoveryView.vue:78-81`).
    await seedThreeOwners()
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()
    store.clearMemory()
    expect(store.hydrated).toBe(false)

    await store.adoptSession()

    expect(store.hydrated).toBe(true)
    expect(store.queue.map((item) => item.id)).toEqual(['mine'])
  })

  it('leaves hydrated false when adoptSession() cannot read the queue', async () => {
    // The production sequence, and not a fresh store: booting on the sign-in
    // screen raises `hydrated` (that restore ran to completion, it just had
    // nobody to read for), so a failing adoption has to *lower* it — raising it
    // afterwards is not enough. Left true, the app reports "queue read, and it
    // is empty" while the user's only copy of their evidence sits in IndexedDB:
    // `RecoveryView` says "Nothing to recover" and its `if (!queue.hydrated)`
    // guard never calls `init()` again.
    const auth = useAuthStore()
    auth.token = null
    auth.user = null
    auth.persist()
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()
    expect(store.hydrated).toBe(true) // the state the failure has to undo

    auth.applySession({ token: 'alice-token', user: { ...USER } })
    vi.spyOn(idb, 'getAllForUser').mockRejectedValueOnce(new Error('IndexedDB unavailable'))
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await store.adoptSession()

    expect(store.hydrated).toBe(false)
  })

  it('stops replaying mid-run when the session changes under it', async () => {
    // The cross-user replay reached through *time* rather than through the
    // queue: `flush()` awaits one upload per entry, so a whole sign-out and
    // sign-in fits inside the loop. `pending` was resolved against whoever
    // started the run, and carrying on would send the rest of their entries
    // under the next person's JWT — permanent NOT_A_PARTY, evidence frozen out
    // of its owner's reach.
    await idb.put(persisted({ id: 'first', userId: USER.id, seq: 1 }))
    await idb.put(persisted({ id: 'second', userId: USER.id, seq: 2 }))
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()
    expect(store.queue).toHaveLength(2)

    const auth = useAuthStore()
    // The first upload "takes a while", and the device changes hands during it.
    apiClient.request.mockImplementationOnce(async () => {
      auth.token = 'bob-token'
      auth.user = { ...STRANGER }
      auth.persist()
      return { data: {} }
    })

    const synced = vi.fn()
    window.addEventListener('escrow:sync', synced)
    store.isOnline = true
    await store.flush()
    window.removeEventListener('escrow:sync', synced)

    expect(apiClient.request.mock.calls.map((c) => c[0].url)).toEqual(['/api/v1/escrow/1/event'])
    // The second entry is untouched — not sent, not frozen, still its owner's.
    const left = await idb.getAll()
    expect(left.map((item) => item.id)).toEqual(['second'])
    expect(left[0].frozen).toBeUndefined()
    // And the run still announces what it DID send: the first entry really was
    // accepted, so the optimistic display is now stale and the wired refetch
    // hangs off this event and off nothing else. Cutting a run short must not
    // cost the screen its reconciliation.
    expect(synced).toHaveBeenCalledOnce()
  })

  it('does not write an entry back into IndexedDB after a logout deleted it', async () => {
    // The ownership check at the top of the iteration guards the *next* entry;
    // this guards the one already in flight. A multipart upload takes minutes,
    // and a sign-out inside that window has already deleted this entry — so the
    // freeze that follows a permanent rejection would `put` it straight back,
    // Blobs and comment included, onto the device that was just handed back.
    vi.spyOn(console, 'error').mockImplementation(() => {})
    await idb.put(persisted({ id: 'in-flight', userId: USER.id, seq: 1 }))
    const store = useOfflineQueueStore()
    store.isOnline = false
    await store.init()
    expect(store.queue).toHaveLength(1)

    const auth = useAuthStore()
    // The device changes hands mid-upload — exactly what `endSession` does — and
    // only then does the server give its final word on the request.
    apiClient.request.mockImplementationOnce(async () => {
      auth.clearSession()
      await idb.clearForUser(USER.id)
      throw httpError(400, { code: 'ILLEGAL_TRANSITION', message: 'already shipped' })
    })

    store.isOnline = true
    await store.flush()

    expect(await idb.getAll()).toEqual([])
  })

  it('releases the replay lock when the loop itself throws, so the queue is not stuck forever', async () => {
    // `flushing` is a lock. Left raised, every later `flush()` — every `online`
    // event, every `adoptSession()` — returns immediately on the stale flag, and
    // the queue stops replaying for the life of the tab without saying so.
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const store = useOfflineQueueStore()

    // The throw comes from the loop's own scaffolding — the ownership
    // re-check *between* two entries — and not from a request, so the per-entry
    // `try` never sees it. An owner that reads once and then fails is a
    // stand-in: what matters is that something outside the inner `try` can
    // throw at all, not this particular way of making it happen.
    let reads = 0
    const brittleOwner = {
      toString() {
        reads += 1
        if (reads > 1) throw new Error('unreadable owner')
        return String(USER.id)
      },
    }
    store.queue = [
      persisted({ id: 'first', userId: USER.id, seq: 1 }),
      { ...persisted({ id: 'second', seq: 2 }), meta: { type: 'SEND_EVENT', userId: brittleOwner } },
    ]

    const synced = vi.fn()
    window.addEventListener('escrow:sync', synced)
    store.isOnline = true
    await expect(store.flush()).rejects.toThrow('unreadable owner')
    window.removeEventListener('escrow:sync', synced)

    // Without the `finally` the lock stays raised and no later flush ever runs.
    expect(store.flushing).toBe(false)
    // And `escrow:sync` is in that same `finally` for the same reason — asserted
    // HERE and not on the `break` path, which is the distinction the placement
    // turns on: a `break` leaves the loop normally and reaches anything written
    // after the block, a `throw` does not. The first entry was accepted before
    // this run blew up, so the optimistic display is stale; skipping the event
    // leaves the screen showing a pending entry the server already holds, with
    // nothing else ever telling it to refetch.
    expect(synced).toHaveBeenCalledOnce()
  })
})
