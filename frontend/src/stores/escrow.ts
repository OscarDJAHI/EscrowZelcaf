import { defineStore } from 'pinia'
import {
  createTransaction,
  fetchTransactionDetail,
  fetchTransactions,
  openDispute,
  sendTransactionEvent,
} from '@/api/escrow'
import { useAuthStore } from './auth'
import { useOfflineQueueStore } from './offlineQueue'
import { currentSessionEpoch } from './session'
import { apiErrorMessage } from '@/utils/apiError'
import type { AuditLog, DisputeOpened, EscrowEventName, Transaction } from '@/types/domain'
import type { CreateTransactionPayload } from '@/api/escrow'

/**
 * Une transaction TELLE QUE L'ÉCRAN LA VOIT — c'est-à-dire pas tout à fait celle que le
 * serveur envoie.
 *
 * <p>Deux écarts, tous deux réels et jusqu'ici tus par l'absence de types :
 * <ul>
 *   <li>`id` peut être une CHAÎNE `local-…`. Une création hors ligne pose une carte
 *       optimiste avant que le serveur n'ait attribué quoi que ce soit. C'est la raison
 *       pour laquelle tout le fichier compare `String(a) === String(b)` au lieu de `===`,
 *       et déclarer `id: number` aurait fait passer ces comparaisons pour des maladresses
 *       à « simplifier ».</li>
 *   <li>`buyerEmail` est `null` sur cette même carte : l'acheteur, c'est l'utilisateur
 *       courant, mais c'est le serveur qui le nomme.</li>
 * </ul>
 *
 * <p>Les marqueurs `_queued*` sont posés par le client et ne reviennent jamais du serveur.
 * `utils/frozenEntry.ts` les détecte PAR PRÉFIXE et non par ce type — délibérément : un
 * marqueur ajouté plus tard y est attrapé sans qu'on ait à penser à l'y déclarer.
 */
export interface DisplayTransaction extends Omit<Transaction, 'id' | 'buyerEmail'> {
  id: number | string
  buyerEmail: string | null
  _queuedOffline?: boolean
  _queuedEvent?: EscrowEventName
  _queuedDispute?: boolean
}

export interface DisplayTransactionDetail {
  transaction: DisplayTransaction
  auditLogs: AuditLog[]
}

interface EscrowState {
  transactions: DisplayTransaction[]
  currentDetail: DisplayTransactionDetail | null
  transactionsFetchedAt: string | null
  currentDetailFetchedAt: string | null
  loading: boolean
  error: string | null
}

