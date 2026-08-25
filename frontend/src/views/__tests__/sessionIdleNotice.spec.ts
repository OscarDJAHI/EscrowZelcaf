import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { mount } from '@vue/test-utils'
import AuthView from '@/views/AuthView.vue'
import { createEscrowI18n } from '@/i18n'
import en from '@/i18n/en.json'
import fr from '@/i18n/fr.json'
import { endSession } from '@/stores/session'
import { useAuthStore } from '@/stores/auth'
import { IDLE_TIMEOUT_MINUTES } from '@/utils/idleTimeout'
import { aUser } from '@/test-support/factories'
import type { Pinia } from 'pinia'

/**
 * Le motif affiché après une expiration d'inactivité (Story 2.7, AC2 — UX-DR28 : motif ET
 * action de reprise).
 *
 * <p><b>Le motif est posé par la VRAIE primitive</b>, `endSession({reason:'idle'})`, et non
 * par une écriture de test dans le stockage : une fixture qui écrirait elle-même la clé
 * prouverait que l'écran sait lire une clé, pas que la fin de session sait l'écrire — et
 * les deux moitiés de la chaîne pourraient diverger sans que rien ne rougisse.
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

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })
const NOTICE = '[data-testid="session-idle-notice"]'

let pinia: Pinia

function mountAuth(locale = 'en') {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
      { path: '/auth', name: 'auth', component: AuthView },
      { path: '/verify-email', name: 'verify-email', component: { template: '<div />' } },
    ],
  })
  return mount(AuthView, { global: { plugins: [pinia, router, createEscrowI18n(locale)] } })
}

/** Une session posée par la primitive de production, puis abandonnée pour inactivité. */
async function signInThenGoIdle() {
  const auth = useAuthStore()
  auth.token = 'jeton-d-alice'
  auth.user = { ...ALICE }
  auth.persist()
  await endSession({ reason: 'idle' })
}

/** Le texte du catalogue, paramètre substitué — la seule référence qui ne diverge pas. */
function expected(catalogue: { auth: { sessionIdleNotice: string } }) {
  return catalogue.auth.sessionIdleNotice.replace('{minutes}', String(IDLE_TIMEOUT_MINUTES))
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('l’écran d’authentification explique pourquoi la session s’est arrêtée', () => {
  it('affiche EXACTEMENT un motif après une expiration d’inactivité, et plus jamais ensuite', async () => {
    await signInThenGoIdle()

    const wrapper = mountAuth()

    // Compte EXACT : un `data-testid` inexistant fait renvoyer 0 à `findAll`, et une
    // assertion « au plus un » serait satisfaite par le vide.
    expect(wrapper.findAll(NOTICE)).toHaveLength(1)
    // Le délai vient de la constante de production, interpolé — pas d'un « 15 » gravé
    // dans deux traductions qui survivrait à l'ajustement du seuil.
    expect(wrapper.get(NOTICE).text()).toBe(expected(en))
    expect(wrapper.get(NOTICE).text()).toContain(String(IDLE_TIMEOUT_MINUTES))
    // `role="status"` : annoncé sans voler le focus au champ d'adresse, qui EST l'action
    // de reprise (UX-DR28).
    expect(wrapper.get(NOTICE).attributes('role')).toBe('status')

    // Consommé : le second écran ne réexplique pas une expiration déjà expliquée.
    expect(mountAuth().findAll(NOTICE)).toHaveLength(0)
  })

  it('n’affiche RIEN après une déconnexion volontaire, ni sans fin de session du tout', async () => {
    const wrapper = mountAuth()
    expect(wrapper.findAll(NOTICE)).toHaveLength(0)

    const auth = useAuthStore()
    auth.token = 'jeton-d-alice'
    auth.user = { ...ALICE }
    auth.persist()
    await endSession({ reason: 'logout' })

    expect(mountAuth().findAll(NOTICE)).toHaveLength(0)

    // Appariée dans le même test : l'absence ci-dessus ne vaut que si la présence est
    // atteignable par ce même chemin.
    await signInThenGoIdle()
    expect(mountAuth().findAll(NOTICE)).toHaveLength(1)
  })

  it('est traduit — le message français n’est pas le message anglais', async () => {
    await signInThenGoIdle()

    // AD-23 : les deux langues, sinon la CI échoue. Comparé aux DEUX catalogues pour que
    // le test rougisse aussi si l'un d'eux se met à recopier l'autre.
    expect(mountAuth('fr').get(NOTICE).text()).toBe(expected(fr))
    expect(expected(fr)).not.toBe(expected(en))
  })
})
