import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient from '@/api/client'
import {
  fetchTransactionDetail as fetchTransactionDetailApi,
  fetchTransactions as fetchTransactionsApi,
  openDispute as openDisputeApi,
} from '@/api/escrow'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import * as idb from '@/stores/offlineQueue.idb'
import { aTransaction, aUser } from '@/test-support/factories'
import type { DisplayTransactionDetail } from '@/stores/escrow'
import type { EscrowState } from '@/types/domain'
import type { CreateTransactionPayload } from '@/api/escrow'

// `TOKEN_STORAGE_KEY` is re-exported because a whole-module factory drops
// everything it does not name, and `stores/auth` — reached through
// `stores/escrow` since it stamps `meta.userId` — imports the real constant.
// `resetSessionExpiryLatch` joins it for `stores/session` (Story 1.9).
vi.mock('@/api/client', () => ({
  default: { request: vi.fn() },
  TOKEN_STORAGE_KEY: 'escrow_token',
  resetSessionExpiryLatch: vi.fn(),
}))
vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

// `description` ajoutée par la migration : le contrat de `createTransaction` l'exige, et
// le serveur la reçoit toujours. Une charge amputée d'un champ requis n'est pas ce que
// l'écran envoie.
const payload: CreateTransactionPayload = {
  sellerEmail: 'seller@example.com',
  amount: 1500,
  currency: 'XOF',
  description: 'Cargaison de cacao',
}

