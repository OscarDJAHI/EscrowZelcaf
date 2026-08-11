import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import { fetchTransactionDetail, fetchTransactions } from '@/api/escrow'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { beginSession, currentSessionEpoch, endSession } from '@/stores/session'
import * as idb from '@/stores/offlineQueue.idb'
import { aTransaction, aUser } from '@/test-support/factories'
import type { Transaction, TransactionDetail } from '@/types/domain'

/**
 * L'ÉPOQUE DE SESSION (Story 2.7, T7, AC6).
 *
 * <p><b>Le défaut gardé ici.</b> `escrow.loadTransactions` écrivait
 * `this.transactions = await fetchTransactions()` <b>sans aucune garde de session</b> —
 * l'`issuedAt` ajouté depuis horodate la réponse mais ne compare aucune session. La liste
 * de A, émise juste avant sa déconnexion, s'installait donc dans le store de B et y
 * restait le temps que la lecture de B aboutisse.
 *
 * <p><b>Ce que ce fichier NE revendique PAS</b> (décision D-E). L'époque ne couvre pas le
 * chemin du service worker : une réponse `/api/` en vol au moment de la purge recrée
 * `escrow-api-cache` derrière elle, hors de tout store Pinia. Entrée E9 du ledger, routée
 * vers la Story 11-3. Aucune assertion ici ne dit « aucune donnée de A ne survit » — elles
 * disent toutes « ce store-ci ne l'accepte pas ».
 *
 * <p><b>Ordre garanti, jamais mesuré.</b> Les réponses sont des promesses différées que le
 * test résout lui-même : « la réponse de A arrive après la connexion de B » est une
 * propriété du montage, pas un pari sur un délai.
 */

vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))
vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
}))
vi.mock('@/api/client', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...(actual as object), default: { request: vi.fn() } }
})

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })
const BOB = aUser({ id: 7, email: 'bob@corp.example' })

// Typées `Transaction` et non `DisplayTransaction` : c'est ce que le SERVEUR rend, donc
// ce que la fonction mockée doit rendre. Une fixture typée sur la forme d'affichage
// admettrait un identifiant `local-…` que cette réponse-là ne porte jamais.
const ALICE_ROW: Transaction = aTransaction({ id: 101, buyerEmail: 'alice@corp.example' })
const BOB_ROW: Transaction = aTransaction({ id: 202, buyerEmail: 'bob@corp.example' })

function detailOf(row: Transaction): TransactionDetail {
  return { transaction: row, auditLogs: [] }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  promise.catch(() => {})
  return { promise, resolve, reject }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.mocked(fetchTransactions).mockReset()
  vi.mocked(fetchTransactionDetail).mockReset()
})

describe('l\'époque de session — le compteur lui-même', () => {
  it('tourne à la FIN d\'une session', async () => {
    const before = currentSessionEpoch()

    await endSession({ reason: 'logout' })

    expect(currentSessionEpoch()).not.toBe(before)
  })

  it('tourne aussi au DÉBUT d\'une session — les deux bouts, pas un seul', async () => {
    const before = currentSessionEpoch()

    await beginSession(42)

    expect(currentSessionEpoch()).not.toBe(before)
  })

  it('est STRICTEMENT croissante sur un cycle complet A → B, et ne revient jamais en arrière', async () => {
    const auth = useAuthStore()
    const seen: number[] = [currentSessionEpoch()]

    await auth.applySession({ token: 'alice-token', user: ALICE })
    seen.push(currentSessionEpoch())
    await endSession({ reason: 'logout' })
    seen.push(currentSessionEpoch())
    await auth.applySession({ token: 'bob-token', user: BOB })
    seen.push(currentSessionEpoch())

    // Positive : chaque valeur est strictement supérieure à la précédente. Négative
    // appariée : aucune valeur n'apparaît deux fois — c'est exactement ce que `loadSeq`
    // ne peut pas promettre, puisque `$reset()` le ramène à zéro.
    for (let i = 1; i < seen.length; i += 1) expect(seen[i]).toBeGreaterThan(seen[i - 1])
    expect(new Set(seen).size).toBe(seen.length)
  })
})

