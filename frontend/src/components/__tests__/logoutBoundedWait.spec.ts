import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { mount } from '@vue/test-utils'
import { IDBFactory } from 'fake-indexeddb'
import { logoutUser } from '@/api/auth'
import DashboardView from '@/views/DashboardView.vue'
import { createEscrowI18n } from '@/i18n'
import en from '@/i18n/en.json'
import fr from '@/i18n/fr.json'
import { REVOCATION_WAIT_SECONDS } from '@/stores/session'
import { useAuthStore } from '@/stores/auth'
import { closeSessionBroadcast } from '@/utils/sessionBroadcast'
import { stopIdleWatch } from '@/utils/idleTimeout'
import * as idb from '@/stores/offlineQueue.idb'
import { aUser } from '@/test-support/factories'
import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'

/**
 * L'ATTENTE DE DÉCONNEXION EST DITE (Story 2.7, AC4 — UX-DR26 : libellé ET délai annoncé).
 *
 * <p><b>Ce que ce fichier garde.</b> L'attente de la révocation est désormais bornée
 * (`REVOCATION_WAIT_MS`), mais une attente bornée reste une attente : entre le clic et la
 * navigation, `endSession` a déjà remis les stores à zéro et l'écran se vide. Sans état
 * visible, l'utilisateur regarde un tableau de bord qui perd son contenu et un bouton qui
 * ne répond plus, sans savoir si son geste a été pris en compte.
 *
 * <p><b>Périmètre strict.</b> On mesure ici ce que l'ATTENTE affiche, et rien d'autre. La
 * place définitive du bouton, sa tokenisation et sa cible tactile appartiennent à T8/T9 et
 * ne sont pas assérées ici — un test qui verrouillerait la position actuelle du bouton
 * rougirait à sa migration pour une raison qui n'a rien à voir avec ce qu'il prouve.
 *
 * <p><b>Minuteries RÉELLES</b>, et rien à faire avancer : le libellé s'observe PENDANT
 * l'attente, dont le test tient le fil par une révocation qui ne se règle que sur ordre.
 * Figer l'horloge aurait suspendu `vi.waitFor` sans rien démontrer.
 */

vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
}))
vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(async () => []),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

const ALICE = aUser({ id: 42, email: 'alice@corp.example', role: 'BUYER' })
const LOGOUT = '[data-testid="logout-button"]'

let pinia: Pinia
/** Le double de `caches.delete` — l'un des effets qu'une seconde déconnexion rejouerait. */
let cacheDelete: ReturnType<typeof vi.fn>

function makeRouter(): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: DashboardView },
      { path: '/auth', name: 'auth', component: { template: '<div />' } },
    ],
  })
}

async function mountDashboard(locale = 'en') {
  const router = makeRouter()
  await router.push('/')
  await router.isReady()
  const wrapper = mount(DashboardView, {
    global: { plugins: [pinia, router, createEscrowI18n(locale)] },
  })
  await wrapper.vm.$nextTick()
  return { wrapper, router }
}

/** Une session posée par la primitive de production, jamais par un `setItem` en direct. */
function signIn() {
  const auth = useAuthStore()
  auth.token = 'jeton-d-alice'
  auth.user = { ...ALICE }
  auth.persist()
  return auth
}

/**
 * Le réseau qui accepte et ne répond pas — jusqu'à ce que le test le décide.
 *
 * <p>C'est le seul moyen d'observer l'attente : avec une révocation résolue, l'état
 * « déconnexion en cours » ne dure pas un rendu et le test mesurerait sa propre chance.
 */
function revocationOnDemand() {
  let release: (() => void) | undefined
  vi.mocked(logoutUser).mockReturnValueOnce(
    new Promise<void>((resolve) => {
      release = () => resolve()
    }),
  )
  return () => release?.()
}

/** Le texte du catalogue, paramètre substitué — la seule référence qui ne diverge pas. */
function waiting(catalogue: { common: { loggingOut: string } }) {
  return catalogue.common.loggingOut.replace('{seconds}', String(REVOCATION_WAIT_SECONDS))
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  stopIdleWatch()
  closeSessionBroadcast()
  cacheDelete = vi.fn(async () => true)
  globalThis.caches = { delete: cacheDelete } as unknown as CacheStorage
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
})

