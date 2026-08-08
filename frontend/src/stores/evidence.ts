import { defineStore } from 'pinia'
import { downloadEvidence, listEvidence, uploadEvidence, withdrawEvidence } from '@/api/evidence'
import { saveBlob } from '@/utils/download'
import { apiErrorMessage } from '@/utils/apiError'
import type { EvidenceItem } from '@/types/domain'

interface EvidenceState {
  items: EvidenceItem[]
  loading: boolean
  error: string | null
  uploading: boolean
  /** Which transaction `items` belongs to. `string | number` : l'id vient de la route. */
  loadedId: string | number | null
  /** Request token guarding against out-of-order responses. */
  loadSeq: number
}

export const useEvidenceStore = defineStore('evidence', {
  state: (): EvidenceState => ({
    items: [],
    loading: false,
    error: null,
    uploading: false,
    loadedId: null,
    loadSeq: 0,
  }),

  actions: {
    async loadEvidence(id: string | number): Promise<void> {
      const seq = ++this.loadSeq
      // Switching transactions: drop the previous transaction's evidence
      // immediately so its documents are never shown under another one.
      if (id !== this.loadedId) this.items = []
      this.loading = true
      this.error = null
      try {
        const data = await listEvidence(id)
        if (seq !== this.loadSeq) return // a newer load superseded this one
        this.items = Array.isArray(data) ? data : []
        this.loadedId = id
      } catch (err) {
        if (seq !== this.loadSeq) return
        this.error = apiErrorMessage(err) || 'Unable to load the evidence for this transaction.'
      } finally {
        if (seq === this.loadSeq) this.loading = false
      }
    },

    /**
     * Uploads evidence online. Builds the FormData with the frozen part names
     * (AD-13). Errors bubble up so the deposit component can surface the server
     * message and keep the form intact. The caller refreshes the list via the
     * `uploaded` event (single fetch — no redundant reload here).
     */
    async uploadEvidence(
      id: string | number,
      {
        files,
        comment,
        clientCapturedAt,
      }: { files: File[]; comment?: string | null; clientCapturedAt?: string | null },
    ): Promise<EvidenceItem[]> {
      this.uploading = true
      try {
        const form = new FormData()
        for (const file of files) form.append('files', file) // exact repeated key 'files'
        if (comment) form.append('comment', comment)
        if (clientCapturedAt) form.append('clientCapturedAt', clientCapturedAt)
        return await uploadEvidence(id, form)
      } finally {
        this.uploading = false
      }
    },

    /**
     * Logically withdraws one of the current user's active evidence items.
     * Online only. On success the returned EvidenceDto (status: WITHDRAWN)
     * replaces the matching item in place — the row stays at its chronological
     * position with its badge flipped. Errors propagate to the component.
     */
    async withdrawEvidence(
      id: string | number,
      evidenceId: string | number,
    ): Promise<EvidenceItem> {
      const dto = await withdrawEvidence(id, evidenceId)
      // Invalidate any in-flight loadEvidence: its stale response (which predates
      // this withdrawal and would show the piece as ACTIVE) must bail on the
      // `seq !== this.loadSeq` guard rather than overwrite the flip below.
      this.loadSeq++
      // The invalidated load will bail before its `finally` clears `loading`
      // (its `seq === this.loadSeq` guard is now false) and no replacement load
      // is started here, so this withdrawal owns the terminal loading state —
      // clear it to avoid stranding the spinner / any :disabled="loading" control.
      this.loading = false
      this.items = this.items.map((i) => (i.id === dto.id ? dto : i))
      return dto
    },

    /**
     * Fetches the file as a Blob (so the JWT is carried) and triggers a browser
     * save via a synthetic <a download>, revoking the ObjectURL afterwards.
     */
    async downloadFile(id: string | number, item: EvidenceItem): Promise<void> {
      const blob = await downloadEvidence(id, item.id)
      // The save itself is `utils/download.js` since Story 4.5 gave it a second
      // caller. The fetch above stays here: it is what this action is *for* —
      // carrying the JWT — and it is precisely the half the recovery screen has
      // nothing to do with, its bytes never having left the device.
      saveBlob(blob, item.originalFilename)
    },
  },
})
