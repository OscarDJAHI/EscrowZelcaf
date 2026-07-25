import axios from 'axios'

export const TOKEN_STORAGE_KEY = 'escrow_token'

// VITE_API_BASE défini (même "") => on l'utilise tel quel : "" signifie MÊME
// ORIGINE (chemins relatifs /api/v1/... proxifiés par le reverse-proxy, Story 1.4).
// Non défini (dev sans build arg) => défaut historique vers le backend local.
const configuredApiBase = import.meta.env.VITE_API_BASE
const apiClient = axios.create({
  baseURL: configuredApiBase === undefined ? 'http://localhost:8080' : configuredApiBase,
  headers: {
    'Content-Type': 'application/json',
  },
})

// Attach the JWT (if any) to every outgoing request.
apiClient.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// On a 401, the stored session is no longer valid: clear it and
// send the user back to the login screen.
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
      localStorage.removeItem('escrow_user')
      if (typeof window !== 'undefined' && window.location.pathname !== '/auth') {
        window.location.href = '/auth'
      }
    }
    return Promise.reject(error)
  },
)

export default apiClient
