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
 * Mirror of the `Retryability.TRANSIENT` codes of
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
  // Story 1.3 — anti-bruteforce auth (429) : se resorbe seul (Retry-After).
  'RATE_LIMITED',
  'FILE_READ_ERROR',
  'STORAGE_UNAVAILABLE',
  // Revue 1.6 — filet de sécurité du GlobalExceptionHandler : un défaut interne
  // (panne de base, indisponibilité passagère) est circonstanciel, donc rejouable.
  'INTERNAL_ERROR',
  // Story 1.8 — l'analyse antivirus à l'ingestion n'a pas pu rendre de verdict
  // (moteur injoignable, timeout). Le fichier n'est PAS en cause : sans cette
  // entrée, le défaut « permanent » gèlerait définitivement une preuve légitime
  // sur une simple panne d'infrastructure.
  'SCAN_UNAVAILABLE',
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
    // axios-internal string ("Request failed with status code 404") to show a
    // user as if it were a verdict. The same reason `code` refuses to guess.
    message: res?.data?.message ?? null,
  }
}

/**
 * User-facing sentence for each `Retryability.PERMANENT` code of
 * `backend/src/main/java/com/zlecaf/escrow/domain/ErrorCode.java` that a frozen
 * entry can actually carry — i.e. every one but `AUTH_FAILED`, which only the
 * partner endpoints raise and which no queued request can ever hit.
 *
 * The label is derived from the `code` and never from the envelope's `message`:
 * `ErrorCode.java:8-11` declares that text interpolated, localisable and locked
 * by no contract, so a backend refactor may reword it without breaking a thing.
 * The `code` is the contract (Story 5.3), so it is what the wording hangs on.
 *
 * English, like the rest of the chrome (`OnlineBanner.vue`) and like the server
 * messages themselves — the repo carries no i18n infrastructure.
 *
 * A subset, not a mirror: only the reachable codes are spelled out, and an
 * unknown one degrades through `describeFailure` rather than crashing. The
 * drift guard in `__tests__/replayFailure.spec.js` holds the other direction —
 * no key here may name a code the PERMANENT partition does not declare.
 */
export const FAILURE_LABELS = Object.freeze({
  DISPUTE_ALREADY_RESOLVED: 'This dispute had already been arbitrated.',
  TRANSACTION_TERMINAL: 'This transaction was already closed: nothing more can happen to it.',
  ILLEGAL_TRANSITION: 'This action is not allowed from the state the transaction had reached.',
  UNAUTHORIZED_TRANSITION: 'Your role is not allowed to perform this action on this transaction.',
  WINDOW_CLOSED: 'Evidence can no longer be changed at this stage of the transaction.',
  EVIDENCE_INVALID: 'One of the attached files was refused (empty, wrong type, or too large).',
  // Story 1.8 — verdict d'un scan antivirus à l'ingestion. Rejouer le même fichier
  // redéclencherait à l'identique : l'entrée est gelée et ce libellé dit quoi faire
  // (retirer la pièce), sans jamais nommer la signature — le serveur ne la renvoie
  // pas, et le front n'a donc rien à en dire.
  EVIDENCE_MALWARE_DETECTED:
    'One of the attached files was refused by the antivirus scan. Remove it and attach a clean copy.',
  EVIDENCE_FLOOR_VIOLATION: 'A disputed transaction must keep at least one piece of evidence.',
  TOO_MANY_FILES: 'Too many files were attached for a single deposit.',
  COMMENT_TOO_SHORT: 'The comment was missing or too short.',
  NOT_A_PARTY: 'You are not a party to this transaction.',
  TRANSACTION_NOT_FOUND: 'This transaction no longer exists.',
  VALIDATION_ERROR: 'The server rejected the details of this request.',
  // Revue 1.6 : le backend énumère les règles effectives (longueur en octets et
  // nombre de catégories, tous trois configurables) dans le `message`. Ce libellé
  // est le repli quand aucun message ne parvient — `describeFailure` préfère
  // désormais le message serveur pour ce code, afin que l'AC #1 (« les règles
  // explicitées ») tienne aussi côté UI.
  WEAK_PASSWORD: 'The password does not meet the security policy (length and character variety).',
  MISSING_REQUEST_PART: 'Part of this request never reached the server.',
  INVALID_REQUEST: 'The server rejected this request.',
  RESOURCE_NOT_FOUND: 'What this action referred to no longer exists.',
  CONFLICT: 'This action conflicted with the transaction as the server holds it.',
  FORBIDDEN: 'You are not allowed to perform this action.',
})

/** Shown when neither a known code nor a server message says anything usable. */
const GENERIC_FAILURE_LABEL = 'The server refused this action.'

/**
 * The one sentence telling the user why a queued action was refused.
 *
 * The fallback order is the contract, and each step is a step down in trust:
 * the label of a known `code` (stable, ours) → the server `message` (real
 * detail, but no contract holds its wording) → a generic refusal. Never the
 * raw `code`: `SOME_NEW_CODE` is not a sentence.
 *
 * Pure — no store, no clock, no network — so Story 4.5 can reuse it as-is.
 * @param {{code: string|null, message: string|null}|null|undefined} failure as `extractFailureReason` shapes it
 * @returns {string}
 */
export function describeFailure(failure) {
  const code = failure?.code
  const message = failure?.message
  const hasMessage = typeof message === 'string' && message.trim() !== ''

  // Exception à l'ordre ci-dessus (revue 1.6) : pour WEAK_PASSWORD le `message`
  // est PLUS informatif que le libellé, et l'AC #1 exige que les règles effectives
  // soient explicitées. Les seuils sont configurables (`escrow.auth.password.*`),
  // donc un libellé figé côté client mentirait dès qu'un exploitant les change.
  if (code === 'WEAK_PASSWORD' && hasMessage) return message

  // `hasOwn`, not `FAILURE_LABELS[code]`: `code` comes off a server response, so
  // a lookup that walks the prototype would let 'constructor' return a function.
  if (typeof code === 'string' && Object.hasOwn(FAILURE_LABELS, code)) return FAILURE_LABELS[code]

  if (hasMessage) return message

  return GENERIC_FAILURE_LABEL
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
