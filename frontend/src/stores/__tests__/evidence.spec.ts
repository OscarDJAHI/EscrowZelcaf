import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import { listEvidence, withdrawEvidence as withdrawEvidenceApi } from '@/api/evidence'
import { useAuthStore } from '@/stores/auth'
import { useEvidenceStore } from '@/stores/evidence'
import { endSession } from '@/stores/session'
import * as idb from '@/stores/offlineQueue.idb'
import { aUser, anEvidenceItem } from '@/test-support/factories'
import type { EvidenceItem } from '@/types/domain'

/**
 * LES DEUX JETONS DE COURSE D'`evidence` (Story 2.7, T7, AC6).
 *
 * <p><b>Pourquoi ce fichier n'existait pas.</b> `loadSeq` garde ce store depuis
 * l'Epic 4 et n'avait AUCUN test : ni sa course intra-session, ni celle du retrait qu'il
 * sert à invalider. Une garde sans test est une garde qu'un remaniement supprime sans
 * bruit — et cette story, qui vient poser une seconde garde juste à côté, est le pire
 * moment pour découvrir la première par accident.
 *
 * <p><b>Ce que ce fichier prouve, et que rien d'autre ne prouve.</b> Que `loadSeq` est
 * REMBOBINÉ par `$reset()` — le fait est constaté ici, pas supposé — et que ce
 * rembobinage rendait une réponse de A exactement égale à la première lecture de B. Seule
 * l'époque de session sépare les deux ; `loadSeq` ne le peut pas, et c'est la raison pour
 * laquelle elle ne le remplace pas.
 *
 * <p><b>Aucune minuterie, factice ou réelle.</b> L'ordre des résolutions est piloté par
 * des promesses différées : « la réponse de A arrive après la connexion de B » est une
 * GARANTIE D'ORDRE ici, jamais un délai qu'une machine chargée pourrait démentir.
 */

vi.mock('@/api/evidence', () => ({
  listEvidence: vi.fn(),
  uploadEvidence: vi.fn(),
  withdrawEvidence: vi.fn(),
  downloadEvidence: vi.fn(),
}))
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
  return { ...(actual as object), default: { request: vi.fn() } }
})

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })
const BOB = aUser({ id: 7, email: 'bob@corp.example' })

const ALICE_PIECE = anEvidenceItem({ id: 101, originalFilename: 'connaissement-alice.pdf' })
const BOB_PIECE = anEvidenceItem({ id: 202, originalFilename: 'connaissement-bob.pdf' })

