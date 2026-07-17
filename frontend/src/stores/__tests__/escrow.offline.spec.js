import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
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

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  setActivePinia(createPinia())
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