describe('l\'époque de session — les lectures d\'escrow (AC6)', () => {
  /**
   * LE TOUR DE FIN DE SESSION, prouvé par son EFFET et non par le compteur.
   *
   * <p>Ce test est né de la passe de mutation. Retirer le tour d'`endSession` laissait
   * verts tous les tests du scénario A → B : le tour de `beginSession` suffisait à les
   * satisfaire, si bien que la moitié du dispositif n'était gardée que par une assertion
   * sur la valeur du compteur. Or les deux tours ferment des fenêtres DIFFÉRENTES, et
   * celle-ci est propre à l'appareil partagé — entre le départ de A et l'arrivée de qui
   * que ce soit, la réponse en vol de A repeuplait le store DERRIÈRE la purge, sur un
   * poste qui venait d'être rendu. Personne ne le voyait à l'écran (la garde du routeur
   * renvoie vers `/auth`), et c'est bien le problème : la donnée était là sans que rien
   * ne la montre ni ne la reprenne.
   */
  it('ne laisse pas la réponse de A repeupler le store APRÈS sa déconnexion, avant toute autre session', async () => {
    const auth = useAuthStore()
    const escrow = useEscrowStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<Transaction[]>()
    vi.mocked(fetchTransactions).mockReturnValueOnce(alice.promise)
    const aliceLoad = escrow.loadTransactions()

    await endSession({ reason: 'logout' })
    expect(escrow.transactions).toHaveLength(0) // l'état de départ, dit

    alice.resolve([ALICE_ROW])
    await aliceLoad

    // Négative, compte exact : rien n'est revenu derrière la purge.
    expect(escrow.transactions).toHaveLength(0)
    expect(escrow.transactionsFetchedAt).toBeNull()

    // Positive appariée : une lecture émise APRÈS la déconnexion — donc dans la session
    // suivante — atterrit normalement.
    await auth.applySession({ token: 'bob-token', user: BOB })
    vi.mocked(fetchTransactions).mockResolvedValueOnce([BOB_ROW])
    await escrow.loadTransactions()
    expect(escrow.transactions).toHaveLength(1)
  })

  /**
   * LE TOUR DE DÉBUT DE SESSION, prouvé lui aussi par son EFFET — et sur le seul chemin
   * où il est irremplaçable : une session qui commence SANS qu'aucune ne se soit terminée.
   *
   * <p>Né de la même passe de mutation, par symétrie. Ce chemin n'est pas théorique : la
   * branche « session incohérente » du routeur (Story 2.3, AC5) renvoie vers `/auth` sans
   * appeler `endSession`, et la connexion qui suit entre donc directement par
   * `applySession`. `auth.verify` (Story 2.4) fait de même. C'est ce que dit déjà le
   * commentaire de `beginSession` : le `$reset()` d'entrée ferme la fenêtre « quoi qu'il
   * soit arrivé à la fin de la précédente » — et sans tour d'époque, il la refermait sur
   * une réponse encore en vol.
   */
  it('rejette la LISTE de A quand B se connecte SANS qu\'aucune session ne se soit terminée', async () => {
    const auth = useAuthStore()
    const escrow = useEscrowStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<Transaction[]>()
    vi.mocked(fetchTransactions).mockReturnValueOnce(alice.promise)
    const aliceLoad = escrow.loadTransactions()

    // Aucun `endSession` : on entre directement dans la session suivante.
    await auth.applySession({ token: 'bob-token', user: BOB })

    alice.resolve([ALICE_ROW])
    await aliceLoad

    expect(escrow.transactions).toHaveLength(0)
    expect(escrow.transactionsFetchedAt).toBeNull()

    // Positive appariée.
    vi.mocked(fetchTransactions).mockResolvedValueOnce([BOB_ROW])
    await escrow.loadTransactions()
    expect(escrow.transactions).toHaveLength(1)
    expect(escrow.transactions[0].id).toBe(BOB_ROW.id)
  })

  it('rejette la LISTE de A quand elle se résout après la connexion de B', async () => {
    const auth = useAuthStore()
    const escrow = useEscrowStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<Transaction[]>()
    vi.mocked(fetchTransactions).mockReturnValueOnce(alice.promise)
    const aliceLoad = escrow.loadTransactions()

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    alice.resolve([ALICE_ROW])
    await aliceLoad

    // Négative, compte EXACT : la liste de B ne contient rien, et pas seulement « pas la
    // ligne d'Alice ».
    expect(escrow.transactions).toHaveLength(0)
    expect(escrow.transactionsFetchedAt).toBeNull()

    // Positive appariée : la lecture de B atterrit bel et bien.
    vi.mocked(fetchTransactions).mockResolvedValueOnce([BOB_ROW])
    await escrow.loadTransactions()
    expect(escrow.transactions).toHaveLength(1)
    expect(escrow.transactions[0].id).toBe(BOB_ROW.id)
    expect(escrow.transactionsFetchedAt).not.toBeNull()
  })

  it('rejette le DÉTAIL de A quand il se résout après la connexion de B', async () => {
    const auth = useAuthStore()
    const escrow = useEscrowStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<TransactionDetail>()
    vi.mocked(fetchTransactionDetail).mockReturnValueOnce(alice.promise)
    const aliceLoad = escrow.loadTransactionDetail(101)

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    alice.resolve(detailOf(ALICE_ROW))
    await aliceLoad

    expect(escrow.currentDetail).toBeNull()
    expect(escrow.currentDetailFetchedAt).toBeNull()

    vi.mocked(fetchTransactionDetail).mockResolvedValueOnce(detailOf(BOB_ROW))
    await escrow.loadTransactionDetail(202)
    expect(escrow.currentDetail?.transaction.id).toBe(BOB_ROW.id)
  })

  it('n\'affiche pas à B le message d\'erreur d\'une lecture émise par A', async () => {
    const auth = useAuthStore()
    const escrow = useEscrowStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<Transaction[]>()
    vi.mocked(fetchTransactions).mockReturnValueOnce(alice.promise)
    const aliceLoad = escrow.loadTransactions()

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    alice.reject(new Error('network is down'))
    await aliceLoad

    expect(escrow.error).toBeNull()

    // Positive appariée : l'échec de la lecture de B, lui, s'affiche.
    vi.mocked(fetchTransactions).mockRejectedValueOnce(new Error('network is down'))
    await escrow.loadTransactions()
    expect(escrow.error).not.toBeNull()
  })

  it('ne laisse pas la réponse de A éteindre le drapeau de chargement de B', async () => {
    const auth = useAuthStore()
    const escrow = useEscrowStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<Transaction[]>()
    vi.mocked(fetchTransactions).mockReturnValueOnce(alice.promise)
    const aliceLoad = escrow.loadTransactions()

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    const bob = deferred<Transaction[]>()
    vi.mocked(fetchTransactions).mockReturnValueOnce(bob.promise)
    const bobLoad = escrow.loadTransactions()
    expect(escrow.loading).toBe(true)

    alice.resolve([ALICE_ROW])
    await aliceLoad

    // Le `finally` d'Alice ne doit pas annoncer à Bob que sa propre lecture est terminée.
    expect(escrow.loading).toBe(true)

    bob.resolve([BOB_ROW])
    await bobLoad
    expect(escrow.loading).toBe(false)
  })
})
