import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import AuthView from '@/views/AuthView.vue'
import { createEscrowI18n } from '@/i18n'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import { PERSISTENCE_PREFERENCE_KEY, writePersistence } from '@/utils/credentialStorage'
import type { Pinia } from 'pinia'

/**
 * L'option « rester connecté » (Story 2.7, AC1).
 *
 * <p>Ce que cette suite garde, et qu'aucune autre ne pouvait garder : la case décide
 * réellement d'OÙ ATTERRIT LE JETON. Une assertion sur la seule préférence enregistrée
 * serait creuse — elle passerait aussi bien si `login` écrivait toujours au même endroit.
 * On assère donc le substrat de destination, qui est la propriété que l'AC nomme.
 */

vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
}))

vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

import { loginUser } from '@/api/auth'

let pinia: Pinia

function mountAuth() {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
      { path: '/auth', name: 'auth', component: AuthView },
      { path: '/verify-email', name: 'verify-email', component: { template: '<div />' } },
    ],
  })
  return mount(AuthView, {
    global: { plugins: [pinia, router, createEscrowI18n('en')] },
  })
}

async function submitLogin(wrapper: ReturnType<typeof mountAuth>, remember: boolean) {
  await wrapper.find('input[type="email"]').setValue('pro@escrow.test')
  await wrapper.find('input[type="password"]').setValue('correct horse battery')
  // `setValue` INCONDITIONNEL, dans les deux sens. Une première version ne cochait que
  // pour `true` et laissait la case telle quelle sinon : sur un poste dont la préférence
  // était déjà « local », la case partait cochée et le test « décoché » ne décochait rien.
  // Il vérifiait alors le contraire de ce que son nom annonçait — et il l'a dit.
  await wrapper.find('[data-testid="remember-me"]').setValue(remember)
  await wrapper.find('form').trigger('submit')
  await flushPromises()
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
  vi.mocked(loginUser).mockResolvedValue({
    token: 'jeton-de-session',
    user: { id: 7, email: 'pro@escrow.test', role: 'BUYER' },
  } as never)
})

afterEach(() => {
  vi.restoreAllMocks()
  localStorage.clear()
  sessionStorage.clear()
})

describe('la case « rester connecté »', () => {
  it('est présente en connexion et ABSENTE en inscription', async () => {
    const wrapper = mountAuth()
    expect(wrapper.find('[data-testid="remember-me"]').exists()).toBe(true)

    // L'inscription n'ouvre pas de session (Story 2.4 : c'est `verify` qui le fait).
    // Offrir le choix là promettrait un effet qui n'aurait pas lieu.
    await wrapper.findAll('button[type="button"]')[1]!.trigger('click')
    expect(wrapper.find('[data-testid="remember-me"]').exists()).toBe(false)
  })

  it('part DÉCOCHÉE quand aucune préférence n\'est enregistrée', () => {
    const wrapper = mountAuth()
    // Décochée par défaut, c'est la décision D4 : un appareil dont on ne sait rien
    // est traité comme un appareil partagé.
    expect((wrapper.find('[data-testid="remember-me"]').element as HTMLInputElement).checked).toBe(
      false,
    )
  })

  it('part COCHÉE quand l\'appareil a déjà exprimé la préférence', () => {
    writePersistence('local')
    const wrapper = mountAuth()
    // Sans cela, l'utilisateur d'un poste personnel re-cocherait à chaque connexion
    // et l'option ne tiendrait pas la promesse qui la justifie.
    expect((wrapper.find('[data-testid="remember-me"]').element as HTMLInputElement).checked).toBe(
      true,
    )
  })

  it('décochée : le jeton atterrit en sessionStorage et NULLE PART ailleurs', async () => {
    const wrapper = mountAuth()
    await submitLogin(wrapper, false)

    // La propriété que l'AC1 nomme : le jeton meurt avec l'onglet.
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jeton-de-session')
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(PERSISTENCE_PREFERENCE_KEY)).toBe('session')
  })

  it('cochée : le jeton atterrit en localStorage et NULLE PART ailleurs', async () => {
    const wrapper = mountAuth()
    await submitLogin(wrapper, true)

    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jeton-de-session')
    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(localStorage.getItem(PERSISTENCE_PREFERENCE_KEY)).toBe('local')
  })

  it('la préférence est posée AVANT l\'écriture du jeton, pas après', async () => {
    // Le poste a déjà servi à quelqu'un qui avait coché la case.
    writePersistence('local')
    const wrapper = mountAuth()
    // La personne suivante DÉCOCHE. Si la préférence était posée après `login`, le
    // jeton partirait d'abord en localStorage — le substrat encore actif — puis on
    // déclarerait sessionStorage actif : le jeton serait illisible, la session
    // paraîtrait ouverte et le premier appel API partirait sans en-tête. Rien ne
    // lèverait. C'est l'ordre, et lui seul, que ce test verrouille.
    await submitLogin(wrapper, false)

    expect(sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('jeton-de-session')
    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
  })
})
