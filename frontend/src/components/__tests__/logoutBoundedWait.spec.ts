import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { mount } from '@vue/test-utils'
import { IDBFactory } from 'fake-indexeddb'
import { logoutUser } from '@/api/auth'
import LogoutButton from '@/components/LogoutButton.vue'
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
 * visible, l'utilisateur regarde une interface qui perd son contenu et un bouton qui ne
 * répond plus, sans savoir si son geste a été pris en compte.
 *
 * <p><b>Périmètre strict.</b> On mesure ici ce que l'ATTENTE affiche, et rien d'autre. La
 * place du bouton dans les shells, sa tokenisation et sa cible tactile sont assérées
 * ailleurs (T8/T9) — un test qui verrouillerait ici la position du bouton rougirait à sa
 * migration pour une raison qui n'a rien à voir avec ce qu'il prouve.
 *
 * <p><b>T8 a exercé cette prudence, et elle a payé.</b> Le sujet monté était
 * `DashboardView`, où le bouton vivait ; il est désormais `LogoutButton`, que les deux
 * shells placent (décision D-C). Aucune assertion n'a eu à changer — seul le composant
 * monté — parce qu'aucune ne parlait du tableau de bord. C'est exactement la propriété que
 * l'en-tête revendiquait avant que la migration ne la mette à l'épreuve.
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

/**
 * `authChunkFails` reproduit le défaut d'origine et non une commodité de test.
 *
 * <p>Toutes les routes de production sont des chunks paresseux. Le filet existe pour le cas
 * où celui d'`AuthView` ne se charge PAS — déploiement qui invalide le nom haché, précache
 * évincé hors ligne. Un composant asynchrone qui rejette est littéralement cet événement ;
 * un `spyOn(router, 'replace')` aurait prouvé la même ligne en simulant le mécanisme au
 * lieu de la panne, et serait resté vert si la production cessait un jour de passer par
 * `replace`.
 */
function makeRouter({ authChunkFails = false } = {}): Router {
  return createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'dashboard', component: { template: '<div />' } },
      {
        path: '/auth',
        name: 'auth',
        component: authChunkFails
          ? () => Promise.reject(new Error('chunk AuthView introuvable'))
          : { template: '<div />' },
      },
    ],
  })
}

