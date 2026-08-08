import apiClient from './client'
import type { EvidenceItem } from '@/types/domain'

/** Les preuves d'une transaction, dans l'ordre chronologique. */
export function listEvidence(id: string | number): Promise<EvidenceItem[]> {
  return apiClient.get<EvidenceItem[]>(`/api/v1/escrow/${id}/evidence`).then((res) => res.data)
}

/**
 * Uploads one or more evidence files as multipart/form-data.
 * The explicit Content-Type lets axios 1.x compute the multipart boundary
 * (the shared client otherwise defaults to application/json).
 *
 * Parts du formulaire : `files` (répété), `comment?`, `clientCapturedAt?`.
 */
export function uploadEvidence(
  id: string | number,
  formData: FormData,
): Promise<EvidenceItem[]> {
  return apiClient
    .post<EvidenceItem[]>(`/api/v1/escrow/${id}/evidence`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((res) => res.data)
}

/** Downloads a single evidence file as a Blob (JWT carried by the interceptor). */
export function downloadEvidence(
  id: string | number,
  evidenceId: string | number,
): Promise<Blob> {
  return apiClient
    .get<Blob>(`/api/v1/escrow/${id}/evidence/${evidenceId}/download`, { responseType: 'blob' })
    .then((res) => res.data)
}

/**
 * Logically withdraws one of the current user's active evidence items.
 * No request body; the server flips the status to WITHDRAWN.
 */
export function withdrawEvidence(
  id: string | number,
  evidenceId: string | number,
): Promise<EvidenceItem> {
  return apiClient
    .post<EvidenceItem>(`/api/v1/escrow/${id}/evidence/${evidenceId}/withdraw`)
    .then((res) => res.data)
}
