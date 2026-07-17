/**
 * Verdict on a failed replay from the offline queue: may the identical request
 * plausibly succeed later ('transient'), or will it fail identically forever
 * ('permanent')?
 *
 * The verdict is read from the `code` of the error envelope, never from the
 * `message` (interpolated, locked by no contract) and never from the HTTP class
 * alone — 400 and 409 each carry both verdicts (FILE_READ_ERROR is a retryable
 * 400; DISPUTE_ALREADY_RESOLVED is a definitive 409).
 */

/**
 * Mirror of the three `Retryability.TRANSIENT` codes of
 * `backend/src/main/java/com/zlecaf/escrow/domain/ErrorCode.java` — that file is
 * the authority; the envelope does not serialise retryability (deliberate: the
 * policy belongs to the client), so the front has to mirror it.
 *
 * Only the transient ones are enumerated, everything else being permanent by
 * default: a new *permanent* backend code is then classified correctly with no
 * front change, and the one dangerous case — a new *transient* code — turns
 * `ErrorCodeContractTest.transientPartitionIsExact` (an exact-equality
 * assertion) red instead of silently freezing an entry that deserved a retry.
 */
export const TRANSIENT_CODES = new Set([
  'CONCURRENT_MODIFICATION',
  'FILE_READ_ERROR',
  'STORAGE_UNAVAILABLE',
])

/**
 * The machine-readable reason behind a replay failure, extracted once so no
 * consumer has to re-parse the AxiosError shape.
 *
 * `code` is null whenever the response was not built by `@RestControllerAdvice`
 * (Spring Security's 401, a routing 404, the default `/error`): those carry no
 * envelope, and no code must ever be inferred for them.
 * @param {object} err an AxiosError, or any rejection from the shared client
 * @returns {{code: string|null, status: number|null, message: string|null}}
 */
export function extractFailureReason(err) {
  const res = err?.response
  return {
    code: res?.data?.code ?? null,
    status: res?.status ?? null,
    // No envelope, no message: falling back on `err.message` would hand 4.4 an
    // axios-internal English string ("Request failed with status code 404") to
    // show a French-speaking user. The same reason `code` refuses to guess.
    message: res?.data?.message ?? null,
  }
}

/**
 * The order of the rules is the contract: the status decides *before* the code,
 * because a code may be absent.
 * @param {object} err an AxiosError, or any rejection from the shared client
 * @returns {'transient' | 'permanent'}
 */
export function classifyReplayFailure(err) {
  const res = err?.response
  if (!res) return 'transient' // network outage / timeout: no response at all

  const { status } = res
  if (status === 408 || status === 429 || status >= 500) return 'transient'

  const code = res.data?.code ?? null

  // Session expired: the request is valid again once re-authenticated, so this
  // must never freeze evidence. The *absence* of an envelope is what identifies
  // it — an expired JWT never reaches `@RestControllerAdvice`: `JwtAuthFilter`
  // clears the context and `anyRequest().authenticated()` rejects it through
  // Spring Security's default entry point, which — with no httpBasic, formLogin
  // or custom AuthenticationEntryPoint in `SecurityConfig` — is
  // Http403ForbiddenEntryPoint. Hence 403, not the 401 one would expect, and
  // hence no code. A *coded* 401/403 is a real verdict (NOT_A_PARTY,
  // UNAUTHORIZED_TRANSITION, AUTH_FAILED) and is left to the rules below.
  if (code === null && (status === 401 || status === 403)) return 'transient'

  if (TRANSIENT_CODES.has(code)) return 'transient'

  // Coded 4xx, unknown code, or bare 4xx: a client rejection does not repair
  // itself by being replayed.
  return 'permanent'
}