async function mountLogoutButton(locale = 'en', routerOptions = {}) {
  const router = makeRouter(routerOptions)
  await router.push('/')
  await router.isReady()
  const wrapper = mount(LogoutButton, {
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
    const { wrapper, router } = await mountLogoutButton()
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
    const { wrapper, router } = await mountLogoutButton('fr')
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
    const { wrapper, router } = await mountLogoutButton()

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

/**
 * LE FILET DE NAVIGATION (Story 2.7, T8).
 *
 * <p>Ce chemin était, jusqu'ici, ENTIÈREMENT MORT pour la suite : la story le relève
 * nommément. Il vaut d'être couvert parce qu'il ne se déclenche que le jour où quelque
 * chose d'autre a déjà mal tourné — et c'est précisément là qu'un code jamais exécuté
 * révèle qu'il ne fonctionne pas.
 *
 * <p>L'enjeu est le pire état de l'application : la session vient d'être détruite, le jeton
 * n'existe plus, et si la navigation échoue sans filet, l'utilisateur reste sur un écran
 * vide, sans issue, pendant qu'un rejet non traité s'échappe d'un gestionnaire `async`.
 */
describe('le filet quand l’écran d’authentification ne se charge pas', () => {
  let assign: ReturnType<typeof vi.fn>
  let realLocationDescriptor: PropertyDescriptor | undefined

  /**
   * Pourquoi un PROXY et non un `vi.spyOn(window.location, 'assign')`.
   *
   * <p>`assign` est une propriété propre de `Location`, `configurable: false` et
   * `writable: false` : l'espionner lève « Cannot redefine property ». Ce qui EST
   * redéfinissable, c'est `window.location` lui-même (accesseur `configurable: true`).
   *
   * <p>Le proxy remplace donc `assign` seul et délègue TOUT le reste au vrai objet, ce qui
   * n'est pas un détail : `createWebHistory()` lit `location` à la construction du routeur,
   * qui a lieu DANS le test, donc après ce remplacement. Un objet factice construit à la
   * main aurait fait router les tests sur une URL inventée — le harnais mesurerait alors
   * autre chose que la production, sans le dire.
   *
   * <p><b>La cible du proxy est un objet VIDE, pas le vrai `Location`.</b> Un piège `get`
   * n'a pas le droit de rendre autre chose que la valeur réelle d'une propriété non
   * configurable et non inscriptible de sa cible — c'est un invariant du langage, et
   * proxier `Location` directement lève au premier appel d'`assign`. Une cible vierge n'a
   * aucune propriété à protéger : la délégation est alors explicite, et `assign` est la
   * seule chose qui n'est pas déléguée.
   */
  beforeEach(() => {
    assign = vi.fn()
    realLocationDescriptor = Object.getOwnPropertyDescriptor(window, 'location')
    const real = window.location
    const proxy = new Proxy({} as Location, {
      get(_target, prop) {
        if (prop === 'assign') return assign
        const value = Reflect.get(real, prop, real)
        return typeof value === 'function' ? value.bind(real) : value
      },
      set(_target, prop, value) {
        return Reflect.set(real, prop, value)
      },
      has(_target, prop) {
        return prop in real
      },
    })
    Object.defineProperty(window, 'location', { configurable: true, get: () => proxy })
    // Le diagnostic est ÉMIS par la production : on le tait pour ne pas polluer la sortie,
    // et on l'assère plus bas plutôt que de faire semblant qu'il n'existe pas.
    vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    // Restauré par son descripteur d'origine et non par une réaffectation : `location` est
    // un ACCESSEUR, et lui rendre la forme d'une donnée laisserait le reste de la suite
    // tourner sur une fenêtre subtilement différente de celle du navigateur.
    if (realLocationDescriptor) Object.defineProperty(window, 'location', realLocationDescriptor)
  })

  it('recharge sur /auth quand `router.replace` rejette', async () => {
    signIn()
    const { wrapper } = await mountLogoutButton('en', { authChunkFails: true })

    // Appariée : avant le clic, aucun rechargement n'a été demandé. Sans ce premier temps,
    // un filet déclenché à tort — au montage, par exemple — passerait l'assertion suivante.
    expect(assign).not.toHaveBeenCalled()

    await wrapper.find(LOGOUT).trigger('click')
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/auth'))

    // Une seule fois : le filet est une sortie, pas une boucle.
    expect(assign).toHaveBeenCalledTimes(1)
    expect(console.error).toHaveBeenCalled()
  })

  it('la session est détruite AVANT le filet — il ne rattrape pas une purge manquée', async () => {
    // L'ordre est ce qui compte. Si le filet partait avant `endSession`, un rechargement
    // ramènerait l'application sur un appareil encore porteur du jeton et du cache du
    // partant, et la garde de routeur le laisserait passer. Le filet répare la NAVIGATION,
    // jamais la purge.
    //
    // ⚠️ CE TEST A ÉTÉ ÉCRIT CREUX, ET LA MUTATION L'A DIT. Sa première version assérait
    // `auth.token === null` APRÈS le `vi.waitFor`. Or `waitFor` scrute par sondages, et
    // `endSession` s'était achevée entre-temps : les assertions décrivaient l'état FINAL,
    // que l'ordre soit respecté ou inversé. Déplacer `endSession` après la navigation ne
    // faisait rougir AUCUN des six tests. Ce qu'il faut observer n'est pas l'état après,
    // c'est l'état À L'INSTANT où le filet part — d'où la capture dans le double lui-même,
    // seul point du parcours qui soit contemporain de l'événement mesuré.
    const auth = signIn()
    let stateAtNetTime: { token: string | null; stored: string | null; purges: number } | null = null
    assign.mockImplementation(() => {
      stateAtNetTime = {
        token: auth.token,
        stored: localStorage.getItem('escrow_token') ?? sessionStorage.getItem('escrow_token'),
        purges: cacheDelete.mock.calls.length,
      }
    })

    const { wrapper } = await mountLogoutButton('en', { authChunkFails: true })

    await wrapper.find(LOGOUT).trigger('click')
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/auth'))

    // Appariée : le filet est bien parti, sinon les assertions suivantes porteraient sur
    // un `null` et passeraient par le vide — le motif exact relevé quatre fois sur cet epic.
    expect(stateAtNetTime).not.toBeNull()
    expect(stateAtNetTime!.token).toBeNull()
    expect(stateAtNetTime!.stored).toBeNull()
    expect(stateAtNetTime!.purges).toBeGreaterThan(0)
  })

  it('ne laisse pas le bouton figé sur « déconnexion en cours »', async () => {
    // Le `finally` est là pour ce chemin précis : le composant n'est PAS démonté, puisque
    // le rechargement complet met du temps à venir. Un bouton resté en attente mentirait
    // indéfiniment à qui le regarde.
    signIn()
    const { wrapper } = await mountLogoutButton('en', { authChunkFails: true })

    await wrapper.find(LOGOUT).trigger('click')
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/auth'))
    await wrapper.vm.$nextTick()

    expect(wrapper.find(LOGOUT).text()).toBe(en.common.logout)
    expect(wrapper.find(LOGOUT).attributes('disabled')).toBeUndefined()
  })
})