/** Deterministic payload — see `offlineQueue.spec.js`, same rationale. */
function makeBlob(sizeBytes = 2048, type = 'image/jpeg'): Blob {
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
async function expectSameBytes(actual: Blob, expected: Blob) {
  expect(typeof actual?.arrayBuffer).toBe('function')
  expect(actual.size).toBe(expected.size)
  const [a, b] = [await actual.arrayBuffer(), await expected.arrayBuffer()]
  expect(Buffer.compare(Buffer.from(a), Buffer.from(b))).toBe(0)
}

/** Detail shape the dispute paths read: a transaction plus its audit logs. */
function detailFor(id: number | string, state: EscrowState = 'FUNDS_LOCKED'): DisplayTransactionDetail {
  // `id` est répandu APRÈS la fabrique : une transaction du serveur porte un identifiant
  // numérique, mais la vue en connaît aussi de la forme `local-…` (carte optimiste), et
  // c'est `DisplayTransaction` qui l'admet — pas `Transaction`.
  return { transaction: { ...aTransaction({ state }), id }, auditLogs: [] }
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
    // `FUNDS_LOCKED` + `SHIP_GOODS` et non `PAID` + `SHIP` : ces deux dernières valeurs
    // n'existent dans AUCUNE des deux énumérations, et `evidence` n'appartient pas à
    // `currentDetail` (c'est une clé de la réponse d'ouverture de litige). Le test reste
    // le même — il prouve qu'un échec d'enfilement remonte au lieu d'être marqué en
    // attente — mais il le prouve désormais sur un état que la machine peut atteindre.
    escrow.currentDetail = { transaction: aTransaction({ id: 9, state: 'FUNDS_LOCKED' }), auditLogs: [] }
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))

    await expect(escrow.sendTransactionEvent(9, 'SHIP_GOODS')).rejects.toThrow('QuotaExceededError')

    expect(escrow.currentDetail!.transaction._queuedEvent).toBeUndefined()
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
    expect(entry.data!.comment).toBe('Colis endommagé')
    expect(entry.data!.clientCapturedAt).toMatch(/^\d{4}-\d{2}-\d{2}T[\d:.]+Z$/)
    // `files` at the root — that is where `buildFormData()` looks for them.
    expect(entry.files).toHaveLength(2)
    await expectSameBytes(entry.files![0], a)
    await expectSameBytes(entry.files![1], b)
    expect(openDisputeApi).not.toHaveBeenCalled()
  })

  it('flips the detail and its dashboard row to DISPUTED optimistically', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [aTransaction({ id: 7, state: 'FUNDS_LOCKED' }), aTransaction({ id: 8, state: 'FUNDS_LOCKED' })]

    await escrow.openDispute(7, { files: [makeBlob(512)], comment: 'Article cassé' })

    expect(escrow.currentDetail!.transaction.state).toBe('DISPUTED')
    expect(escrow.currentDetail!.transaction._queuedDispute).toBe(true)
    expect(escrow.transactions[0]).toMatchObject({ state: 'DISPUTED', _queuedDispute: true })
    // The other transaction must not be dragged along.
    expect(escrow.transactions[1].state).toBe('FUNDS_LOCKED')
  })

  it('flips the rows the route actually addresses, whose ids are numbers not strings', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [aTransaction({ id: 7, state: 'FUNDS_LOCKED' })]

    // What production really passes: `TransactionDetailView` declares its route
    // prop as a String and hands it down, while the API sends numeric ids.
    await escrow.openDispute('7', { files: [makeBlob(512)], comment: 'Id de route' })

    expect(escrow.currentDetail!.transaction._queuedDispute).toBe(true)
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
    expect(escrow.currentDetail!.transaction.state).toBe('FUNDS_LOCKED')
  })

  it('shows nothing optimistic when the entry could not be persisted', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [aTransaction({ id: 7, state: 'FUNDS_LOCKED' })]
    vi.spyOn(idb, 'put').mockRejectedValueOnce(new Error('QuotaExceededError'))

    await expect(
      escrow.openDispute(7, { files: [makeBlob(512)], comment: 'Jamais mis en file' }),
    ).rejects.toThrow('QuotaExceededError')

    // Nothing queued means nothing will ever sync: a DISPUTED badge here would
    // promise a dispute that exists nowhere.
    expect(escrow.currentDetail!.transaction.state).toBe('FUNDS_LOCKED')
    expect(escrow.currentDetail!.transaction._queuedDispute).toBeUndefined()
    expect(escrow.transactions[0]._queuedDispute).toBeUndefined()
    expect(queue.pendingCount).toBe(0)
  })

  it('replays the queued dispute as one multipart POST with its bytes intact', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    // A signed-in user, both halves: since Story 1.9 `flush()` only replays the
    // entries `escrow.js` stamped with the *current* user's id. An anonymous
    // fixture would queue an ownerless entry, which nothing ever replays.
    // Awaited, because `applySession` now also adopts the queue for the new
    // session — left in flight it would read and flush behind this test's back.
    await useAuthStore().applySession({ token: 'alice-token', user: aUser({ id: 42, email: 'alice@corp.example' }) })
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)
    const [a, b] = [makeBlob(2048, 'image/png'), makeBlob(4096, 'application/pdf')]

    await escrow.openDispute(7, { files: [a, b], comment: 'Deux preuves jointes' })
    const { clientCapturedAt } = (await idb.getAll())[0]!.data!
    const synced = vi.fn()
    window.addEventListener('escrow:sync', synced)

    queue.isOnline = true
    await queue.flush()
    window.removeEventListener('escrow:sync', synced)

    // The entry this story produces is really consumable by the 4.1 replay.
    expect(apiClient.request).toHaveBeenCalledOnce()
    const config = vi.mocked(apiClient.request).mock.calls[0]![0]
    expect(config.method).toBe('post')
    expect(config.url).toBe('/api/v1/escrow/7/dispute')
    expect(config.headers).toEqual({ 'Content-Type': 'multipart/form-data' })
    // `config.data` est `unknown` pour axios, qui accepte n'importe quel corps ; les
    // trois lignes au-dessus viennent d'établir que c'en est bien un multipart.
    const body = config.data as FormData
    const sent = body.getAll('files')
    expect(sent).toHaveLength(2)
    await expectSameBytes(sent[0] as Blob, a)
    await expectSameBytes(sent[1] as Blob, b)
    expect(body.get('comment')).toBe('Deux preuves jointes')
    expect(body.get('clientCapturedAt')).toBe(clientCapturedAt)
    expect(queue.pendingCount).toBe(0)
    expect(await idb.getAll()).toHaveLength(0)
    // The views refetch on this event — it is what clears `_queuedDispute` and
    // replaces the optimistic state with the server's own.
    expect(synced).toHaveBeenCalledOnce()
  })

  it('rehydrates a queued dispute after a reload and keeps it replayable', async () => {
    const escrow = useEscrowStore()
    // Persisted, so the brand-new Pinia below finds the same session back — which
    // is what a real reload does, and what Story 1.9 requires before an entry can
    // be hydrated at all. Awaited for the same reason as above.
    await useAuthStore().applySession({ token: 'alice-token', user: aUser({ id: 42, email: 'alice@corp.example' }) })
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
    // The owner rides along with the rest of `meta`: it is what lets the reloaded
    // queue recognise the entry as this session's (Story 1.9).
    expect(entry.meta).toEqual({ type: 'OPEN_DISPUTE', transactionId: 7, userId: 42 })
    expect(entry.files![0]).toBeInstanceOf(Blob)
    await expectSameBytes(entry.files![0], blob)

    reloaded.isOnline = true
    await reloaded.flush()

    expect(apiClient.request).toHaveBeenCalledOnce()
    await expectSameBytes(
      (vi.mocked(apiClient.request).mock.calls[0]![0].data as FormData).getAll('files')[0] as Blob,
      blob,
    )
  })
})

