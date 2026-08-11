import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import { USER_STORAGE_KEY } from '@/stores/auth'
import { takeSessionNotice } from '@/stores/session'
import { readCredential, writeCredential, writePersistence } from '@/utils/credentialStorage'
import { LAST_ACTIVITY_STORAGE_KEY } from '@/utils/idleTimeout'
import { SESSION_CHANNEL_NAME } from '@/utils/sessionBroadcast'
import { aUser } from '@/test-support/factories'

/**
 * LE CÂBLAGE de la propagation inter-onglets dans `main.ts` (Story 2.7, AC3).
 *
 * <p><b>Pourquoi ce fichier existe.</b> « Un composant testé isolément n'est pas un
 * composant branché » — `ClientShell` avait sept tests verts et n'était monté par aucune
 * route. `installSessionBroadcastListener` est prouvée comme FONCTION par
 * `stores/__tests__/sessionBroadcast.spec.ts`, et cette preuve reste INTÉGRALEMENT verte
 * si personne ne l'appelle : un onglet qui n'écoute pas continue d'afficher une interface
 * authentifiée après la déconnexion faite à côté, et rien ne le dit.
 *
 * <p>On amorce donc l'application RÉELLE — `main.ts`, son pinia, son routeur — puis on
 * parle depuis un autre canal, comme le ferait un autre onglet du même appareil.
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

/** L'onglet voisin : son propre canal, sur le nom que la production écoute. */
let other: BroadcastChannel
/** La fermeture du canal ouvert par l'amorçage — un par registre de modules. */
const closers: Array<() => void> = []

/** Une session vivante et FRAÎCHE : sans horodatage récent, le contrôle d'inactivité la tuerait au démarrage. */
function signedInDevice() {
  writePersistence('local')
  writeCredential(TOKEN_STORAGE_KEY, TOKEN)
  writeCredential(USER_STORAGE_KEY, JSON.stringify(ALICE))
  writeCredential(LAST_ACTIVITY_STORAGE_KEY, String(Date.now()))
}

/** Amorce l'application entière, comme le navigateur le ferait. */
async function boot() {
  document.body.innerHTML = '<div id="app"></div>'
  // Sans cela, le second amorçage réutiliserait le graphe du premier — mêmes stores, même
  // routeur, même canal — et ne prouverait plus rien d'un DÉMARRAGE.
  vi.resetModules()
  await import('@/main')
  // Le canal ouvert par CET amorçage, capturé depuis le registre qui vient de naître :
  // c'est le seul moyen de le refermer ensuite. Un canal laissé ouvert continuerait
  // d'entendre les messages du test suivant.
  const bus = await import('@/utils/sessionBroadcast')
  closers.push(bus.closeSessionBroadcast)
  return (await import('@/router')).default
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  other = new BroadcastChannel(SESSION_CHANNEL_NAME)
})

afterEach(() => {
  other.close()
  for (const close of closers.splice(0)) close()
  localStorage.clear()
  sessionStorage.clear()
  document.body.innerHTML = ''
  vi.restoreAllMocks()
})

describe('main.ts — la propagation inter-onglets est BRANCHÉE', () => {
  it('une fin de session annoncée par un autre onglet termine celui-ci', async () => {
    signedInDevice()

    const router = await boot()

    // Avant/après exact : la session est bien VIVANTE au sortir de l'amorçage. Sans cette
    // première assertion, un démarrage qui effacerait toujours la session — ou une
    // application qui refuserait de démarrer — satisferait la suite du test.
    expect(readCredential(TOKEN_STORAGE_KEY)).toBe(TOKEN)

    other.postMessage({ type: 'session-end', reason: 'logout' })

    await vi.waitFor(() => expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull())
    expect(readCredential(USER_STORAGE_KEY)).toBeNull()
    // Terminaison OBSERVABLE, par une navigation et non par un rechargement silencieux.
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('auth'))
  })

  it('un message étranger sur le même canal ne termine rien', async () => {
    // La contre-épreuve du câblage : l'écouteur branché doit filtrer, pas déconnecter à
    // la moindre parole. Le canal porte un nom générique et une story ultérieure pourrait
    // y faire transiter autre chose.
    //
    // ⚠️ Écrit d'abord « on poste, on attend 20 ms, le jeton est toujours là ». La passe
    // de mutation l'a démasqué : avec le discriminant retiré, ce test rougissait lancé
    // seul et restait VERT dans la suite complète — sous charge, l'attente s'écoulait
    // avant la livraison du message. On s'appuie donc sur l'ORDRE de livraison, que la
    // spécification garantit, plutôt que sur l'horloge : le message douteux porte
    // `reason: 'idle'`, on attend l'effet d'un second message bien formé — donc le
    // premier est nécessairement traité — et on regarde le motif, seul témoin qui
    // distingue « c'est le premier qui a agi » de « c'est le second ».
    signedInDevice()

    await boot()

    other.postMessage({ type: 'locale-changed', reason: 'idle' })
    other.postMessage({ type: 'session-end', reason: 'logout' })

    await vi.waitFor(() => expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull())
    expect(takeSessionNotice()).toBeNull()
  })
})
