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
vi.mock('@/api/client', () => ({ default: { request: vi.fn() }, TOKEN_STORAGE_KEY: 'escrow_token' }))

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

    auth.logout()

    // Vidage local synchrone et immédiat.
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    expect(localStorage.getItem('escrow_token')).toBeNull()
    // Révocation serveur déclenchée avec le jeton révoqué (fire-and-forget, microtâche).
    await Promise.resolve()
    expect(logoutUser).toHaveBeenCalledWith('jwt-abc')
  })

  it('n\'appelle pas le serveur si aucun jeton n\'est présent', async () => {
    const auth = useAuthStore()
    auth.logout()
    await Promise.resolve()
    expect(logoutUser).not.toHaveBeenCalled()
  })
})
