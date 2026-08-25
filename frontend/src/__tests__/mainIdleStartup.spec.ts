import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import { USER_STORAGE_KEY } from '@/stores/auth'
import { readCredential, writeCredential, writePersistence } from '@/utils/credentialStorage'
import { IDLE_TIMEOUT_MS, LAST_ACTIVITY_STORAGE_KEY } from '@/utils/idleTimeout'
import { aUser } from '@/test-support/factories'

/**
 * LE CÂBLAGE de la politique d'inactivité dans `main.ts` (Story 2.7, AC2).
 *
 * <p><b>Pourquoi ce fichier existe.</b> « Un composant testé isolément n'est pas un
 * composant branché » — `ClientShell` avait sept tests verts et n'était monté par aucune
 * route. Le même angle mort menace ici : `enforceIdlePolicy` est prouvée comme FONCTION
 * par `stores/__tests__/sessionIdle.spec.ts`, et cette preuve reste intégralement verte si
 * personne ne l'appelle. Or le contrôle au démarrage est précisément ce qui attrape
 * l'onglet ROUVERT, et le shell applicatif est précaché par le service worker (UX-DR46) :
 * l'interface authentifiée s'affiche AVANT tout appel API. Non branché, il ne protège
 * rien, et rien ne le dit.
 *
 * <p>On amorce donc l'application RÉELLE — `main.ts`, son pinia, son routeur — et on
 * regarde ce qu'il reste de la session.
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
const TOKEN = 'jeton-d-alice'

/**
 * Le poste tel qu'il est au moment où quelqu'un rouvre l'application : « rester connecté »
 * a été coché — le SEUL cas où un jeton survit à la fermeture de l'onglet — et la dernière
 * activité remonte à `msAgo`.
 */
function reopenedAfter(msAgo: number) {
  writePersistence('local')
  writeCredential(TOKEN_STORAGE_KEY, TOKEN)
  writeCredential(USER_STORAGE_KEY, JSON.stringify(ALICE))
  writeCredential(LAST_ACTIVITY_STORAGE_KEY, String(Date.now() - msAgo))
}

/** Amorce l'application entière, comme le navigateur le ferait. */
async function boot() {
  document.body.innerHTML = '<div id="app"></div>'
  // Sans cela, le second amorçage réutiliserait le graphe du premier — mêmes stores, même
  // routeur — et ne prouverait plus rien d'un DÉMARRAGE.
  vi.resetModules()
  await import('@/main')
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
})

afterEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('main.ts — la politique d’inactivité s’applique AU DÉMARRAGE', () => {
  it('un onglet rouvert APRÈS le délai ne restaure aucune session', async () => {
    reopenedAfter(IDLE_TIMEOUT_MS + 60_000)

    await boot()

    // Le jeton ET le profil sont partis du substrat : la personne suivante qui ouvre
    // l'application sur ce poste ne trouve rien à restaurer.
    expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    expect(readCredential(USER_STORAGE_KEY)).toBeNull()
  })

  it('un onglet rouvert AVANT le délai garde sa session', async () => {
    // La contre-épreuve, et elle n'est pas décorative : sans elle, un démarrage qui
    // effacerait TOUJOURS la session — ou une application qui refuserait de démarrer —
    // satisferait le test ci-dessus à la perfection.
    reopenedAfter(IDLE_TIMEOUT_MS - 60_000)

    await boot()

    expect(readCredential(TOKEN_STORAGE_KEY)).toBe(TOKEN)
    expect(readCredential(USER_STORAGE_KEY)).toContain(ALICE.email)
  })

  it('la minuterie est branchée elle aussi : l’onglet RESTÉ ouvert finit par expirer', async () => {
    // L'autre moitié du dispositif, et elle a besoin de son propre test de câblage : le
    // contrôle au démarrage ne s'exécute qu'une fois, au boot. Sans minuterie branchée, un
    // onglet laissé ouvert toute la nuit garderait sa session — et les deux tests
    // ci-dessus resteraient verts.
    //
    // `toFake` restreint à l'horloge et aux minuteries de `setTimeout` : la panoplie
    // complète fige aussi l'ordonnanceur de fake-indexeddb, dont `offlineQueue.init()` se
    // sert au démarrage, et l'amorçage se suspendrait au lieu de prouver quoi que ce soit.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
    try {
      reopenedAfter(0)

      await boot()
      await vi.advanceTimersByTimeAsync(IDLE_TIMEOUT_MS)

      expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    } finally {
      vi.useRealTimers()
    }
  })
})
