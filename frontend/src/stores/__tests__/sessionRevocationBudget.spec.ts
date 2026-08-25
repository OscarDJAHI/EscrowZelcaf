import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient, { TOKEN_STORAGE_KEY } from '@/api/client'
import { logoutUser } from '@/api/auth'
import { USER_STORAGE_KEY, useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import {
  LAST_USER_STORAGE_KEY,
  READ_CACHE_NAME,
  REVOCATION_WAIT_MS,
  endSession,
} from '@/stores/session'
import { readCredential } from '@/utils/credentialStorage'
import { closeSessionBroadcast } from '@/utils/sessionBroadcast'
import { stopIdleWatch } from '@/utils/idleTimeout'
import * as idb from '@/stores/offlineQueue.idb'
import { aTransaction, aUser } from '@/test-support/factories'
import type { QueueEntry } from '@/types/queue'
import type { User } from '@/types/domain'

/**
 * L'ATTENTE BORNÉE SUR LA RÉVOCATION SERVEUR (Story 2.7, AC4 — T5).
 *
 * <p><b>Le défaut fermé ici.</b> `logoutUser` est un `fetch` sans délai d'expiration, et
 * `DashboardView` attend `endSession` avant de naviguer. Sur un portail captif — qui
 * accepte la connexion et ne répond jamais — ou avec un DNS suspendu, l'utilisateur
 * restait sur un tableau de bord DÉJÀ VIDÉ (les stores sont remis à zéro bien avant cette
 * étape), sans issue et sans explication. Sa déconnexion avait bel et bien eu lieu ; il
 * n'en voyait rien.
 *
 * <p><b>Ce que le correctif ne fait PAS, et c'est le point.</b> Il ne pose ni `signal` ni
 * `AbortController` : un abandon de requête ANNULERAIT la révocation au lieu de cesser de
 * l'attendre, et détruirait la garantie de `keepalive` (décision de la revue 1.6, NEVER de
 * la spec 1.9) — que le navigateur mène la requête à terme après la navigation. La borne
 * porte sur l'ATTENTE, pas sur la requête. Ce que `logoutUser` reçoit et ce qu'elle envoie
 * est asservi par `api/__tests__/authLogout.spec.ts` ; ici on mesure l'attente.
 *
 * <p><b>Pourquoi un fichier séparé de `session.spec.ts`.</b> Celui-ci pilote des minuteries
 * factices pour faire s'écouler le plafond sans immobiliser la suite trois secondes ;
 * `session.spec.ts` s'appuie sur `vi.waitFor`, qui utilise les minuteries RÉELLES et se
 * BLOQUE sous `vi.useFakeTimers()`. Les mélanger n'aurait pas fait rougir la suite, il
 * l'aurait suspendue.
 *
 * <p><b>`toFake: ['setTimeout','clearTimeout','Date']` et non la panoplie complète</b> :
 * ces tests lisent IndexedDB — `endSession` purge les entrées du partant avant d'attendre
 * le réseau — et `fake-indexeddb` ordonnance ses transactions sur `setImmediate`. Figer
 * celui-ci suspend la base au lieu de faire rougir quoi que ce soit.
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
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal()
  // Seule l'instance axios est remplacée : `TOKEN_STORAGE_KEY` reste le vrai, donc les
  // clés assérées ici sont celles que la production écrit.
  return { ...(actual as object), default: { request: vi.fn() } }
})

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })

function entry(id: string, userId: number, seq: number): QueueEntry {
  return {
    id,
    timestamp: `2026-01-01T00:00:0${seq}.000Z`,
    seq,
    method: 'post',
    url: `/api/v1/escrow/${seq}/event`,
    data: { event: 'SHIP_GOODS' },
    meta: { type: 'SEND_EVENT', transactionId: seq, userId },
  }
}

async function seedSharedDevice() {
  await idb.put(entry('alice-pending', ALICE.id, 1))
  await idb.put(entry('bob-pending', 7, 3))
}

async function storedIds() {
  return (await idb.getAll()).map((item) => item.id).sort()
}

/** Un double de l'API Cache, absente de jsdom. */
function stubCaches() {
  const del = vi.fn(async () => true)
  globalThis.caches = { delete: del } as unknown as CacheStorage
  return del
}

/** Une session posée par les primitives de PRODUCTION, jamais par un `setItem` en direct. */
function signIn(user: User) {
  const auth = useAuthStore()
  auth.token = `${user.email}-token`
  auth.user = { ...user }
  auth.persist()
  return auth
}

/**
 * Le réseau qui accepte et ne répond jamais : le portail captif, à la lettre.
 *
 * <p>C'est le SEUL moyen de distinguer une attente bornée d'une attente sans fin — avec
 * une promesse résolue, les deux comportements sont rigoureusement identiques.
 */
function revocationThatNeverAnswers() {
  vi.mocked(logoutUser).mockReturnValueOnce(new Promise<void>(() => {}))
}

/** Une révocation dont le test tient le fil : elle ne se règle que sur ordre. */
function revocationOnDemand() {
  let release: (() => void) | undefined
  vi.mocked(logoutUser).mockReturnValueOnce(
    new Promise<void>((resolve) => {
      release = () => resolve()
    }),
  )
  return () => release?.()
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  stopIdleWatch()
  closeSessionBroadcast()
  setActivePinia(createPinia())
  vi.mocked(apiClient.request).mockReset()
  vi.mocked(apiClient.request).mockResolvedValue({ data: {} })
  vi.clearAllMocks()
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'Date'] })
})

