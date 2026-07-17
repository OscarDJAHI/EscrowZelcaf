import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient from '@/api/client'
import { openDispute as openDisputeApi } from '@/api/escrow'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import * as idb from '@/stores/offlineQueue.idb'

vi.mock('@/api/client', () => ({ default: { request: vi.fn() } }))
vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

const payload = { sellerEmail: 'seller@example.com', amount: 1500, currency: 'XOF' }

/** Deterministic payload — see `offlineQueue.spec.js`, same rationale. */
function makeBlob(sizeBytes = 2048, type = 'image/jpeg') {
  const bytes = new Uint8Array(sizeBytes)
  for (let i = 0; i < sizeBytes; i += 1) bytes[i] = i % 256
  return new Blob([bytes], { type })
}

/**
 * Byte-level comparison: a Blob flattened to `{}` by fake-indexeddb still
 * satisfies `toEqual`, so the bytes themselves have to be read back out.
 * No `toBeInstanceOf(Blob)` after a FormData round-trip — jsdom's FormData
 * re-wraps an appended Node Blob into a jsdom File.
 */
async function expectSameBytes(actual, expected) {
  expect(typeof actual?.arrayBuffer).toBe('function')
  expect(actual.size).toBe(expected.size)
  const [a, b] = [await actual.arrayBuffer(), await expected.arrayBuffer()]
  expect(Buffer.compare(Buffer.from(a), Buffer.from(b))).toBe(0)
}

/** Detail shape the dispute paths read: a transaction plus its audit logs. */
function detailFor(id, state = 'FUNDS_LOCKED') {
  return { transaction: { id, state }, auditLogs: [] }
}

beforeEach(() => {
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

describe('escrow store — offline enqueue is now able to fail', () => {
  it('shows the optimistic card once the offline create is safely queued', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false

    const optimistic = await escrow.createNewTransaction(payload)

    expect(optimistic._queuedOffline).toBe(true)
    expect(escrow.transactions).toHaveLength(1)
    expect(await idb.getAll()).toHaveLength(1)
  })

  it('takes the optimistic card back when the entry could not be persisted', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))

    await expect(escrow.createNewTransaction(payload)).rejects.toThrow('QuotaExceededError')

    // Nothing queued means nothing will ever sync: a card left on screen would
    // promise the user a transaction that does not exist anywhere.
    expect(escrow.transactions).toHaveLength(0)
    expect(await idb.getAll()).toHaveLength(0)
  })

  it('propagates the failure of an offline event instead of flagging it as queued', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    escrow.currentDetail = { transaction: { id: 9, state: 'PAID' }, evidence: [] }
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))

    await expect(escrow.sendTransactionEvent(9, 'SHIP')).rejects.toThrow('QuotaExceededError')

    expect(escrow.currentDetail.transaction._queuedEvent).toBeUndefined()
  })
})

