import { defineStore } from 'pinia'
import {
  createTransaction,
  fetchTransactionDetail,
  fetchTransactions,
  sendTransactionEvent,
} from '@/api/escrow'
import { useOfflineQueueStore } from './offlineQueue'

export const useEscrowStore = defineStore('escrow', {
  state: () => ({
    transactions: [],
    currentDetail: null, // { transaction, auditLogs }
    loading: false,
    error: null,
  }),

  actions: {
    async loadTransactions() {
      this.loading = true
      this.error = null
      try {
        this.transactions = await fetchTransactions()
      } catch (err) {
        this.error = err.response?.data?.message || 'Unable to load your transactions.'
      } finally {
        this.loading = false
      }
    },

    /**
     * Creates a transaction. If the device is offline, the request is queued
     * and an optimistic placeholder card (marked `_queuedOffline`) is shown
     * immediately; it will be replaced by the real record once synced.
     */
    async createNewTransaction(payload) {
      const offlineQueue = useOfflineQueueStore()

      if (!offlineQueue.isOnline) {
        const optimistic = {
          id: `local-${Date.now()}`,
          buyerEmail: null,
          sellerEmail: payload.sellerEmail,
          amount: payload.amount,
          currency: payload.currency,
          description: payload.description,
          state: 'INITIATED',
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
          _queuedOffline: true,
        }
        this.transactions.unshift(optimistic)
        offlineQueue.enqueue({
          method: 'post',
          url: '/api/v1/escrow',
          data: payload,
          meta: { type: 'CREATE_TRANSACTION' },
        })
        return optimistic
      }

      const created = await createTransaction(payload)
      this.transactions.unshift(created)
      return created
    },

    async loadTransactionDetail(id) {
      this.loading = true
      this.error = null
      try {
        this.currentDetail = await fetchTransactionDetail(id)
      } catch (err) {
        this.error = err.response?.data?.message || 'Unable to load this transaction.'
      } finally {
        this.loading = false
      }
    },

    /**
     * Sends a state-machine event. If offline, the event is queued and the
     * transaction is flagged with `_queuedEvent` so the UI can explain why
     * the state hasn't changed yet.
     */
    async sendTransactionEvent(id, event) {
      const offlineQueue = useOfflineQueueStore()

      if (!offlineQueue.isOnline) {
        offlineQueue.enqueue({
          method: 'post',
          url: `/api/v1/escrow/${id}/event`,
          data: { event },
          meta: { type: 'SEND_EVENT', transactionId: id },
        })
        if (this.currentDetail?.transaction?.id === id) {
          this.currentDetail.transaction._queuedEvent = event
        }
        return null
      }

      const updated = await sendTransactionEvent(id, event)

      if (this.currentDetail?.transaction && String(this.currentDetail.transaction.id) === String(id)) {
        this.currentDetail.transaction = updated
      }
      const index = this.transactions.findIndex((t) => String(t.id) === String(id))
      if (index !== -1) this.transactions[index] = updated

      return updated
    },
  },
})
