import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import { USER_STORAGE_KEY, useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import * as idb from '@/stores/offlineQueue.idb'
import { writeCredential } from '@/utils/credentialStorage'
import { aUser } from '@/test-support/factories'
import type { CreateTransactionPayload } from '@/api/escrow'

/**
 * LES TROIS ÉTATS DU JETON (Story 2.7, T6, AC5).
 *
 * <p><b>Le défaut gardé ici.</b> `loadStoredUser()` rend `null` sur tout échec de
 * `JSON.parse` alors que le JETON survit. `isAuthenticated` valant `Boolean(token)`, la
 * garde du routeur laisse passer et l'API fonctionne ; mais la file hors-ligne lisait
 * `user?.id == null` comme « personne n'est connecté ». Deux conséquences, toutes deux
 * asservies ci-dessous : elle prétendait avoir LU une file qu'elle n'avait pas ouverte,
 * et elle estampillait `userId: undefined` sur chaque nouvelle entrée — des orphelines
 * que la première déconnexion venue supprime (`offlineQueue.idb.ts:140`).
 *
 * <p><b>Chaque assertion négative est appariée à une positive dans le même test</b>, avec
 * des comptes EXACTS : « rien n'a été mis en file » ne prouve rien tant qu'on n'a pas
 * montré, sur le même montage, que la mise en file fonctionne quand la session est saine.
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
  // Seule l'instance axios est remplacée : `TOKEN_STORAGE_KEY` reste le vrai, donc la
  // clé écrite ci-dessous est celle que la production relit.
  return { ...(actual as object), default: { request: vi.fn() } }
})

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })

const payload: CreateTransactionPayload = {
  sellerEmail: 'seller@example.com',
  amount: 1500,
  currency: 'XOF',
  description: 'Cargaison de cacao',
}

/**
 * Pose l'état incohérent PAR LE SUBSTRAT et jamais en écrivant `auth.user = null` à la
 * main : c'est `loadStoredUser()`, au moment où le store naît, qui fabrique le défaut —
 * une fixture posée après coup testerait une forme que la production n'emprunte pas.
 */
function seedIncoherentSession(rawProfile: string) {
  writeCredential(TOKEN_STORAGE_KEY, 'alice-token')
  writeCredential(USER_STORAGE_KEY, rawProfile)
}

function seedHealthySession() {
  writeCredential(TOKEN_STORAGE_KEY, 'alice-token')
  writeCredential(USER_STORAGE_KEY, JSON.stringify(ALICE))
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
})

describe('les trois états du jeton — stores/auth', () => {
  it('distingue le jeton ABSENT du jeton dont le profil est ILLISIBLE', () => {
    seedIncoherentSession('{ ceci n\'est pas du JSON')
    const auth = useAuthStore()

    // Le défaut d'origine, à la ligne près : le profil est perdu…
    expect(auth.user).toBeNull()
    // …mais le jeton, lui, est bien là, et l'application est authentifiée pour l'API.
    expect(auth.isAuthenticated).toBe(true)
    // Ce qui distingue les deux états — et qui n'existait pas.
    expect(auth.sessionState).toBe('incoherent')
    expect(auth.sessionState).not.toBe('anonymous')
  })

  it('rend « anonymous » sans jeton, « active » sur une session saine — trois valeurs, pas deux', () => {
    expect(useAuthStore().sessionState).toBe('anonymous')

    setActivePinia(createPinia())
    seedHealthySession()
    expect(useAuthStore().sessionState).toBe('active')

    setActivePinia(createPinia())
    seedIncoherentSession('null')
    expect(useAuthStore().sessionState).toBe('incoherent')
  })

  it('range un profil PARSABLE mais sans identifiant du côté incohérent', () => {
    // `JSON.parse` réussit, `typeof parsed === 'object'` aussi : `loadStoredUser` rend
    // donc un objet. C'est en aval que le dégât est identique — `meta.userId` serait
    // `undefined` et `getAllForUser` ne rendrait jamais rien.
    seedIncoherentSession(JSON.stringify({ email: 'alice@corp.example', role: 'BUYER' }))
    const auth = useAuthStore()

    expect(auth.user).not.toBeNull()
    expect(auth.sessionState).toBe('incoherent')
  })

  it('ne se laisse pas indexer par une clé hostile venue du stockage', () => {
    // Le défaut de `spaceForRole('constructor')`, transposé : un objet littéral hérite
    // d'`Object.prototype`. Ici il n'y a aucune table à indexer, et le test le fige.
    seedIncoherentSession(JSON.stringify({ id: null, role: 'constructor' }))
    expect(useAuthStore().sessionState).toBe('incoherent')

    setActivePinia(createPinia())
    seedIncoherentSession(JSON.stringify({ role: 'toString' }))
    expect(useAuthStore().sessionState).toBe('incoherent')
  })
})

