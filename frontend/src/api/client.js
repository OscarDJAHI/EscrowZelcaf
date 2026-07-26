import axios from 'axios'
import { extractFailureReason, isBareAuthFailure } from '@/utils/replayFailure'

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

/**
 * Raised once the session has been announced dead, and lowered only by
 * `beginSession()` (`stores/session.js`) — never by a timer, which would reopen
 * the window at an arbitrary moment. A screen loading three resources in
 * parallel produces three bare 403s: without the latch that is three teardowns
 * and three concurrent navigations.
 */
let sessionExpiryAnnounced = false

/** Lets the next sign-in re-arm the announcement. Called by `beginSession()`. */
export function resetSessionExpiryLatch() {
  sessionExpiryAnnounced = false
}

/**
 * A bare 401/403 means the stored session is dead (see `isBareAuthFailure` for
 * why this backend answers 403 and why a *coded* one must be left alone).
 *
 * All this file does about it is dispatch an event. It imports neither the
 * router nor a store on purpose: `router → stores/auth → api/auth → api/client`
 * closes a cycle, and the winner of the evaluation race would be a half-built
 * axios client. `escrow:sync` (`stores/offlineQueue.js`) already establishes the
 * pattern. `stores/session.js` is what listens and owns the teardown.
 *
 * The token gate keeps an anonymous endpoint from ending a session that never
 * existed — nothing to tear down, and no reason to navigate.
 */
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      !sessionExpiryAnnounced &&
      isBareAuthFailure(extractFailureReason(error)) &&
      localStorage.getItem(TOKEN_STORAGE_KEY) &&
      typeof window !== 'undefined'
    ) {
      sessionExpiryAnnounced = true
      window.dispatchEvent(new CustomEvent('escrow:session-expired'))
    }
    return Promise.reject(error)
  },
)

export default apiClient
