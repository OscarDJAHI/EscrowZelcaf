import { toRaw } from 'vue'
import { defineStore } from 'pinia'
import apiClient from '@/api/client'
import { classifyReplayFailure, extractFailureReason } from '@/utils/replayFailure'
import { ownsEntry } from '@/utils/frozenEntry'
import { useAuthStore } from './auth'
import * as idb from './offlineQueue.idb'
import type { QueueEntry, QueuedRequest } from '@/types/queue'

let seqCounter = 0

/** Enqueue order within this session; ties `timestamp` down to a total order. */
function nextSeq(): number {
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
 */
function buildFormData(item: QueueEntry): FormData {
  const form = new FormData()
  // `?? []` plutôt qu'une assertion : `files` est optionnel dans le type parce qu'il
  // l'est dans les faits — seules les entrées binaires en portent. L'appelant ne
  // franchit cette fonction que si `item.files?.length`, mais c'est SON invariant,
  // pas celui d'ici.
  for (const file of item.files ?? []) form.append('files', file) // exact repeated key 'files'
  const data = item.data ?? {}
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
interface OfflineQueueState {
  isOnline: boolean
  queue: QueueEntry[]
  flushing: boolean
  initialized: boolean
  hydrated: boolean
}

export const useOfflineQueueStore = defineStore('offlineQueue', {
  state: (): OfflineQueueState => ({
    isOnline: typeof navigator === 'undefined' ? true : navigator.onLine,
    queue: [],
    flushing: false,
    initialized: false,
    hydrated: false,
  }),

  getters: {
    // Frozen entries are excluded: they are no longer waiting for anything, and
    // `OnlineBanner` reads this to mean "there is still work in flight".
    pendingCount: (state): number => state.queue.filter((item) => !item.frozen).length,
    /** Entries definitively rejected by the server; kept, never auto-replayed. */
    frozenEntries: (state): QueueEntry[] => state.queue.filter((item) => item.frozen),
    frozenCount: (state): number => state.queue.filter((item) => item.frozen).length,
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
     *
     * The listeners are bound session or no session (Story 1.9): they are what
     * makes the app react to connectivity for the rest of its life, and binding
     * them only for a signed-in boot would leave a user who signs in afterwards
     * with a queue that never flushes on reconnection.
     */
    async init(): Promise<void> {
      if (typeof window === 'undefined') return

      if (!this.initialized) {
        this.initialized = true
        window.addEventListener('online', () => {
          this.isOnline = true
          // `.catch` and not a bare call: `flush()` can reject (an escape from
          // outside its per-entry `try` — the reason the lock lives in a
          // `finally`), and a rejection out of an event listener has nobody to
          // catch it. The lock is already released by then; the run is simply
          // over.
          this.flush().catch((err) => {
            console.error('[offlineQueue] replay interrupted after coming back online', err)
          })
        })
        window.addEventListener('offline', () => {
          this.isOnline = false
        })
      }

      if (this.hydrated) return

      // TROIS ÉTATS ET NON DEUX (Story 2.7, AC5). La condition d'origine s'écrivait
      // `useAuthStore().user?.id == null` : elle rangeait sous « personne n'est
      // connecté » un cas qui n'est pas celui-là — un JETON présent dont le profil est
      // illisible. Voir `SessionState` dans `stores/auth.ts` pour le défaut complet.
      const session = useAuthStore().sessionState

      // SESSION INCOHÉRENTE : on ne prétend PAS avoir lu la file.
      //
      // La différence avec la branche anonyme ci-dessous tient en un mot : ici, il y a
      // bien quelqu'un — le jeton le prouve — et ses entrées sont peut-être en
      // IndexedDB. Poser `hydrated = true` ferait dire à `RecoveryView` « rien à
      // récupérer » (`recovery.nothingToRecover`) à propos d'une file que personne n'a
      // ouverte, sur la seule surface qui rende à l'utilisateur des fichiers n'existant
      // NULLE PART ailleurs. Laissé à faux, l'écran affiche son état de lecture manquée
      // et son bouton de reprise, et une nouvelle connexion — qui répare le profil —
      // passera par `adoptSession()`.
      //
      // Le drapeau est le seul signal disponible : `RecoveryView` lit `hydrated` et rien
      // d'autre. Ne rien faire du tout — l'ancien comportement moins le mensonge —
      // serait indiscernable d'un échec de stockage, ce qui est précisément la
      // confusion que `hydrated` existe pour trancher.
      if (session === 'incoherent') {
        console.error(
          '[offlineQueue] incoherent session (a token without a readable profile): the queue is not read, and is not claimed to be',
        )
        return
      }

      // Booting on the login screen: there is nobody to read the queue *for*, so
      // nothing is read and nothing is replayed — the device may well hold the
      // previous user's entries. `hydrated` is still set, because this restore
      // did run to completion: it is the flag `RecoveryView` reads to tell "not
      // read yet" from "nothing there", and leaving it false would strand that
      // screen on a spinner. The sign-in that follows goes through
      // `adoptSession()`, which is what finally reads the queue.
      if (session === 'anonymous') {
        this.hydrated = true
        return
      }

      // Tout autre état poursuit vers l'hydratation — et c'est la place réservée au
      // quatrième état de la Story 2.4. Un compte non vérifié a un identifiant, donc des
      // entrées qui sont les siennes : les lui refuser au démarrage serait un choix
      // produit que rien n'a demandé. Écrit en branches nommées plutôt qu'en `!== 'active'`
      // exactement pour que ce cas s'ajoute sans réécrire cette fonction.

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
        // Inside its own `catch`, because this line sits OUTSIDE the `try` above
        // and `main.js` calls `init()` un-awaited: a rejection here is exactly
        // the unhandled rejection the "Never rejects" contract promises not to
        // produce. The hydration has already succeeded and been recorded; only
        // the send failed.
        try {
          await this.flush()
        } catch (err) {
          console.error('[offlineQueue] replay interrupted at startup', err)
        }
      }
    },

    /**
     * Loads the signed-in user's persisted entries into state, oldest first
     * (FIFO replay order).
     *
     * Scoped at the source since Story 1.9: the database is device-global, so
     * loading it whole is what let another user's entries reach `flush()` — and
     * the freeze that follows (NOT_A_PARTY) is permanent, costing their owner
     * evidence they can no longer recover.
     *
     * With no session nothing is loaded *and nothing is dropped*: an entry
     * queued during this very session is legitimately in `queue` and is not the
     * caller's to lose.
     *
     * <p>La condition reste `userId == null` et n'a pas été portée sur `sessionState`
     * (Story 2.7) : ici la question n'est pas « dans quel état est la session » mais
     * « ai-je de quoi interroger `getAllForUser` ». Ses deux appelants — `init()` et
     * `adoptSession()` — ont déjà tranché l'état avant d'arriver, et le seul cas où cette
     * ligne agit encore est celui d'une session incohérente atteinte par un autre chemin :
     * elle rend alors la main sans rien lire, ce qui est le bon comportement.
     *
     * <p>`flush()` n'a rien demandé non plus : son filtre `ownsEntry(item, user)` rend
     * `false` dès que `user?.id` est nul, donc une session incohérente ne rejoue RIEN.
     */
    async hydrate(): Promise<void> {
      const userId = useAuthStore().user?.id
      if (userId == null) return

      const items = await idb.getAllForUser(userId)
      // Merge, don't replace: the app is interactive while this is in flight
      // (`main.js` does not await `init()`), so an entry queued meanwhile may
      // have missed the snapshot. Overwriting would hide it from the banner and
      // from `flush()` until the next reload.
      const known = new Set(items.map((item) => item.id))
      const queuedMeanwhile = this.queue.filter((item) => !known.has(item.id))
      this.queue = [...items, ...queuedMeanwhile]
    },

    /**
     * Drops this session's *view* of the queue, and only that: the entries stay
     * in IndexedDB. Used by both ends of `endSession()` — on an explicit logout
     * the persisted entries are deleted separately (`idb.clearForUser`), on an
     * expiry they are deliberately kept and come back at the next sign-in.
     *
     * `initialized` and the connectivity listeners are left alone: the tab is
     * still the same tab, and re-binding them per session would stack duplicates.
     */
    clearMemory(): void {
      this.queue = []
      this.hydrated = false
    },

    /**
     * A session has just started in a tab that is already running: read this
     * user's entries and replay them if the device is online.
     *
     * `main.js` has long finished by the time anyone signs in, so without this
     * the user would sit in front of their own pending evidence without it ever
     * being loaded or sent. The legacy migration is re-run here for the same
     * reason: a boot on the login screen skips it, and the import must not wait
     * for the next full reload.
     *
     * Never rejects — it is called from `beginSession()`, itself reached from a
     * store action nobody awaits for its errors.
     */
    async adoptSession(): Promise<void> {
      if (typeof window === 'undefined') return

      // Lowered BEFORE the attempt, not merely raised after it. A boot on the
      // sign-in screen leaves the flag *true* (`init()` above: it ran to
      // completion, it just had nobody to read for). Were this read to fail with
      // the flag still true, the app would report "queue read, and it is empty"
      // while the user's only copy of their evidence sits in IndexedDB —
      // `RecoveryView` would say "Nothing to recover" instead of offering its
      // retry, and its `if (!queue.hydrated)` guard would never call `init()`
      // again. A swallowed failure is not a loading state, and it is even less a
      // result.
      this.hydrated = false

      try {
        await idb.migrateFromLocalStorage()
        await this.hydrate()
        // Set here and not only in `init()`: `clearMemory()` lowered the flag on
        // the way out, and this is the read that answers it. Left false, the flag
        // would claim the queue is unread while it is loaded — `RecoveryView`
        // would show its spinner and call `init()` for a second, redundant trip
        // to IndexedDB. Inside the `try` and after `hydrate()`, like `init()`:
        // a failure must stay retryable rather than be recorded as a success.
        this.hydrated = true
      } catch (err) {
        // Same reasoning as `init()`: the entries are still in IndexedDB and
        // only this session's view of them failed.
        console.error('[offlineQueue] could not restore the queue for this session', err)
        return
      }

      // Started, not awaited. `beginSession()` runs inside `applySession`, which
      // `auth.login()` awaits before handing control back to the view: awaiting
      // the replay here would hold the sign-in button on "Please wait…" for the
      // whole of it — `flush()` sends serially, and a queued dispute carries
      // multi-megabyte photos over the very network that made it queue. What the
      // sign-in must wait for is the *read* above (and the read-cache purge that
      // precedes it in `beginSession`); the *send* belongs in the background,
      // exactly as it does at boot, where `main.js` never awaits `init()`.
      // `.catch` for the same reason as the `online` listener: nobody awaits
      // this, so a rejection escaping `flush()` would surface as an unhandled
      // rejection — through `beginSession`, whose own `try/catch` cannot see it
      // because this function has already returned, and whose contract (like
      // this one's) says it never rejects.
      if (this.isOnline) {
        this.flush().catch((err) => {
          console.error('[offlineQueue] replay interrupted after signing in', err)
        })
      }
    },

    /**
     * Rend l'entrée mise en file, identifiant généré compris.
     *
     * <p><b>Le point d'étranglement où une session incohérente est refusée</b> (Story 2.7,
     * AC5). Les appelants estampillent `meta.userId` avec `useAuthStore().user?.id` : dans
     * cet état-là, le profil étant illisible, chacun d'eux écrirait `undefined`. L'entrée
     * naîtrait ORPHELINE — invisible à `getAllForUser`, invisible à `ownsEntry`, jamais
     * rejouée, et supprimée par la première déconnexion venue (`offlineQueue.idb.ts:140`,
     * qui purge nommément les entrées sans propriétaire). Autrement dit : une preuve
     * acceptée par l'interface, perdue en silence. AD-9 l'interdit.
     *
     * <p>La garde vit ICI et non aux trois points d'appel de `stores/escrow.ts` : trois
     * copies divergent, et un quatrième appelant naîtrait sans garde. Refuser est la seule
     * issue non destructrice — il n'existe aucun propriétaire à inscrire, et en inventer
     * un (le marqueur d'appareil, par exemple) rendrait à quelqu'un les entrées d'un autre.
     *
     * <p>L'état `'anonymous'` n'est PAS refusé, et l'asymétrie est délibérée : il ne
     * traduit aucune incohérence — c'est l'état honnête d'une application sans session, et
     * les surfaces qui appellent `enqueue` sont toutes derrière une route protégée. C'est
     * l'état INCOHÉRENT qui fabrique un orphelin pendant que l'application se croit
     * authentifiée.
     */
    async enqueue(request: QueuedRequest): Promise<QueueEntry> {
      if (useAuthStore().sessionState === 'incoherent') {
        throw new Error(
          '[offlineQueue] incoherent session (a token without a readable profile): refusing to queue an entry that would have no owner',
        )
      }

      const item: QueueEntry = {
        id: `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`,
        timestamp: new Date().toISOString(),
        // Monotonic within the session: `timestamp` only resolves to the
        // millisecond, and two entries queued in the same one must still replay
        // in the order the user made them. See `sortFifo()` in the idb module.
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

    async removeFromQueue(id: string): Promise<void> {
      await idb.remove(id)
      this.queue = this.queue.filter((item) => item.id !== id)
    },

    /**
     * Replay queued requests in order. A transient failure stops the run and
     * keeps the remainder queued; a permanent one freezes the offending entry
     * and moves on, so a definitive rejection bounds the auto-retry rather than
     * corking the queue behind it.
     *
     * Only the signed-in user's entries are replayed (Story 1.9). `client.js`
     * attaches whatever JWT is current at *send* time, never the one in force
     * when the entry was queued: replaying a stranger's request under it earns a
     * NOT_A_PARTY, which is permanent, which freezes their evidence for good.
     * Filtering the display was Story 4.4's job and is not enough — the damage
     * here is done by the request, not by the pixel.
     */
    async flush(): Promise<void> {
      // `pendingCount`, not `queue.length`: frozen entries are never purged, so a
      // queue holding nothing else would otherwise pay a full empty run on every
      // `online` event and every startup, forever.
      if (this.flushing || !this.isOnline || this.pendingCount === 0) return

      // Frozen entries are skipped, not replayed: the server has already given
      // its final word on them. Story 4.5 is what brings them back.
      const user = useAuthStore().user
      const pending = this.queue.filter((item) => !item.frozen && ownsEntry(item, user))
      // Checked after the ownership filter and not only through `pendingCount`
      // (a getter Story 4.4 owns and this story must not touch): a queue holding
      // nothing but somebody else's entries has nothing to send.
      if (pending.length === 0) return

      this.flushing = true
      let syncedAny = false
      let reconciledAny = false

      // `finally`, not a plain assignment at the end: `flushing` is a lock, and
      // an escape from anywhere in the loop would leave it raised for the life of
      // the tab. Every later `flush()` — every `online` event, every
      // `adoptSession()` — returns immediately on the stale lock, so the queue
      // stops replaying for good and says nothing about it.
      try {
        for (const item of pending) {
          // Re-checked every iteration, and not only in the filter above: this
          // loop awaits a multipart upload per entry, so a whole sign-out and
          // sign-in can happen inside it. `pending` was resolved against the user
          // who started the run; carrying on would send the rest of their entries
          // under the next person's JWT — the exact cross-user replay this story
          // exists to close, reached through time rather than through the queue.
          if (!ownsEntry(item, useAuthStore().user)) break

          try {
            if (item.files?.length) {
              // Entry carrying binary: replayed as multipart. The explicit
              // Content-Type marks the request as multipart; the adapter drops
              // the header so the browser can set it with its own boundary (the
              // shared client defaults to application/json).
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
            // Ownership re-checked AFTER the await too, and this is not the same
            // check as the one at the top of the iteration. The upload above can
            // take minutes; a sign-out inside that window has already deleted
            // this entry from IndexedDB (`idb.clearForUser`), and the freeze
            // below would `put` it straight back — Blobs, comment and all — onto
            // a device that has just been handed over. Once the session that
            // owned the entry is gone, neither the freeze nor the retry is ours
            // to decide: leave it exactly as the purge left it.
            if (!ownsEntry(item, useAuthStore().user)) {
              console.error(
                '[offlineQueue] the session changed while this entry was in flight; leaving it to its owner',
                item,
                err,
              )
              break
            }

            if (classifyReplayFailure(err) === 'transient') {
              console.error('[offlineQueue] failed to sync queued request, will retry later', item, err)
              break
            }

            // Permanent rejection: replaying would fail identically forever.
            // Freeze the entry — id, `data` and Blobs all kept — so the
            // auto-retry stops without anything being lost.
            console.error('[offlineQueue] queued request definitively rejected, freezing it', item, err)
            const { code, status, message } = extractFailureReason(err)
            item.frozen = true
            item.failure = { code, status, message, at: new Date().toISOString() }
            try {
              // Persisted before moving on: a reload must not resurrect the
              // auto-retry of a request the server already refused.
              //
              // `toRaw` is load-bearing: `item` came out of the reactive state,
              // and structured clone refuses a Proxy outright (DataCloneError).
              // Only `enqueue()` gets away with a bare `put` — it writes the item
              // before pushing it into the state. Writing the proxy here would
              // throw into the catch below and leave the freeze session-local, so
              // the entry would go back to being replayed on the next reload.
              // eslint-disable-next-line no-await-in-loop
              await idb.put(toRaw(item))
            } catch (persistErr) {
              // The freeze holds in memory for this session and the entry is
              // still in IndexedDB. Turning a storage hiccup into a sync failure
              // would put the cork straight back in the queue.
              console.error(
                '[offlineQueue] could not persist the frozen entry; it stays frozen for this session only',
                item,
                persistErr,
              )
            }
            // The optimistic display is now a lie, and `flush()` only ever runs
            // online: the server is reachable and authoritative, so let the wired
            // refetch overwrite it.
            reconciledAny = true
            continue
          }

          // Past this line the server has accepted the request, so failing to
          // erase the entry is a bookkeeping problem — not a sync failure.
          // Letting it fall into the catch above would replay an already-accepted
          // request and create a duplicate transaction.
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
      } finally {
        this.flushing = false

        // In the `finally` alongside the lock, and for the same reason. An
        // escape from the loop used to skip this line, so a run that had already
        // synced or frozen entries would leave the optimistic display standing
        // with nothing ever telling it to reconcile — the wired refetch hangs
        // off this event and off nothing else. What was sent was sent; the
        // signal describes that, not how the run ended.
        if ((syncedAny || reconciledAny) && typeof window !== 'undefined') {
          window.dispatchEvent(new CustomEvent('escrow:sync'))
        }
      }
    },
  },
})
