import apiClient from './client'

/** @returns {Promise<Array>} evidence items for a transaction (chronological) */
export function listEvidence(id) {
  return apiClient.get(`/api/v1/escrow/${id}/evidence`).then((res) => res.data)
}

/**
 * Uploads one or more evidence files as multipart/form-data.
 * The explicit Content-Type lets axios 1.x compute the multipart boundary
 * (the shared client otherwise defaults to application/json).
 * @param {string|number} id
 * @param {FormData} formData parts: `files` (repeated), `comment?`, `clientCapturedAt?`
 * @returns {Promise<Array>} the created EvidenceDto[]
 */
export function uploadEvidence(id, formData) {
  return apiClient
    .post(`/api/v1/escrow/${id}/evidence`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((res) => res.data)
}

/**
 * Downloads a single evidence file as a Blob (JWT carried by the interceptor).
 * @returns {Promise<Blob>} the binary attachment
 */
export function downloadEvidence(id, evidenceId) {
  return apiClient
    .get(`/api/v1/escrow/${id}/evidence/${evidenceId}/download`, { responseType: 'blob' })
    .then((res) => res.data)
}