afterEach(() => {
  closeSessionBroadcast()
  stopIdleWatch()
  delete (globalThis as { caches?: CacheStorage }).caches
  localStorage.clear()
  sessionStorage.clear()
  vi.restoreAllMocks()
})

describe('le bouton de déconnexion dit l’attente et annonce son délai (UX-DR26)', () => {
  it('affiche le motif ET le délai pendant l’attente, puis rend la main', async () => {
    signIn()
    const answer = revocationOnDemand()
    const { wrapper, router } = await mountDashboard()
    const button = wrapper.find(LOGOUT)

    // Appariée d'entrée : AVANT le clic, le bouton est celui de tous les jours. Sans ce
    // premier temps, un bouton figé sur « déconnexion en cours » passerait la suite.
    expect(button.text()).toBe(en.common.logout)
    expect(button.attributes('disabled')).toBeUndefined()

    await button.trigger('click')
    await vi.waitFor(() => expect(wrapper.find(LOGOUT).text()).toBe(waiting(en)))

    // Le libellé porte le motif ET le délai annoncé, et le bouton ne peut plus être
    // cliqué — sans quoi l'utilisateur relancerait une déconnexion déjà en cours.
    expect(wrapper.find(LOGOUT).attributes('disabled')).toBeDefined()
    expect(wrapper.find(LOGOUT).attributes('aria-busy')).toBe('true')

    // Puis le réseau répond, et l'écran d'authentification arrive.
    answer()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('auth'))
  })

  it('annonce le même délai en français — le nombre vient de la production, pas du catalogue', async () => {
    // Le délai est INTERPOLÉ dans les deux catalogues : un « 3 » gravé en EN et en FR
    // aurait survécu à l'ajustement du plafond et annoncé une attente que `endSession`
    // n'applique plus. Le test lit `REVOCATION_WAIT_SECONDS`, comme la vue.
    signIn()
    const answer = revocationOnDemand()
    const { wrapper, router } = await mountDashboard('fr')
    const button = wrapper.find(LOGOUT)

    expect(button.text()).toBe(fr.common.logout)

    await button.trigger('click')
    await vi.waitFor(() => expect(wrapper.find(LOGOUT).text()).toBe(waiting(fr)))
    // Le nombre annoncé est bien celui que la production applique, et non un littéral :
    // les deux catalogues portent le même, et c'est le seul endroit où il est écrit.
    expect(waiting(fr)).toContain(String(REVOCATION_WAIT_SECONDS))

    answer()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('auth'))
  })

  it('n’a lancé qu’UNE terminaison, et le bouton neutralisé est ce qui l’assure', async () => {
    // Ce test dit à la fois « une seule purge est partie » et « la seule chose qui empêche
    // la seconde est l'attribut `disabled` ». Retirer `:disabled` fait rougir le premier
    // test de ce fichier, qui l'assère nommément — la garde est donc prouvée là où elle
    // vit, une seule fois.
    //
    // <p>⚠️ Ce test a d'abord été écrit avec un second clic et un compte de `logoutUser`,
    // et la mutation a montré qu'il était CREUX de deux façons : `trigger()` refuse de
    // cliquer un élément désactivé — le clic n'atteignait donc jamais le gestionnaire — et
    // l'oracle lui-même était faux, puisqu'à la seconde entrée le jeton a déjà quitté la
    // mémoire et que `revokeOnServer(null)` rend une promesse résolue SANS appeler
    // `logoutUser`. Ce qu'une seconde terminaison rejoue vraiment, c'est la PURGE :
    // `caches.delete` en est le témoin direct, et c'est lui qu'on compte ici.
    signIn()
    const answer = revocationOnDemand()
    const { wrapper, router } = await mountDashboard()

    await wrapper.find(LOGOUT).trigger('click')
    await vi.waitFor(() => expect(cacheDelete).toHaveBeenCalledTimes(1))
    expect(wrapper.find(LOGOUT).attributes('disabled')).toBeDefined()

    answer()
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('auth'))

    // Une purge et une seule, du clic jusqu'à l'écran d'authentification.
    expect(cacheDelete).toHaveBeenCalledTimes(1)
    expect(logoutUser).toHaveBeenCalledTimes(1)
  })
})
