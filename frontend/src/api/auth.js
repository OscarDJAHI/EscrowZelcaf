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