describe('escrow store — opening a dispute offline', () => {
  it('queues the opening and all its files as one single entry', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)
    const [a, b] = [makeBlob(2048, 'image/png'), makeBlob(4096, 'application/pdf')]

    const result = await escrow.openDispute(7, { files: [a, b], comment: 'Colis endommagé' })

    expect(result).toBeNull()
    // One entry, never one "dispute" entry plus one "file" entry: the replay has
    // to file the whole dispute or nothing.
    expect(queue.pendingCount).toBe(1)
    const [entry] = await idb.getAll()
    expect(entry.method).toBe('post')
    expect(entry.url).toBe('/api/v1/escrow/7/dispute')
    expect(entry.meta).toEqual({ type: 'OPEN_DISPUTE', transactionId: 7 })
    expect(entry.data.comment).toBe('Colis endommagé')
    expect(entry.data.clientCapturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/)
    // `files` at the root — that is where `buildFormData()` looks for them.
    expect(entry.files).toHaveLength(2)
    await expectSameBytes(entry.files[0], a)
    await expectSameBytes(entry.files[1], b)
    expect(openDisputeApi).not.toHaveBeenCalled()
  })

  it('flips the detail and its dashboard row to DISPUTED optimistically', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [{ id: 7, state: 'FUNDS_LOCKED' }, { id: 8, state: 'FUNDS_LOCKED' }]

    await escrow.openDispute(7, { files: [makeBlob(512)], comment: 'Article cassé' })

    expect(escrow.currentDetail.transaction.state).toBe('DISPUTED')
    expect(escrow.currentDetail.transaction._queuedDispute).toBe(true)
    expect(escrow.transactions[0]).toMatchObject({ state: 'DISPUTED', _queuedDispute: true })
    // The other transaction must not be dragged along.
    expect(escrow.transactions[1].state).toBe('FUNDS_LOCKED')
  })

  it('flips the rows the route actually addresses, whose ids are numbers not strings', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [{ id: 7, state: 'FUNDS_LOCKED' }]

    // What production really passes: `TransactionDetailView` declares its route
    // prop as a String and hands it down, while the API sends numeric ids.
    await escrow.openDispute('7', { files: [makeBlob(512)], comment: 'Id de route' })

    expect(escrow.currentDetail.transaction._queuedDispute).toBe(true)
    expect(escrow.transactions[0]._queuedDispute).toBe(true)
  })

  it('refuses to queue an opening with no file rather than jam the queue', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)

    // `flush()` dispatches on `files.length`, so a fileless entry would be
    // replayed as JSON against a multipart-only endpoint: a 400 that no retry
    // clears, sitting at the head of the queue and blocking everything behind.
    await expect(escrow.openDispute(7, { files: [], comment: 'Sans pièce' })).rejects.toThrow(
      /at least one evidence file/,
    )

    expect(queue.pendingCount).toBe(0)
    expect(escrow.currentDetail.transaction.state).toBe('FUNDS_LOCKED')
  })

  it('shows nothing optimistic when the entry could not be persisted', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [{ id: 7, state: 'FUNDS_LOCKED' }]
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))

    await expect(
      escrow.openDispute(7, { files: [makeBlob(512)], comment: 'Jamais mis en file' }),
    ).rejects.toThrow('QuotaExceededError')

    // Nothing queued means nothing will ever sync: a DISPUTED badge here would
    // promise a dispute that exists nowhere.
    expect(escrow.currentDetail.transaction.state).toBe('FUNDS_LOCKED')
    expect(escrow.currentDetail.transaction._queuedDispute).toBeUndefined()
    expect(escrow.transactions[0]._queuedDispute).toBeUndefined()
    expect(queue.pendingCount).toBe(0)
  })

  it('replays the queued dispute as one multipart POST with its bytes intact', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)
    const [a, b] = [makeBlob(2048, 'image/png'), makeBlob(4096, 'application/pdf')]

    await escrow.openDispute(7, { files: [a, b], comment: 'Deux preuves jointes' })
    const { clientCapturedAt } = (await idb.getAll())[0].data
    const synced = vi.fn()
    window.addEventListener('escrow:sync', synced)

    queue.isOnline = true
    await queue.flush()
    window.removeEventListener('escrow:sync', synced)

    // The entry this story produces is really consumable by the 4.1 replay.
    expect(apiClient.request).toHaveBeenCalledOnce()
    const config = apiClient.request.mock.calls[0][0]
    expect(config.method).toBe('post')
    expect(config.url).toBe('/api/v1/escrow/7/dispute')
    expect(config.headers).toEqual({ 'Content-Type': 'multipart/form-data' })
    const sent = config.data.getAll('files')
    expect(sent).toHaveLength(2)
    await expectSameBytes(sent[0], a)
    await expectSameBytes(sent[1], b)
    expect(config.data.get('comment')).toBe('Deux preuves jointes')
    expect(config.data.get('clientCapturedAt')).toBe(clientCapturedAt)
    expect(queue.pendingCount).toBe(0)
    expect(await idb.getAll()).toHaveLength(0)
    // The views refetch on this event — it is what clears `_queuedDispute` and
    // replaces the optimistic state with the server's own.
    expect(synced).toHaveBeenCalledOnce()
  })

  it('rehydrates a queued dispute after a reload and keeps it replayable', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    escrow.currentDetail = detailFor(7)
    const blob = makeBlob(4096, 'image/jpeg')

    await escrow.openDispute(7, { files: [blob], comment: 'Preuve hors-ligne' })

    // Simulate the PWA reload: brand-new Pinia reading back the same IndexedDB.
    setActivePinia(createPinia())
    idb.resetDBForTests()
    const reloaded = useOfflineQueueStore()
    reloaded.isOnline = false
    await reloaded.init()

    expect(reloaded.pendingCount).toBe(1)
    const [entry] = reloaded.queue
    expect(entry.meta).toEqual({ type: 'OPEN_DISPUTE', transactionId: 7 })
    expect(entry.files[0]).toBeInstanceOf(Blob)
    await expectSameBytes(entry.files[0], blob)

    reloaded.isOnline = true
    await reloaded.flush()

    expect(apiClient.request).toHaveBeenCalledOnce()
    await expectSameBytes(apiClient.request.mock.calls[0][0].data.getAll('files')[0], blob)
  })
})

describe('escrow store — opening a dispute online is unchanged', () => {
  it('posts directly and replaces the state with the returned transaction', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = true
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [{ id: 7, state: 'FUNDS_LOCKED' }]
    const disputed = { id: 7, state: 'DISPUTED' }
    openDisputeApi.mockResolvedValueOnce({ transaction: disputed })

    const dto = await escrow.openDispute(7, { files: [makeBlob(512)], comment: 'En ligne' })

    expect(dto).toEqual({ transaction: disputed })
    expect(openDisputeApi).toHaveBeenCalledOnce()
    const [id, form] = openDisputeApi.mock.calls[0]
    expect(id).toBe(7)
    expect(form.getAll('files')).toHaveLength(1)
    expect(form.get('comment')).toBe('En ligne')
    // The online path never stamped a capture time; that stays offline-only.
    expect(form.get('clientCapturedAt')).toBeNull()
    expect(escrow.currentDetail.transaction).toEqual(disputed)
    expect(escrow.transactions[0]).toEqual(disputed)
    // Nothing must reach the queue while online.
    expect(queue.pendingCount).toBe(0)
    expect(escrow.currentDetail.transaction._queuedDispute).toBeUndefined()
  })
})