export const useEscrowStore = defineStore('escrow', {
  state: (): EscrowState => ({
    transactions: [],
    currentDetail: null, // { transaction, auditLogs }
    // When each source was *issued* to the server, not when it landed — see
    // `loadTransactions`. Null until a load has succeeded. `SyncFailureNotice`
    // compares them against `failure.at` (same client clock, so no drift) to
    // tell a row that has seen a rejection from one that predates it.
    transactionsFetchedAt: null,
    currentDetailFetchedAt: null,
    loading: false,
    error: null,
  }),

  actions: {
    /**
     * L'ÉPOQUE DE SESSION, capturée à l'émission et relue à la résolution (Story 2.7,
     * AC6). Voir `stores/session.ts` pour ce qu'elle est et ce qu'elle ne couvre pas.
     *
     * <p>C'est ici que le défaut vivait : `this.transactions = await fetchTransactions()`
     * n'avait AUCUNE garde de session. La liste de A, émise juste avant sa déconnexion,
     * s'installait dans le store de B et y restait le temps que la lecture de B aboutisse
     * — l'horodatage `issuedAt` ajouté depuis date la réponse mais ne compare aucune
     * session, et n'a jamais rien empêché.
     *
     * <p>La comparaison porte sur les TROIS sorties — succès, échec, `finally` — et pas
     * seulement sur l'écriture des données. Un `error` écrit par la lecture de A afficherait
     * à B un message d'erreur pour une requête qui n'est pas la sienne ; un `loading = false`
     * poserait la main sur un store que `$reset()` vient de rendre neuf. Même forme que la
     * garde `loadSeq` d'`evidence.ts`, délibérément : deux gardes qui se lisent pareil se
     * relisent pareil.
     */
    async loadTransactions(): Promise<void> {
      this.loading = true
      this.error = null
      // Stamped before the call, written only on success. A load issued before a
      // replay was refused but landing after it carries the server's state from
      // *before* that refusal: stamping on return would date it after the
      // refusal and make a stale payload look like it had seen the verdict.
      // Keeping data and stamp together also means two concurrent loads landing
      // out of order stay coherent — the older response overwrites with its own
      // older stamp, so a reader degrades to "unknown" instead of being lied to.
      const issuedAt = new Date().toISOString()
      const epoch = currentSessionEpoch()
      try {
        const data = await fetchTransactions()
        if (epoch !== currentSessionEpoch()) return
        this.transactions = data
        this.transactionsFetchedAt = issuedAt
      } catch (err) {
        if (epoch !== currentSessionEpoch()) return
        // Stamp untouched on failure: the previous data is still on screen, so
        // the stamp that describes it must stay.
        this.error = apiErrorMessage(err) || 'Unable to load your transactions.'
      } finally {
        if (epoch === currentSessionEpoch()) this.loading = false
      }
    },

    /**
     * Creates a transaction. If the device is offline, the request is queued
     * and an optimistic placeholder card (marked `_queuedOffline`) is shown
     * immediately; it will be replaced by the real record once synced.
     */
    async createNewTransaction(
      payload: CreateTransactionPayload,
    ): Promise<DisplayTransaction> {
      const offlineQueue = useOfflineQueueStore()

      if (!offlineQueue.isOnline) {
        const optimistic: DisplayTransaction = {
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
        try {
          await offlineQueue.enqueue({
            method: 'post',
            url: '/api/v1/escrow',
            data: payload,
            // `userId` stamps who queued this. The queue is device-global and
            // survives `logout()`, so without an owner on the entry nothing
            // downstream can tell whose it is. Read at enqueue time, never
            // later: by the time a frozen entry is displayed the session may
            // belong to someone else.
            meta: { type: 'CREATE_TRANSACTION', userId: useAuthStore().user?.id },
          })
        } catch (err) {
          // Nothing was persisted, so nothing will ever sync: take the card back
          // rather than show a transaction no reload would bring back. Matched
          // on id, not identity — Pinia stores a reactive proxy of `optimistic`,
          // so `!==` against the raw object never matches and would keep it.
          this.transactions = this.transactions.filter((t) => t.id !== optimistic.id)
          throw err
        }
        return optimistic
      }

      // ÉCART NOMMÉ, PAS COCHÉ (Story 2.7, AC6) : cet `unshift` n'est PAS gardé par
      // l'époque. L'AC6 parle des réponses de LECTURE, et celle-ci est la réponse à une
      // écriture — la transaction existe bel et bien côté serveur, si bien que l'écarter
      // en silence est une décision produit et non une extension mécanique de la garde
      // (personne ne la verrait avant le prochain rechargement). Le fait est écrit
      // plutôt que corrigé de mon propre chef : à trancher avec le PO.
      //
      // Les deux AUTRES chemins d'écriture de ce store n'ont, eux, besoin de rien, et
      // c'est vérifié et non supposé : `sendTransactionEvent` et `openDispute` écrivent
      // via `findIndex(...)` sur `this.transactions` et via `this.currentDetail?.…`.
      // Après un `$reset()`, la liste est vide — l'index vaut -1 — et `currentDetail` est
      // nul : leurs écritures sont structurellement sans effet sur le store d'autrui.
      const created = await createTransaction(payload)
      this.transactions.unshift(created)
      return created
    },

    /** Même garde d'époque que `loadTransactions`, et pour la même raison (AC6). */
    async loadTransactionDetail(id: string | number): Promise<void> {
      this.loading = true
      this.error = null
      const issuedAt = new Date().toISOString() // before the call — see `loadTransactions`
      const epoch = currentSessionEpoch()
      try {
        const detail = await fetchTransactionDetail(id)
        if (epoch !== currentSessionEpoch()) return
        this.currentDetail = detail
        this.currentDetailFetchedAt = issuedAt
      } catch (err) {
        if (epoch !== currentSessionEpoch()) return
        this.error = apiErrorMessage(err) || 'Unable to load this transaction.'
      } finally {
        if (epoch === currentSessionEpoch()) this.loading = false
      }
    },

    /**
     * Sends a state-machine event. If offline, the event is queued and the
     * transaction is flagged with `_queuedEvent` so the UI can explain why
     * the state hasn't changed yet.
     */
    async sendTransactionEvent(
      id: string | number,
      event: EscrowEventName,
    ): Promise<DisplayTransaction | null> {
      const offlineQueue = useOfflineQueueStore()

      if (!offlineQueue.isOnline) {
        await offlineQueue.enqueue({
          method: 'post',
          url: `/api/v1/escrow/${id}/event`,
          data: { event },
          meta: { type: 'SEND_EVENT', transactionId: id, userId: useAuthStore().user?.id },
        })
        if (this.currentDetail?.transaction && this.currentDetail.transaction.id === id) {
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

    /**
     * Opens a dispute on a transaction. Builds the multipart FormData with the
     * frozen part names (AD-13: repeated `files`, required `comment`) and posts
     * to the composite endpoint. On success the displayed transaction flips to
     * DISPUTED (mirrors `sendTransactionEvent`) and the DisputeOpenedDto is
     * returned so the caller can react.
     *
     * Offline, the opening *and* its attachments are queued as a single entry
     * against the same composite endpoint, so the replay either files the whole
     * dispute or none of it — never a comment without its evidence. The queued
     * transaction is flipped to DISPUTED optimistically and flagged with
     * `_queuedDispute`; the call returns null, like the offline event path.
     * The state flip is the one divergence from `sendTransactionEvent`, which
     * keeps the badge truthful and only sets `_queuedEvent`: a dispute must show
     * DISPUTED while queued so the user cannot file the same dispute twice
     * (`canOpenDispute` reads `state`), so the marker carries the caveat.
     */
    async openDispute(
      id: string | number,
      // `Blob[]` et non `File[]` : ce store ne lit AUCUN champ propre à `File` — il
      // recopie les octets dans un `FormData` et dans la file, où ils repartent en clone
      // structuré. C'est le formulaire qui manipule des `File` (il en lit le nom pour
      // valider) ; l'imposer ici obligeait les suites à fabriquer des `File` factices
      // pour un code qui n'en a jamais eu besoin.
      { files, comment }: { files: Blob[]; comment: string },
    ): Promise<DisputeOpened | null> {
      const offlineQueue = useOfflineQueueStore()

      if (!offlineQueue.isOnline) {
        // Without at least one file, `flush()` would route the entry down its
        // JSON branch (it dispatches on `files.length`) and POST it to an
        // endpoint that only consumes multipart — a 400 no retry can clear.
        if (!files?.length) throw new Error('A dispute needs at least one evidence file.')

        // Queue first, show second: the transaction already exists, so unlike
        // `createNewTransaction` there is nothing to roll back — a rejected
        // enqueue simply leaves the screen untouched and throws to the form.
        await offlineQueue.enqueue({
          method: 'post',
          url: `/api/v1/escrow/${id}/dispute`,
          // `files` at the root, not in `data`: that is where `buildFormData()`
          // reads the repeated `files` parts from.
          files: [...files],
          // Stamped when the user submitted, not when they shot the photo: the
          // replay may land hours later, so without this the audit trail would
          // only ever hold the reconnection time (AD-11).
          data: { comment, clientCapturedAt: new Date().toISOString() },
          meta: { type: 'OPEN_DISPUTE', transactionId: id, userId: useAuthStore().user?.id },
        })

        if (this.currentDetail?.transaction && String(this.currentDetail.transaction.id) === String(id)) {
          this.currentDetail.transaction.state = 'DISPUTED'
          this.currentDetail.transaction._queuedDispute = true
        }
        const queuedIndex = this.transactions.findIndex((t) => String(t.id) === String(id))
        if (queuedIndex !== -1) {
          this.transactions[queuedIndex] = {
            ...this.transactions[queuedIndex],
            state: 'DISPUTED',
            _queuedDispute: true,
          }
        }

        return null
      }

      const form = new FormData()
      for (const f of files) form.append('files', f) // exact repeated key 'files'
      form.append('comment', comment) // required here (>=10 chars, validated client + server)

      const dto = await openDispute(id, form)

      if (this.currentDetail?.transaction && String(this.currentDetail.transaction.id) === String(id)) {
        this.currentDetail.transaction = dto.transaction
      }
      const index = this.transactions.findIndex((t) => String(t.id) === String(id))
      if (index !== -1) this.transactions[index] = dto.transaction

      return dto
    },
  },
})
