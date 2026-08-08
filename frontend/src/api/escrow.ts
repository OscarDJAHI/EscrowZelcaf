import apiClient from './client'
import type { DisputeOpened, EscrowEventName, Transaction, TransactionDetail } from '@/types/domain'

/** Les transactions escrow de l'utilisateur courant. */
export function fetchTransactions(): Promise<Transaction[]> {
  return apiClient.get<Transaction[]>('/api/v1/escrow').then((res) => res.data)
}

export interface CreateTransactionPayload {
  sellerEmail: string
  amount: number
  currency: string
  description: string
}

export function createTransaction(payload: CreateTransactionPayload): Promise<Transaction> {
  return apiClient.post<Transaction>('/api/v1/escrow', payload).then((res) => res.data)
}

export function fetchTransactionDetail(id: string | number): Promise<TransactionDetail> {
  return apiClient.get<TransactionDetail>(`/api/v1/escrow/${id}`).then((res) => res.data)
}

/** `event` est l'un des six événements de transition de la machine. */
export function sendTransactionEvent(
  id: string | number,
  event: EscrowEventName,
): Promise<Transaction> {
  return apiClient
    .post<Transaction>(`/api/v1/escrow/${id}/event`, { event })
    .then((res) => res.data)
}

/**
 * Opens a dispute via the composite endpoint (evidence is mandatory).
 * Multipart parts: `files` (repeated) and `comment`. The explicit Content-Type
 * lets axios compute the multipart boundary (the shared client defaults to JSON).
 */
export function openDispute(id: string | number, formData: FormData): Promise<DisputeOpened> {
  return apiClient
    .post<DisputeOpened>(`/api/v1/escrow/${id}/dispute`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    .then((res) => res.data)
}
