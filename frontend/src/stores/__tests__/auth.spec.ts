import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { loginUser, logoutUser, resendVerification } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { aUser } from '@/test-support/factories'

// Story 1.6 — le logout() du store révoque la session côté serveur.
vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
}))
vi.mock('@/api/client', () => ({
  default: { request: vi.fn() },
  TOKEN_STORAGE_KEY: 'escrow_token',
  resetSessionExpiryLatch: vi.fn(),
}))

describe('auth store logout (Story 1.6)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })
  afterEach(() => localStorage.clear())

  it('vide l\'état local ET révoque la session serveur avec le jeton courant', async () => {
    const auth = useAuthStore()
    auth.applySession({ token: 'jwt-abc', user: aUser({ id: 1, role: 'BUYER' }) })

    const pending = auth.logout()

    // Vidage local synchrone et immédiat : la déconnexion client ne dépend pas du réseau.
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('escrow_token')).toBeNull()

    await pending
    expect(logoutUser).toHaveBeenCalledWith('jwt-abc')
  })

  it('retourne une promesse attendable — l\'appelant navigue APRÈS la révocation', async () => {
    // Revue 1.6 : le fire-and-forget était avorté par window.location, si bien que
    // le jeton restait accepté côté serveur jusqu'à expiration. C'est l'attente qui
    // rend AC #2 vrai dans le seul parcours réel, pas seulement en test.
    const auth = useAuthStore()
    auth.applySession({ token: 'jwt-abc', user: aUser({ id: 1, role: 'BUYER' }) })

    let resolveRevocation: (() => void) | undefined
    vi.mocked(logoutUser).mockReturnValueOnce(new Promise((resolve) => { resolveRevocation = resolve }))

    const pending = auth.logout()
    expect(pending).toBeInstanceOf(Promise)

    let settled = false
    pending.then(() => { settled = true })
    await Promise.resolve()
    expect(settled).toBe(false) // toujours en attente du serveur

    resolveRevocation!()
    await pending
    expect(settled).toBe(true)
  })

  it('résout quand même si la révocation serveur échoue (hors ligne, jeton déjà mort)', async () => {
    const auth = useAuthStore()
    auth.applySession({ token: 'jwt-abc', user: aUser({ id: 1, role: 'BUYER' }) })
    vi.mocked(logoutUser).mockRejectedValueOnce(new Error('network down'))

    await expect(auth.logout()).resolves.toBeUndefined()
    expect(auth.token).toBeNull()
  })

  it('n\'appelle pas le serveur si aucun jeton n\'est présent', async () => {
    const auth = useAuthStore()
    await auth.logout()
    expect(logoutUser).not.toHaveBeenCalled()
  })
})


/**
 * Ce que le store fait d'une erreur d'API — la lacune trouvée pendant la migration.
 *
 * <p>Aucune assertion ne couvrait ces deux lectures. Le store est passé de
 * `err.response?.data?.message` aux fonctions de `utils/apiError.ts`, et une première
 * version de celles-ci filtrait par `axios.isAxiosError` : tout rejet fabriqué à la main
 * — donc tout rejet de test — retombait silencieusement sur le repli. La suite serait
 * restée VERTE avec un message serveur qui ne s'affiche plus, et le compte à rebours de
 * renvoi de code aurait perdu l'horloge serveur d'AD-11 sans que rien ne le dise.
 *
 * <p>La forme des rejets ci-dessous est délibérément celle d'un objet nu, et non d'une
 * `AxiosError` : c'est ce que produisent les suites, et c'est précisément le cas que le
 * filtrage cassait.
 */
describe('lecture des erreurs d\'API (migration TypeScript)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.clear()
    vi.clearAllMocks()
  })
  afterEach(() => localStorage.clear())

  it('affiche le message du SERVEUR, pas le repli générique', async () => {
    vi.mocked(loginUser).mockRejectedValueOnce({
      response: { status: 401, data: { code: 'AUTH_FAILED', message: 'Compte verrouillé pendant 15 minutes.' } },
    })
    const auth = useAuthStore()

    expect(await auth.login({ email: 'a@corp.example', password: 'x' })).toBe(false)
    expect(auth.error).toBe('Compte verrouillé pendant 15 minutes.')
  })

  it('retombe sur le repli quand la réponse ne porte aucun message', async () => {
    vi.mocked(loginUser).mockRejectedValueOnce(new Error('panne réseau'))
    const auth = useAuthStore()

    expect(await auth.login({ email: 'a@corp.example', password: 'x' })).toBe(false)
    expect(auth.error).toBe('Invalid email or password.')
  })

  it('lit le délai de renvoi dans l\'en-tête Retry-After du serveur (AD-11)', async () => {
    vi.mocked(resendVerification).mockRejectedValueOnce({
      response: { status: 429, headers: { 'retry-after': '42' }, data: {} },
    })
    const auth = useAuthStore()

    expect(await auth.resend({ email: 'a@corp.example' })).toEqual({ ok: false, retryAfterSeconds: 42 })
  })

  it('ne rend jamais un compte à rebours NaN quand l\'en-tête manque', async () => {
    vi.mocked(resendVerification).mockRejectedValueOnce({ response: { status: 429, headers: {}, data: {} } })
    const auth = useAuthStore()

    expect(await auth.resend({ email: 'a@corp.example' })).toEqual({ ok: false, retryAfterSeconds: 0 })
  })
})