describe('escrow store — when a load saw the server', () => {
  // Sentinel instants five minutes apart, never `new Date()`: two real
  // `toISOString()` calls land in the same millisecond, so a test built on them
  // could not tell "stamped at issue" from "stamped on return" — the exact
  // confusion this behaviour exists to prevent.
  const ISSUED_AT = '2026-01-01T00:00:00.000Z'
  const LANDED_AT = '2026-01-01T00:05:00.000Z'

  afterEach(() => {
    vi.useRealTimers()
  })

  it('has no stamp until a load has actually succeeded', () => {
    const escrow = useEscrowStore()

    expect(escrow.transactionsFetchedAt).toBeNull()
    expect(escrow.currentDetailFetchedAt).toBeNull()
  })

  it('stamps loadTransactions with the instant it was issued, not the one it landed', async () => {
    const escrow = useEscrowStore()
    vi.useFakeTimers()
    vi.setSystemTime(new Date(ISSUED_AT))
    let land
    vi.mocked(fetchTransactionsApi).mockReturnValueOnce(new Promise((resolve) => { land = resolve }))

    const loading = escrow.loadTransactions()
    // The response is in flight while the clock moves — precisely the window in
    // which `flush()` freezes an entry. This payload is the server's state from
    // *before* that freeze, so a stamp of LANDED_AT would let `SyncFailureNotice`
    // badge it as having seen the rejection.
    vi.setSystemTime(new Date(LANDED_AT))
    land!([aTransaction({ id: 7, state: 'FUNDS_LOCKED' })])
    await loading

    expect(escrow.transactionsFetchedAt).toBe(ISSUED_AT)
  })

  it('stamps loadTransactionDetail with the instant it was issued, not the one it landed', async () => {
    const escrow = useEscrowStore()
    vi.useFakeTimers()
    vi.setSystemTime(new Date(ISSUED_AT))
    let land
    vi.mocked(fetchTransactionDetailApi).mockReturnValueOnce(new Promise((resolve) => { land = resolve }))

    const loading = escrow.loadTransactionDetail(7)
    vi.setSystemTime(new Date(LANDED_AT))
    land!(detailFor(7))
    await loading

    expect(escrow.currentDetailFetchedAt).toBe(ISSUED_AT)
  })

  it('leaves both stamps untouched when a load fails', async () => {
    const escrow = useEscrowStore()
    escrow.transactionsFetchedAt = ISSUED_AT
    escrow.currentDetailFetchedAt = ISSUED_AT
    vi.mocked(fetchTransactionsApi).mockRejectedValueOnce(new Error('network down'))
    vi.mocked(fetchTransactionDetailApi).mockRejectedValueOnce(new Error('network down'))

    await escrow.loadTransactions()
    await escrow.loadTransactionDetail(7)

    // A failed load leaves the previous data on screen, so the stamp that
    // describes that data has to stay with it.
    expect(escrow.transactionsFetchedAt).toBe(ISSUED_AT)
    expect(escrow.currentDetailFetchedAt).toBe(ISSUED_AT)
  })
})

describe('escrow store — a queued entry carries its owner', () => {
  it('stamps meta.userId on all three offline paths', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    useAuthStore().user = aUser({ id: 42, email: 'alice@corp.example' })
    queue.isOnline = false
    escrow.currentDetail = detailFor(7)

    await escrow.createNewTransaction(payload)
    await escrow.sendTransactionEvent(7, 'SHIP_GOODS')
    await escrow.openDispute(7, { files: [makeBlob(512)], comment: 'Colis endommagé' })

    // Read back through IndexedDB: the owner has to survive the reload, since
    // that is exactly when a frozen entry gets displayed to whoever is logged in.
    const stored = await idb.getAll()
    expect(stored).toHaveLength(3)
    expect(stored.map((e) => e.meta!.userId)).toEqual([42, 42, 42])
  })

  it('leaves meta.userId absent when nobody is logged in', async () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false

    await escrow.createNewTransaction(payload)

    // Not a crash and not a fabricated owner: an unowned entry is shown to
    // nobody rather than to the next person to log in.
    expect((await idb.getAll())[0]!.meta!.userId).toBeUndefined()
  })
})

describe('escrow store — opening a dispute online is unchanged', () => {
  it('posts directly and replaces the state with the returned transaction', async () => {
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    queue.isOnline = true
    escrow.currentDetail = detailFor(7)
    escrow.transactions = [aTransaction({ id: 7, state: 'FUNDS_LOCKED' })]
    const disputed = aTransaction({ id: 7, state: 'DISPUTED' })
    // `evidence` fait partie du `DisputeOpenedDto` : la réponse rend la transaction ET
    // les pièces créées. L'omettre construisait une réponse que le serveur n'envoie pas.
    vi.mocked(openDisputeApi).mockResolvedValueOnce({ transaction: disputed, evidence: [] })

    const dto = await escrow.openDispute(7, { files: [makeBlob(512)], comment: 'En ligne' })

    expect(dto).toEqual({ transaction: disputed, evidence: [] })
    expect(openDisputeApi).toHaveBeenCalledOnce()
    const [id, form] = vi.mocked(openDisputeApi).mock.calls[0]
    expect(id).toBe(7)
    expect(form.getAll('files')).toHaveLength(1)
    expect(form.get('comment')).toBe('En ligne')
    // The online path never stamped a capture time; that stays offline-only.
    expect(form.get('clientCapturedAt')).toBeNull()
    expect(escrow.currentDetail!.transaction).toEqual(disputed)
    expect(escrow.transactions[0]).toEqual(disputed)
    // Nothing must reach the queue while online.
    expect(queue.pendingCount).toBe(0)
    expect(escrow.currentDetail!.transaction._queuedDispute).toBeUndefined()
  })
})
