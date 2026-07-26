import apiClient from './client'

/**
 * @param {{email: string, password: string, firstName: string, lastName: string, role: 'BUYER'|'SELLER'|'ADMIN'}} payload
 */
export function registerUser(payload) {
  return apiClient.post('/api/v1/auth/register', payload).then((res) => res.data)
}

/**
 * @param {{email: string, password: string}} payload
 */
export function loginUser(payload) {
  return apiClient.post('/api/v1/auth/login', payload).then((res) => res.data)
}

/**
 * Déconnexion serveur (Story 1.6, NFR-P5) : révoque la session côté serveur (le
 * jeton présenté n'est plus accepté). Le jeton est passé EXPLICITEMENT pour ne pas
 * dépendre de l'état localStorage : l'appelant peut vider l'état local d'abord.
 * @param {string} token le JWT à révoquer
 */
export function logoutUser(token) {
  return apiClient
    .post('/api/v1/auth/logout', null, { headers: { Authorization: `Bearer ${token}` } })
    .then((res) => res.data)
}