/** Une promesse dont le test décide de l'instant de résolution. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  // La promesse est déjà munie de ses gestionnaires par la production ; ce `catch` neutre
  // évite seulement un rejet non traité quand un test la rejette exprès.
  promise.catch(() => {})
  return { promise, resolve, reject }
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  vi.mocked(listEvidence).mockReset()
  vi.mocked(withdrawEvidenceApi).mockReset()
})

describe('evidence — loadSeq garde les courses INTRA-session', () => {
  it('laisse la lecture la PLUS RÉCENTE gagner, quel que soit l\'ordre des réponses', async () => {
    const evidence = useEvidenceStore()
    const first = deferred<EvidenceItem[]>()
    const second = deferred<EvidenceItem[]>()

    vi.mocked(listEvidence).mockReturnValueOnce(first.promise)
    const older = evidence.loadEvidence(7)
    vi.mocked(listEvidence).mockReturnValueOnce(second.promise)
    const newer = evidence.loadEvidence(7)

    // La plus récente répond d'abord, la plus ancienne ensuite : c'est l'inversion que
    // `loadSeq` existe pour absorber.
    second.resolve([BOB_PIECE])
    await newer
    first.resolve([ALICE_PIECE])
    await older

    // Positive et négative appariées, compte EXACT.
    expect(evidence.items).toHaveLength(1)
    expect(evidence.items[0].id).toBe(BOB_PIECE.id)
  })

  it('laisse un RETRAIT invalider une lecture en vol qui montrerait la pièce encore active', async () => {
    const evidence = useEvidenceStore()
    const active = anEvidenceItem({ id: 101, status: 'ACTIVE' })
    const withdrawn = { ...active, status: 'WITHDRAWN' as const }

    const inFlight = deferred<EvidenceItem[]>()
    vi.mocked(listEvidence).mockReturnValueOnce(inFlight.promise)
    const stale = evidence.loadEvidence(7)

    vi.mocked(withdrawEvidenceApi).mockResolvedValueOnce(withdrawn)
    await evidence.withdrawEvidence(7, 101)
    expect(evidence.items).toEqual([]) // rien n'était encore chargé — l'état de départ, dit

    // La lecture partie AVANT le retrait répond maintenant, et elle montre la pièce
    // encore ACTIVE. C'est précisément ce que `loadSeq` doit écarter.
    inFlight.resolve([active])
    await stale

    expect(evidence.items).toHaveLength(0)
    // Positive appariée : une lecture postérieure au retrait, elle, atterrit.
    vi.mocked(listEvidence).mockResolvedValueOnce([withdrawn])
    await evidence.loadEvidence(7)
    expect(evidence.items).toEqual([withdrawn])
  })
})

describe('evidence — l\'époque garde les courses INTER-sessions (AC6)', () => {
  it('constate que `$reset()` REMBOBINE loadSeq — le fait dont dépend tout le reste', async () => {
    const evidence = useEvidenceStore()
    vi.mocked(listEvidence).mockResolvedValueOnce([ALICE_PIECE])
    await evidence.loadEvidence(7)

    expect(evidence.loadSeq).toBe(1) // l'état de départ, distinct du zéro d'un store neuf

    evidence.$reset()

    expect(evidence.loadSeq).toBe(0)
  })

  it('rejette une réponse de A dont le seq COÏNCIDE avec la première lecture de B', async () => {
    const auth = useAuthStore()
    const evidence = useEvidenceStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<EvidenceItem[]>()
    vi.mocked(listEvidence).mockReturnValueOnce(alice.promise)
    const aliceLoad = evidence.loadEvidence(7)
    expect(evidence.loadSeq).toBe(1)

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    const bob = deferred<EvidenceItem[]>()
    vi.mocked(listEvidence).mockReturnValueOnce(bob.promise)
    const bobLoad = evidence.loadEvidence(7)
    // LE CŒUR DU DÉFAUT : les deux compteurs sont ÉGAUX. `seq !== this.loadSeq` est donc
    // faux pour la réponse d'Alice, et la garde de l'Epic 4 la laisserait passer.
    expect(evidence.loadSeq).toBe(1)

    alice.resolve([ALICE_PIECE])
    await aliceLoad

    // Négative, compte exact : la pièce d'Alice n'entre pas dans le store de Bob.
    expect(evidence.items).toHaveLength(0)
    expect(evidence.loadedId).toBeNull()

    // Positive appariée, MÊME montage : la lecture de Bob, elle, atterrit — sans quoi
    // « rien n'est arrivé » serait aussi satisfait par une garde qui rejette tout.
    bob.resolve([BOB_PIECE])
    await bobLoad
    expect(evidence.items).toHaveLength(1)
    expect(evidence.items[0].id).toBe(BOB_PIECE.id)
  })

  it('n\'affiche pas à B l\'erreur d\'une lecture émise par A', async () => {
    const auth = useAuthStore()
    const evidence = useEvidenceStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<EvidenceItem[]>()
    vi.mocked(listEvidence).mockReturnValueOnce(alice.promise)
    const aliceLoad = evidence.loadEvidence(7)

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    alice.reject(new Error('network is down'))
    await aliceLoad

    expect(evidence.error).toBeNull()

    // Positive appariée : l'échec de la lecture de BOB, lui, s'affiche bel et bien.
    vi.mocked(listEvidence).mockRejectedValueOnce(new Error('network is down'))
    await evidence.loadEvidence(7)
    expect(evidence.error).not.toBeNull()
  })

  it('ne laisse pas la réponse de A éteindre le drapeau de chargement de B', async () => {
    const auth = useAuthStore()
    const evidence = useEvidenceStore()

    await auth.applySession({ token: 'alice-token', user: ALICE })
    const alice = deferred<EvidenceItem[]>()
    vi.mocked(listEvidence).mockReturnValueOnce(alice.promise)
    const aliceLoad = evidence.loadEvidence(7)

    await endSession({ reason: 'logout' })
    await auth.applySession({ token: 'bob-token', user: BOB })

    const bob = deferred<EvidenceItem[]>()
    vi.mocked(listEvidence).mockReturnValueOnce(bob.promise)
    const bobLoad = evidence.loadEvidence(7)
    expect(evidence.loading).toBe(true)

    alice.resolve([ALICE_PIECE])
    await aliceLoad

    // Le `finally` d'Alice ne doit pas retirer le voile de chargement de Bob : l'écran
    // afficherait une liste vide et « terminé » pendant que sa propre lecture est en vol.
    expect(evidence.loading).toBe(true)

    bob.resolve([BOB_PIECE])
    await bobLoad
    expect(evidence.loading).toBe(false)
  })
})
