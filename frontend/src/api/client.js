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
 * Is this failure about the session stored *right now*?
 *
 * Two things would make the announcement wrong. There may be no session at all
 * — an anonymous endpoint has nothing to tear down and no reason to navigate.
 * Or the failure may belong to a session that is already over: a slow request
 * (a multipart replay, an evidence download) can outlive the sign-out it
 * triggered and land seconds after somebody has signed back in, and announcing
 * it then would tear down the *new* session and bounce a user who just typed
 * their password. `api/auth.js` documents that hazard for `logoutUser` and
 * routes around this interceptor to avoid it; every other call goes through
 * here.
 *
 * Fails OPEN on purpose: only a request whose `Authorization` we can positively
 * read AND positively tell apart from the stored one is dismissed as a
 * straggler. If the header is unreadable for any reason the failure is treated
 * as current — a spurious sign-in screen is a nuisance, a revoked token left in
 * an authenticated UI is the defect Story 1.6 made routine.
 */
function concernsCurrentSession(error) {
  const currentToken = localStorage.getItem(TOKEN_STORAGE_KEY)
  if (!currentToken) return false

  const sent = error?.config?.headers?.Authorization
  return typeof sent !== 'string' || sent === `Bearer ${currentToken}`
}

/**
 * Could this response body have carried an error envelope at all?
 *
 * `isBareAuthFailure` reads "no `code`" as "no envelope, therefore a dead
 * session". That inference only holds if the body was parsed as JSON. The
 * evidence download asks for `responseType: 'blob'` (`api/evidence.js:30`), so
 * axios hands back a Blob and `data.code` is `undefined` whatever the server
 * wrote — `EvidenceList.vue:29-31` re-reads that Blob by hand for precisely this
 * reason. Without this guard a 403 NOT_A_PARTY on a download, an ordinary
 * business verdict, would sign the user out and wipe their screen: the one thing
 * this story's rules forbid outright.
 *
 * Deliberately errs towards NOT announcing. A session that is genuinely dead
 * fails every other call too, and every screen makes JSON calls, so the teardown
 * is at worst deferred to the next one — whereas announcing on an unreadable
 * body turns every authorization verdict on a binary endpoint into a sign-out.
 *
 * Only the default (absent) and explicit `'json'` are let through: `'text'`,
 * `'arraybuffer'` and `'stream'` are just as unable to expose a `code`.
 */
function envelopeWasParsed(error) {
  const responseType = error?.config?.responseType
  return responseType == null || responseType === 'json'
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
 * `typeof window` is tested FIRST and not last: every term after it touches
 * `localStorage`, which is just as absent from a non-DOM runtime as `window`
 * is, so a trailing guard would be dead code that throws a ReferenceError one
 * term before it ever runs — and that ReferenceError would replace the caller's
 * AxiosError, turning a permanent server rejection into an unclassifiable one
 * that `classifyReplayFailure` reads as transient and re-queues forever.
 */
apiClient.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      typeof window !== 'undefined' &&
      !sessionExpiryAnnounced &&
      envelopeWasParsed(error) &&
      isBareAuthFailure(extractFailureReason(error)) &&
      concernsCurrentSession(error)
    ) {
      sessionExpiryAnnounced = true
      window.dispatchEvent(new CustomEvent('escrow:session-expired'))
    }
    return Promise.reject(error)
  },
)

export default apiClient
