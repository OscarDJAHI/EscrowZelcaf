import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { logoutUser } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'

// Story 1.6 — le logout() du store révoque la session côté serveur.
vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
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
    auth.applySession({ token: 'jwt-abc', user: { id: 1, role: 'BUYER' } })

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
    auth.applySession({ token: 'jwt-abc', user: { id: 1, role: 'BUYER' } })

    let resolveRevocation
    logoutUser.mockReturnValueOnce(new Promise((resolve) => { resolveRevocation = resolve }))

    const pending = auth.logout()
    expect(pending).toBeInstanceOf(Promise)

    let settled = false
    pending.then(() => { settled = true })
    await Promise.resolve()
    expect(settled).toBe(false) // toujours en attente du serveur

    resolveRevocation()
    await pending
    expect(settled).toBe(true)
  })

  it('résout quand même si la révocation serveur échoue (hors ligne, jeton déjà mort)', async () => {
    const auth = useAuthStore()
    auth.applySession({ token: 'jwt-abc', user: { id: 1, role: 'BUYER' } })
    logoutUser.mockRejectedValueOnce(new Error('network down'))

    await expect(auth.logout()).resolves.toBeUndefined()
    expect(auth.token).toBeNull()
  })

  it('n\'appelle pas le serveur si aucun jeton n\'est présent', async () => {
    const auth = useAuthStore()
    await auth.logout()
    expect(logoutUser).not.toHaveBeenCalled()
  })
})