describe('les trois états du jeton — file hors-ligne', () => {
  it('ne prétend PAS avoir lu la file quand la session est incohérente', async () => {
    seedIncoherentSession('{ ceci n\'est pas du JSON')
    const queue = useOfflineQueueStore()
    // Le bruit est attendu : la file DIT que la session est incohérente.
    vi.spyOn(console, 'error').mockImplementation(() => {})

    await queue.init()

    // Négative : le drapeau que `RecoveryView` lit pour distinguer « pas encore lu » de
    // « il n'y a rien » ne doit pas mentir.
    expect(queue.hydrated).toBe(false)
    expect(queue.queue).toHaveLength(0)

    // Positive appariée, MÊME montage : sans jeton, la file a bel et bien terminé sa
    // lecture — il n'y avait personne pour qui lire. C'est ce cas-là, et lui seul, qui
    // vaut `hydrated = true`.
    setActivePinia(createPinia())
    sessionStorage.clear()
    const anonymous = useOfflineQueueStore()
    await anonymous.init()
    expect(anonymous.hydrated).toBe(true)
  })

  it('refuse de mettre en file, plutôt que de fabriquer une entrée orpheline', async () => {
    seedIncoherentSession('{ ceci n\'est pas du JSON')
    const queue = useOfflineQueueStore()

    await expect(
      queue.enqueue({ method: 'post', url: '/api/v1/escrow', data: payload, meta: { type: 'CREATE_TRANSACTION' } }),
    ).rejects.toThrow(/incoherent session/)

    // Négative, compte EXACT : rien n'a été persisté, donc aucune orpheline à supprimer
    // à la prochaine déconnexion.
    expect(await idb.getAll()).toHaveLength(0)
    expect(queue.queue).toHaveLength(0)

    // Positive appariée : sur une session saine, la même mise en file aboutit ET porte
    // son propriétaire. Sans elle, « rien n'a été mis en file » serait satisfait par une
    // file qui ne marche plus du tout.
    setActivePinia(createPinia())
    sessionStorage.clear()
    seedHealthySession()
    const healthy = useOfflineQueueStore()
    await healthy.enqueue({
      method: 'post',
      url: '/api/v1/escrow',
      data: payload,
      meta: { type: 'CREATE_TRANSACTION', userId: useAuthStore().user?.id },
    })
    const stored = await idb.getAll()
    expect(stored).toHaveLength(1)
    expect(stored[0].meta?.userId).toBe(42)
  })

  it('ne laisse aucune entrée orpheline naître d\'une création de transaction hors ligne', async () => {
    // Le chemin RÉEL du défaut : `stores/escrow.ts` estampille
    // `userId: useAuthStore().user?.id`, qui vaut `undefined` ici.
    seedIncoherentSession('{ ceci n\'est pas du JSON')
    const escrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false

    await expect(escrow.createNewTransaction(payload)).rejects.toThrow(/incoherent session/)

    // Négative, comptes exacts : ni entrée orpheline, ni carte optimiste promettant une
    // transaction que rien ne synchronisera jamais.
    expect(await idb.getAll()).toHaveLength(0)
    expect(escrow.transactions).toHaveLength(0)

    // Positive appariée : la même création, sur une session saine, met bien en file une
    // entrée qui porte son propriétaire.
    setActivePinia(createPinia())
    sessionStorage.clear()
    seedHealthySession()
    const healthyEscrow = useEscrowStore()
    useOfflineQueueStore().isOnline = false
    await healthyEscrow.createNewTransaction(payload)
    const stored = await idb.getAll()
    expect(stored).toHaveLength(1)
    expect(stored[0].meta?.userId).toBe(42)
    expect(healthyEscrow.transactions).toHaveLength(1)
  })
})
