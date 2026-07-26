import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient, { TOKEN_STORAGE_KEY, resetSessionExpiryLatch } from '@/api/client'

/**
 * The response interceptor is the single discriminator between "this session is
 * over" and "the server said no to this particular thing". Getting it wrong in
 * either direction is a real defect, and neither direction is visible from the
 * store tests: a business verdict that signs the user out destroys their
 * context, and a revoked token that does not leaves them in an authenticated UI
 * where every call fails in silence (the state Story 1.6 made routine).
 *
 * Driven through the real axios instance — not a re-implementation of the
 * predicate — by swapping the adapter for one that hands back the failure under
 * test. Anything less would assert about a copy of the code.
 */

/** Shapes the rejection axios relays: `err.response.data` is the error envelope. */
function httpError(status, data) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data },
  })
}

/** Makes every request fail with `error`, without touching the network. */
function failWith(error) {
  apiClient.defaults.adapter = () => Promise.reject(error)
}

let expired

beforeEach(() => {
  localStorage.clear()
  resetSessionExpiryLatch()
  expired = vi.fn()
  window.addEventListener('escrow:session-expired', expired)
})

afterEach(() => {
  window.removeEventListener('escrow:session-expired', expired)
  delete apiClient.defaults.adapter
  vi.restoreAllMocks()
})

describe('client interceptor — a dead session is announced exactly once', () => {
  it('announces a bare 403, the status this backend really answers on a revoked token', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    failWith(httpError(403, ''))

    await expect(apiClient.get('/api/v1/escrow/42')).rejects.toThrow()

    expect(expired).toHaveBeenCalledOnce()
  })

  it('announces a bare 401 too — the day a real AuthenticationEntryPoint is configured', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    failWith(httpError(401, ''))

    await expect(apiClient.get('/api/v1/escrow')).rejects.toThrow()

    expect(expired).toHaveBeenCalledOnce()
  })

  it('announces once for a burst of three concurrent failures, not three times', async () => {
    // A screen loading three resources in parallel is the ordinary case, and
    // three teardowns would race three navigations against each other.
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    failWith(httpError(403, ''))

    await Promise.allSettled([
      apiClient.get('/api/v1/escrow'),
      apiClient.get('/api/v1/escrow/42'),
      apiClient.get('/api/v1/escrow/42/evidence'),
    ])

    expect(expired).toHaveBeenCalledOnce()
  })

  it('announces again once the latch is lowered — the next session must be protected too', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    failWith(httpError(403, ''))
    await expect(apiClient.get('/api/v1/escrow')).rejects.toThrow()

    // What `beginSession()` does at the next sign-in. Never a timer: that would
    // reopen the window at a moment nothing chose.
    resetSessionExpiryLatch()
    await expect(apiClient.get('/api/v1/escrow')).rejects.toThrow()

    expect(expired).toHaveBeenCalledTimes(2)
  })

  it('always re-rejects, so the caller still sees its own failure', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    const error = httpError(403, '')
    failWith(error)

    await expect(apiClient.get('/api/v1/escrow')).rejects.toBe(error)
  })
})

