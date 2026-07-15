import apiClient from './client'

/** @returns {Promise<Array>} the current user's escrow transactions */
export function fetchTransactions() {
  return apiClient.get('/api/v1/escrow').then((res) => res.data)
}

/**
 * @param {{sellerEmail: string, amount: number, currency: string, description: string}} payload
 */
export function createTransaction(payload) {
  return apiClient.post('/api/v1/escrow', payload).then((res) => res.data)
}

/** @returns {Promise<{transaction: object, auditLogs: Array}>} */
export function fetchTransactionDetail(id) {
  return apiClient.get(`/api/v1/escrow/${id}`).then((res) => res.data)
}

/**
 * @param {string|number} id
 * @param {string} event one of PAY_FUNDS, SHIP_GOODS, DELIVERY_CONFIRMED, OPEN_DISPUTE, RESOLVE_RELEASE, RESOLVE_REFUND
 */
export function sendTransactionEvent(id, event) {
  return apiClient.post(`/api/v1/escrow/${id}/event`, { event }).then((res) => res.data)
}
