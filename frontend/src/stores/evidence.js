import { defineStore } from 'pinia'
import { downloadEvidence, listEvidence, uploadEvidence, withdrawEvidence } from '@/api/evidence'

export const useEvidenceStore = defineStore('evidence', {
  state: () => ({
    items: [],
    loading: false,
    error: null,
    uploading: false,
    loadedId: null, // which transaction `items` belongs to
    loadSeq: 0, // request token guarding against out-of-order responses
  }),

  actions: {
    async loadEvidence(id) {
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
        this.error = err.response?.data?.message || 'Unable to load the evidence for this transaction.'
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
    async uploadEvidence(id, { files, comment, clientCapturedAt }) {
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
    async withdrawEvidence(id, evidenceId) {
      const dto = await withdrawEvidence(id, evidenceId)
      this.items = this.items.map((i) => (i.id === dto.id ? dto : i))
      return dto
    },

    /**
     * Fetches the file as a Blob (so the JWT is carried) and triggers a browser
     * save via a synthetic <a download>, revoking the ObjectURL afterwards.
     */
    async downloadFile(id, item) {
      const blob = await downloadEvidence(id, item.id)
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = item.originalFilename
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    },
  },
})
