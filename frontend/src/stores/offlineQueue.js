import { defineStore } from 'pinia'
import apiClient from '@/api/client'
import * as idb from './offlineQueue.idb'

let seqCounter = 0

/** Enqueue order within this session; ties `timestamp` down to a total order. */
function nextSeq() {
  seqCounter += 1
  return seqCounter
}

/**
 * Builds the multipart body for an entry carrying binary. Part names are frozen
 * (AD-13) and identical to `stores/escrow.js` / `stores/evidence.js`: repeated
 * `files`, optional `comment` / `clientCapturedAt`.
 *
 * Those three parts are the whole contract: `data` deliberately carries nothing
 * else on a binary entry, so there is nothing to forward generically. Anything
 * added there later needs a part here too — it would not travel on its own.
 * @param {object} item
 * @returns {FormData}
 */
function buildFormData(item) {
  const form = new FormData()
  for (const file of item.files) form.append('files', file) // exact repeated key 'files'
  const data = item.data || {}
  // `!= null`, not truthiness: an empty comment is the server's call to reject,
  // not ours to silently drop.
  if (data.comment != null) form.append('comment', data.comment)
  if (data.clientCapturedAt != null) form.append('clientCapturedAt', data.clientCapturedAt)
  return form
}

/**
 * Generic "offline first" mutation queue: whenever a POST cannot reach the
 * backend because the device is offline, the request description is stored
 * here (and in IndexedDB, so it survives a reload) and replayed in order as
 * soon as the browser reports connectivity again.
 *
 * IndexedDB — not localStorage — because an entry may carry a `files` array of
 * Blobs (an offline evidence deposit): they are persisted as-is via structured
 * clone, with no base64 round-trip and against a far larger storage budget.
 * IndexedDB is still quota-bound and can raise QuotaExceededError, so writes
 * are surfaced to the caller rather than assumed to succeed. Entries without
 * `files` keep their exact previous shape and JSON replay.
 */
export const useOfflineQueueStore = defineStore('offlineQueue', {
  state: () => ({
    isOnline: typeof navigator === 'undefined' ? true : navigator.onLine,
    queue: [],
    flushing: false,
    initialized: false,
    hydrated: false,
  }),

  getters: {
    pendingCount: (state) => state.queue.length,
  },

  actions: {
    /**
     * Migrates any legacy localStorage queue, hydrates from IndexedDB and
     * flushes if already online. Async now that the source is IndexedDB —
     * `main.js` calls this without awaiting, so `pendingCount` reads 0 for a
     * tick at startup; `OnlineBanner` is reactive and corrects itself.
     *
     * Never rejects: `main.js` calls it un-awaited, so a thrown storage error
     * would only surface as an unhandled rejection. A failure is logged and
     * leaves `hydrated` false so a later call can retry — the listeners are
     * bound once regardless, which is why the two flags are separate.
     */
    async init() {
      if (typeof window === 'undefined') return

      if (!this.initialized) {
        this.initialized = true
        window.addEventListener('online', () => {
          this.isOnline = true
          this.flush()
        })
        window.addEventListener('offline', () => {
          this.isOnline = false
        })
      }

      if (this.hydrated) return

      try {
        await idb.migrateFromLocalStorage()
        await this.hydrate()
        this.hydrated = true
      } catch (err) {
        // The entries are still in IndexedDB; only this session's view of them
        // failed. Staying un-hydrated is what makes a retry possible — marking
        // it done would strand the queue as permanently empty.
        console.error('[offlineQueue] could not restore the queue from IndexedDB', err)
        return
      }

      if (this.isOnline && this.queue.length > 0) {
        await this.flush()
      }
    },

    /** Loads the persisted queue into state, oldest first (FIFO replay order). */
    async hydrate() {
      const items = await idb.getAll()
      // Merge, don't replace: the app is interactive while this is in flight
      // (`main.js` does not await `init()`), so an entry queued meanwhile may
      // have missed the snapshot. Overwriting would hide it from the banner and
      // from `flush()` until the next reload.
      const known = new Set(items.map((item) => item.id))
      const queuedMeanwhile = this.queue.filter((item) => !known.has(item.id))
      this.queue = [...items, ...queuedMeanwhile]
    },

    /**
     * @param {{method: string, url: string, data?: object, files?: Blob[], meta?: object}} request
     * @returns {Promise<object>} the queued item (includes a generated id)
     */
    async enqueue(request) {
      const item = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
        timestamp: new Date().toISOString(),
        // Monotonic within the session: `timestamp` only resolves to the
        // millisecond, and two entries queued in the same one must still replay
        // in the order the user made them. See `getAll()`.
        seq: nextSeq(),
        ...request,
      }
      // Persist before exposing it: an IndexedDB write failure must reject to
      // the caller rather than leave a phantom entry in memory that no reload
      // would ever bring back.
      await idb.put(item)
      this.queue.push(item)
      return item
    },

    async removeFromQueue(id) {
      await idb.remove(id)
      this.queue = this.queue.filter((item) => item.id !== id)
    },

    /** Replay queued requests in order; stop (keep remainder queued) on first failure. */
    async flush() {
      if (this.flushing || !this.isOnline || this.queue.length === 0) return
      this.flushing = true

      const pending = [...this.queue]
      let syncedAny = false

      for (const item of pending) {
        try {
          if (item.files?.length) {
            // Entry carrying binary: replayed as multipart. The explicit
            // Content-Type marks the request as multipart; the adapter drops the
            // header so the browser can set it with its own boundary (the shared
            // client defaults to application/json).
            // eslint-disable-next-line no-await-in-loop
            await apiClient.request({
              method: item.method,
              url: item.url,
              data: buildFormData(item),
              headers: { 'Content-Type': 'multipart/form-data' },
            })
          } else {
            // eslint-disable-next-line no-await-in-loop
            await apiClient.request({ method: item.method, url: item.url, data: item.data })
          }
        } catch (err) {
          console.error('[offlineQueue] failed to sync queued request, will retry later', item, err)
          break
        }

        // Past this line the server has accepted the request, so failing to
        // erase the entry is a bookkeeping problem — not a sync failure. Letting
        // it fall into the catch above would replay an already-accepted request
        // and create a duplicate transaction.
        try {
          // eslint-disable-next-line no-await-in-loop
          await this.removeFromQueue(item.id)
        } catch (err) {
          console.error(
            '[offlineQueue] request accepted but the queued entry could not be dropped; removing it from memory to avoid a duplicate replay',
            item,
            err,
          )
          this.queue = this.queue.filter((queued) => queued.id !== item.id)
        }
        syncedAny = true
      }

      this.flushing = false

      if (syncedAny && typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent('escrow:sync'))
      }
    },
  },
})
