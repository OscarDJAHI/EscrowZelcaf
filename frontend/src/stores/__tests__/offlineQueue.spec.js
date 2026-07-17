import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient from '@/api/client'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import * as idb from '@/stores/offlineQueue.idb'

const LEGACY_STORAGE_KEY = 'escrow_offline_queue'

vi.mock('@/api/client', () => ({
  default: { request: vi.fn() },
}))

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
    await first.enqueue({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [blob],
      data: { comment: 'Preuve hors-ligne' },
    })

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

    await store.enqueue({
      method: 'post',
      url: '/api/v1/escrow',
      data: payload,
      meta: { type: 'CREATE_TRANSACTION' },
    })

    const [persisted] = await idb.getAll()
    expect(persisted.files).toBeUndefined()
    expect(persisted.method).toBe('post')
    expect(persisted.url).toBe('/api/v1/escrow')
    expect(persisted.data).toEqual(payload)
    expect(persisted.meta).toEqual({ type: 'CREATE_TRANSACTION' })

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
    await store.enqueue({
      method: 'post',
      url: '/api/v1/escrow/9/event',
      data: { event: 'SHIP' },
      meta: { type: 'SEND_EVENT', transactionId: 9 },
    })

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

    await store.enqueue({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [a, b],
      data: { comment: 'Deux preuves jointes', clientCapturedAt: '2026-07-17T06:00:00.000Z' },
    })

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
    await store.enqueue({
      method: 'post',
      url: '/api/v1/escrow/7/dispute',
      files: [blob],
      data: { comment: 'Preuve conservée' },
    })

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
        meta: { type: 'CREATE_TRANSACTION' },
      },
      {
        id: '1001-bbb',
        timestamp: '2026-07-16T11:00:00.000Z',
        method: 'post',
        url: '/api/v1/escrow/3/event',
        data: { event: 'SHIP' },
        meta: { type: 'SEND_EVENT', transactionId: 3 },
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
    await store.enqueue({ method: 'post', url: '/api/v1/escrow', data: { amount: 10 } })

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
    const getAll = vi.spyOn(idb, 'getAll').mockRejectedValueOnce(new Error('IDB unavailable'))

    // init() is called un-awaited from main.js: it must never reject.
    await expect(store.init()).resolves.toBeUndefined()
    expect(store.hydrated).toBe(false)

    // ...and the failure must not have latched: a later init() retries.
    getAll.mockRestore()
    await store.enqueue({ method: 'post', url: '/api/v1/escrow', data: {} })
    await store.init()

    expect(store.hydrated).toBe(true)
    expect(store.pendingCount).toBe(1)
  })

  it('keeps an entry queued during hydration instead of clobbering it', async () => {
    const store = useOfflineQueueStore()
    store.isOnline = false
    // Entry that lands after getAll() snapshotted an empty store — the window
    // that exists because main.js does not await init().
    vi.spyOn(idb, 'getAll').mockImplementationOnce(async () => {
      await store.enqueue({ method: 'post', url: '/api/v1/escrow/1/event', data: { event: 'SHIP' } })
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
      await store.enqueue({ method: 'post', url, data: { event: 'SHIP' } })
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
    await store.enqueue({ method: 'post', url: '/api/v1/escrow', data: {} })

    const listener = vi.fn()
    window.addEventListener('escrow:sync', listener)
    store.isOnline = true
    await store.flush()
    window.removeEventListener('escrow:sync', listener)

    expect(listener).toHaveBeenCalledOnce()
  })
})