describe('client interceptor — a verdict is not an expiry', () => {
  it('says nothing on a coded 403 (NOT_A_PARTY): the view renders the refusal', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    failWith(httpError(403, { code: 'NOT_A_PARTY', message: 'You are not a party' }))

    await expect(apiClient.get('/api/v1/escrow/42')).rejects.toThrow()

    expect(expired).not.toHaveBeenCalled()
    // And the credentials are untouched: this interceptor no longer writes to
    // localStorage at all, the teardown belonging to `stores/session.js`.
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-abc')
  })

  it('says nothing on a coded 401 (AUTH_FAILED): a wrong password keeps the form usable', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    failWith(httpError(401, { code: 'AUTH_FAILED', message: 'Invalid credentials' }))

    await expect(apiClient.post('/api/v1/auth/login', {})).rejects.toThrow()

    expect(expired).not.toHaveBeenCalled()
  })

  it('says nothing on a bare 403 when no token is stored', async () => {
    // An anonymous endpoint: there is no session to tear down, and navigating a
    // visitor to the sign-in screen would be an answer to a question nobody asked.
    failWith(httpError(403, ''))

    await expect(apiClient.get('/api/v1/escrow')).rejects.toThrow()

    expect(expired).not.toHaveBeenCalled()
  })

  it('says nothing about a straggler from the session that is already over', async () => {
    // A multipart replay or an evidence download can outlive the sign-out it
    // caused and land seconds after somebody has signed back in. Announcing it
    // then tears down the *new* session and bounces a user who has just typed
    // their password. `api/auth.js` documents this hazard for `logoutUser` and
    // routes around this interceptor; every other call comes through here.
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-old')
    apiClient.defaults.adapter = (config) => {
      // Between the send and the failure: teardown, sign-in, new token — and
      // `beginSession()` lowering the latch, which is what leaves the door open.
      localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-new')
      resetSessionExpiryLatch()
      return Promise.reject(Object.assign(httpError(403, ''), { config }))
    }

    await expect(apiClient.get('/api/v1/escrow/42/evidence')).rejects.toThrow()

    expect(expired).not.toHaveBeenCalled()
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-new')
  })

  it('still announces when the failing request carried the token stored now', async () => {
    // The other half of the rule above, and the assumption it rests on: the
    // request interceptor really does leave `Authorization` readable on the
    // config axios hands back with the error. Asserted outright — were it to
    // stop being readable the guard would fail open, which is the safe
    // direction but would make the test above pass for the wrong reason.
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    let seen
    apiClient.defaults.adapter = (config) => {
      seen = config
      return Promise.reject(Object.assign(httpError(403, ''), { config }))
    }

    await expect(apiClient.get('/api/v1/escrow')).rejects.toThrow()

    expect(seen.headers.Authorization).toBe('Bearer jwt-abc')
    expect(expired).toHaveBeenCalledOnce()
  })

  it('says nothing when the body could not have carried a code — an evidence download', async () => {
    // `isBareAuthFailure` reads "no `code`" as "no envelope, therefore a dead
    // session". That inference only holds for a body axios parsed as JSON. The
    // evidence download asks for `responseType: 'blob'` (`api/evidence.js:30`),
    // so `data.code` is `undefined` whatever the server wrote — which would turn
    // an ordinary NOT_A_PARTY on a download into a sign-out, the one thing this
    // story's rules forbid outright. Driven through the real request so that the
    // `responseType` the guard reads is the one axios really puts on the config.
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    apiClient.defaults.adapter = (config) =>
      Promise.reject(Object.assign(httpError(403, new Blob(['{"code":"NOT_A_PARTY"}'])), { config }))

    await expect(
      apiClient.get('/api/v1/escrow/42/evidence/1/download', { responseType: 'blob' }),
    ).rejects.toThrow()

    expect(expired).not.toHaveBeenCalled()
    // And the session is left intact for the JSON call that will follow: a token
    // genuinely dead fails those too, so the teardown is deferred, never lost.
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jwt-abc')
  })

  it('still announces on the same endpoint when the response was parsed as JSON', async () => {
    // The other half: the guard above must key on how the body was requested and
    // not on the URL, or a revoked token that first shows up on the evidence
    // screen would never be announced at all.
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')
    apiClient.defaults.adapter = (config) =>
      Promise.reject(Object.assign(httpError(403, ''), { config }))

    await expect(apiClient.get('/api/v1/escrow/42/evidence')).rejects.toThrow()

    expect(expired).toHaveBeenCalledOnce()
  })

  it('says nothing on a bare 404 or on a network failure', async () => {
    localStorage.setItem(TOKEN_STORAGE_KEY, 'jwt-abc')

    failWith(httpError(404, ''))
    await expect(apiClient.get('/api/v1/escrow/999')).rejects.toThrow()

    failWith(new Error('network down'))
    await expect(apiClient.get('/api/v1/escrow')).rejects.toThrow()

    expect(expired).not.toHaveBeenCalled()
  })
})