afterEach(() => {
  vi.useRealTimers()
  closeSessionBroadcast()
  stopIdleWatch()
  delete (globalThis as { caches?: CacheStorage }).caches
  vi.restoreAllMocks()
})

describe('endSession — l’attente de la révocation est BORNÉE (AC4)', () => {
  it('rend la main quand le réseau ne répond JAMAIS', async () => {
    const del = stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))
    const escrow = useEscrowStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    revocationThatNeverAnswers()

    // Le drapeau plutôt qu'un `await` : c'est le fait « elle a rendu la main » qu'on
    // mesure, et l'attendre pour le mesurer suspendrait le test au lieu de le faire
    // rougir. Avec l'attente non bornée, il reste `false` et l'assertion échoue NET.
    let settled = false
    const pending = endSession({ reason: 'logout' }).then(() => {
      settled = true
    })

    // `advanceTimersByTimeAsync` et non `advanceTimersByTime` : la chaîne d'`await`
    // d'`endSession` — IndexedDB, cache de lecture — est asynchrone, et `vi.waitFor`, outil
    // habituel de ce dépôt, se bloque sous minuteries factices.
    await vi.advanceTimersByTimeAsync(REVOCATION_WAIT_MS)

    expect(settled).toBe(true)
    await expect(pending).resolves.toBeUndefined()

    // Appariée, et pas décorative : une fonction qui aurait renoncé AVANT de purger
    // satisferait aussi bien l'assertion ci-dessus. Tout le local a bel et bien eu lieu,
    // et il a eu lieu AVANT l'attente — c'est l'ordre que `session.spec.ts` verrouille.
    expect(auth.token).toBeNull()
    expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    expect(readCredential(USER_STORAGE_KEY)).toBeNull()
    expect(escrow.transactions).toEqual([])
    expect(useOfflineQueueStore().queue).toEqual([])
    expect(await storedIds()).toEqual(['bob-pending'])
    expect(del).toHaveBeenCalledWith(READ_CACHE_NAME)
    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBeNull()
  })

  it('attend pour de bon quand le réseau répond — la borne est un PLAFOND, pas un délai', async () => {
    // Le plafond ne doit pas devenir le comportement ordinaire. Une déconnexion sur un
    // réseau sain continue d'attendre le verdict du serveur : c'est ce que la revue 1.6
    // avait exigé, et ce que la borne n'annule pas. Sans ce test, `REVOCATION_WAIT_MS = 0`
    // — ou un abandon pur et simple de l'attente — passerait le test précédent à la
    // perfection en supprimant la garantie qu'il ne mesure pas.
    stubCaches()
    signIn(ALICE)
    const answer = revocationOnDemand()

    let settled = false
    const pending = endSession({ reason: 'logout' }).then(() => {
      settled = true
    })

    // Un cheveu EN DEÇÀ du plafond : on n'abandonne pas avant l'heure.
    await vi.advanceTimersByTimeAsync(REVOCATION_WAIT_MS - 1)
    expect(settled).toBe(false)
    expect(logoutUser).toHaveBeenCalledTimes(1)

    // Puis le serveur répond, et la fonction rend la main sans attendre le reste du
    // plafond : c'est bien la RÉPONSE qui l'a libérée, pas l'échéance.
    answer()
    await vi.advanceTimersByTimeAsync(0)
    expect(settled).toBe(true)
    await expect(pending).resolves.toBeUndefined()
  })

  it('éteint son plafond quand la course est gagnée par le réseau', async () => {
    // Une déconnexion ordinaire arme puis doit DÉSARMER sa minuterie : laissée vivante,
    // elle tiendrait le processus éveillé trois secondes par déconnexion.
    //
    // Comptée en DIFFÉRENTIEL et jamais en absolu, et l'instant de référence est pris
    // PENDANT l'attente : `fake-indexeddb` pose ses propres minuteries pour la purge qui
    // précède, et un compte absolu mesurerait leur ménage plutôt que le nôtre.
    stubCaches()
    signIn(ALICE)
    const answer = revocationOnDemand()

    const pending = endSession({ reason: 'logout' })
    await vi.advanceTimersByTimeAsync(0)
    // Ici la révocation est en vol : le plafond est armé, et son existence est la moitié
    // positive de ce test — sans elle, « aucune minuterie ne reste » serait la vérité
    // triviale d'un plafond qu'on n'aurait jamais posé.
    const whileWaiting = vi.getTimerCount()
    expect(logoutUser).toHaveBeenCalledTimes(1)

    answer()
    await expect(pending).resolves.toBeUndefined()

    // La course est gagnée par le réseau : la minuterie n'a pas échu, il faut donc
    // l'éteindre à la main. Une de moins, exactement.
    expect(vi.getTimerCount()).toBe(whileWaiting - 1)
  })

  it('ne transmet à la révocation QUE le jeton — aucun signal d’abandon', async () => {
    // La borne porte sur l'attente et jamais sur la requête : un `AbortSignal` glissé ici
    // annulerait la révocation au lieu de cesser de l'attendre, et le jeton resterait
    // accepté côté serveur pour les 24 h de son TTL. La signature est la garde.
    stubCaches()
    const auth = signIn(ALICE)
    const revokedToken = auth.token

    await endSession({ reason: 'logout' })

    expect(logoutUser).toHaveBeenCalledTimes(1)
    expect(vi.mocked(logoutUser).mock.calls[0]).toEqual([revokedToken])
  })

  it('n’attend rien du tout sur une expiration — il n’y a pas de révocation à borner', async () => {
    // Appariée avec tout ce qui précède : la borne n'a pas introduit d'attente là où il
    // n'y en avait pas. Une expiration sort avant la révocation, et le plafond avec elle.
    signIn(ALICE)

    let settled = false
    const pending = endSession({ reason: 'expired' }).then(() => {
      settled = true
    })

    // Sans faire avancer d'une seule milliseconde au-delà des microtâches : une expiration
    // qui passerait par le plafond ne rendrait la main qu'après trois secondes.
    await vi.advanceTimersByTimeAsync(0)

    expect(settled).toBe(true)
    expect(logoutUser).not.toHaveBeenCalled()
    await expect(pending).resolves.toBeUndefined()
  })
})
