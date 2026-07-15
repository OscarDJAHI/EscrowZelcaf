import { defineStore } from 'pinia'
import apiClient from '@/api/client'

const STORAGE_KEY = 'escrow_offline_queue'

function loadQueue() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

function persistQueue(queue) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(queue))
}

/**
 * Generic "offline first" mutation queue: whenever a POST cannot reach the
 * backend because the device is offline, the request description is stored
 * here (and in localStorage, so it survives a reload) and replayed in order
 * as soon as the browser reports connectivity again.
 */
export const useOfflineQueueStore = defineStore('offlineQueue', {
  state: () => ({
    isOnline: typeof navigator === 'undefined' ? true : navigator.onLine,
    queue: loadQueue(),
    flushing: false,
    initialized: false,
  }),

  getters: {
    pendingCount: (state) => state.queue.length,
  },

  actions: {
    init() {
      if (this.initialized || typeof window === 'undefined') return
      this.initialized = true

      window.addEventListener('online', () => {
        this.isOnline = true
        this.flush()
      })
      window.addEventListener('offline', () => {
        this.isOnline = false
      })

      if (this.isOnline && this.queue.length > 0) {
        this.flush()
      }
    },

    /**
     * @param {{method: string, url: string, data?: object, meta?: object}} request
     * @returns {object} the queued item (includes a generated id)
     */
    enqueue(request) {
      const item = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
        timestamp: new Date().toISOString(),
        ...request,
      }
      this.queue.push(item)
      persistQueue(this.queue)
      return item
    },

    removeFromQueue(id) {
      this.queue = this.queue.filter((item) => item.id !== id)
      persistQueue(this.queue)
    },

    /** Replay queued requests in order; stop (keep remainder queued) on first failure. */
    async flush() {
      if (this.flushing || !this.isOnline || this.queue.length === 0) return
      this.flushing = true

      const pending = [...this.queue]
      let syncedAny = false

      for (const item of pending) {
        try {
          // eslint-disable-next-line no-await-in-loop
          await apiClient.request({ method: item.method, url: item.url, data: item.data })
          this.removeFromQueue(item.id)
          syncedAny = true
        } catch (err) {
          console.error('[offlineQueue] failed to sync queued request, will retry later', item, err)
          break
        }
      }

      this.flushing = false

      if (syncedAny && typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('escrow:sync'))
      }
    },
  },
})
