import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient, { TOKEN_STORAGE_KEY } from '@/api/client'
import { logoutUser } from '@/api/auth'
import { USER_STORAGE_KEY, useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import {
  endSession,
  enforceIdlePolicy,
  installIdleTimeout,
  takeSessionNotice,
} from '@/stores/session'
import {
  IDLE_TIMEOUT_MS,
  LAST_ACTIVITY_STORAGE_KEY,
  markActivity,
  readLastActivity,
  stopIdleWatch,
} from '@/utils/idleTimeout'
import { readCredential, writeCredential } from '@/utils/credentialStorage'
import * as idb from '@/stores/offlineQueue.idb'
import { aTransaction, aUser } from '@/test-support/factories'
import type { QueueEntry } from '@/types/queue'
import type { User } from '@/types/domain'
import type { Router } from 'vue-router'

/**
 * L'expiration d'inactivité, côté session (Story 2.7, AC2 — décisions D-A et Q2).
 *
 * <p><b>Pourquoi un fichier séparé de `session.spec.ts`.</b> Celui-ci pilote des
 * minuteries factices ; `session.spec.ts` s'appuie sur `vi.waitFor`, qui utilise les
 * minuteries RÉELLES et se BLOQUE sous `vi.useFakeTimers()`. Les mélanger n'aurait pas
 * fait rougir la suite, il l'aurait suspendue.
 *
 * <p><b>Ce que cette suite garde et que rien d'autre ne garde :</b> l'inactivité prend la
 * sémantique `expired` et NON `logout`. Détruire la file hors-ligne parce que quelqu'un
 * est parti déjeuner, c'est perdre du travail non envoyé — AD-9 interdit la perte
 * silencieuse de fichier, UX-DR32 fait survivre la file à la ré-authentification. Chaque
 * assertion de purge est donc appariée à une assertion de SURVIE.
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
  // Seule l'instance axios est remplacée : `TOKEN_STORAGE_KEY` et
  // `resetSessionExpiryLatch` restent les vrais, donc les clés assérées ici sont
  // celles que la production écrit.
  return { ...(actual as object), default: { request: vi.fn() } }
})

const ALICE = aUser({ id: 42, email: 'alice@corp.example' })
const BOB = aUser({ id: 7, email: 'bob@corp.example' })

/** Instant sentinelle : deux `new Date()` réels tombent dans la même milliseconde. */
const T0 = new Date('2026-01-01T09:00:00.000Z')

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
  await idb.put(entry('bob-pending', BOB.id, 3))
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

/** La session existe depuis `msAgo` millisecondes sans la moindre activité. */
function inactiveFor(msAgo: number) {
  writeCredential(LAST_ACTIVITY_STORAGE_KEY, String(T0.getTime() - msAgo))
}

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  stopIdleWatch()
  setActivePinia(createPinia())
  vi.mocked(apiClient.request).mockReset()
  vi.mocked(apiClient.request).mockResolvedValue({ data: {} })
  vi.clearAllMocks()
})

afterEach(() => {
  stopIdleWatch()
  delete (globalThis as { caches?: CacheStorage }).caches
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('endSession({reason:\'idle\'}) — sémantique `expired`, pas `logout`', () => {
  it('vide les identifiants et la mémoire mais LAISSE la file hors-ligne et le cache', async () => {
    const del = stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)
    const escrow = useEscrowStore()
    const queue = useOfflineQueueStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    queue.queue = [entry('alice-pending', ALICE.id, 1)]

    await endSession({ reason: 'idle' })

    // Ce qui doit partir est parti : rien de lisible pour la personne suivante.
    expect(auth.token).toBeNull()
    expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    expect(readCredential(USER_STORAGE_KEY)).toBeNull()
    expect(escrow.transactions).toEqual([])
    expect(queue.queue).toEqual([])
    // Et ce qui doit RESTER est resté. C'est la décision D-A, confirmée par le PO :
    // partir déjeuner ne doit pas coûter à quelqu'un ses versements non envoyés.
    expect(await storedIds()).toEqual(['alice-pending', 'bob-pending'])
    expect(del).not.toHaveBeenCalled()
  })

  it('n’appelle pas la révocation serveur — le jeton n’est pas révoqué, il est abandonné', async () => {
    signIn(ALICE)

    await endSession({ reason: 'idle' })

    expect(logoutUser).not.toHaveBeenCalled()
  })

  it('efface l’horodatage d’inactivité, pour que la session suivante parte à neuf', async () => {
    signIn(ALICE)
    markActivity()
    expect(readLastActivity()).not.toBeNull()

    await endSession({ reason: 'idle' })

    // Laissé derrière, il serait lu par le contrôle au démarrage de la session SUIVANTE
    // comme la fraîcheur de celle-ci.
    expect(readLastActivity()).toBeNull()
  })

  it('« idle » est une raison RECONNUE — aucune plainte, là où « signout » en produit une', async () => {
    // La revue de la Story 1.9 avait relevé que `explicit = reason === 'logout'` faisait
    // tomber tout mode NOUVEAU sur le chemin conservateur en silence. Ajouter `'idle'`
    // rouvrait le défaut par le bord opposé : sans son inscription dans les raisons
    // connues, chaque expiration d'inactivité NORMALE aurait produit un diagnostic
    // « raison inconnue » — un mensonge à chaque déclenchement.
    const complain = vi.spyOn(console, 'error').mockImplementation(() => {})
    signIn(ALICE)

    await endSession({ reason: 'idle' })

    expect(complain).not.toHaveBeenCalledWith(
      expect.stringContaining('unknown end-of-session reason'),
    )

    // Appariée dans le même test : la garde du mode inconnu, elle, est toujours là.
    signIn(ALICE)
    await endSession({ reason: 'signout' })
    expect(complain).toHaveBeenCalledWith(expect.stringContaining('unknown end-of-session reason'))
  })
})

describe('le motif affiché — écrit à la fin, consommé une seule fois', () => {
  it('une expiration d’inactivité laisse un motif ; une déconnexion volontaire, non', async () => {
    stubCaches()
    signIn(ALICE)

    await endSession({ reason: 'idle' })
    expect(takeSessionNotice()).toBe('idle')

    // Lecture DESTRUCTRICE : un motif qui survivrait à son affichage réapparaîtrait à
    // chaque retour sur l'écran d'authentification, pour expliquer une expiration qui
    // n'a pas eu lieu.
    expect(takeSessionNotice()).toBeNull()

    // Appariée : la déconnexion volontaire n'a rien à expliquer, l'utilisateur l'a voulue.
    signIn(ALICE)
    await endSession({ reason: 'logout' })
    expect(takeSessionNotice()).toBeNull()
  })
})

describe('enforceIdlePolicy — le contrôle AU DÉMARRAGE, celui qui attrape l’onglet rouvert', () => {
  beforeEach(() => {
    // `toFake: ['Date']` et NON la panoplie complète : ces tests lisent IndexedDB, et
    // fake-indexeddb ordonnance ses transactions sur `setImmediate`/`setTimeout`. Figer
    // ces deux-là suspend la base au lieu de faire rougir quoi que ce soit — constaté ici
    // même, en 30 s de délai d'attente et zéro diagnostic utile. Ce contrôle-ci ne lit que
    // l'horloge ; il n'a besoin que d'elle.
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(T0)
  })

  it('termine la session laissée inactive au-delà du délai', async () => {
    stubCaches()
    await seedSharedDevice()
    const auth = signIn(ALICE)
    inactiveFor(IDLE_TIMEOUT_MS + 1000)

    await expect(enforceIdlePolicy()).resolves.toBe(true)

    expect(auth.token).toBeNull()
    expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    // Sémantique `expired` jusqu'au bout : le travail non envoyé attend son propriétaire.
    expect(await storedIds()).toEqual(['alice-pending', 'bob-pending'])
  })

  it('laisse intacte une session dont le délai n’est pas écoulé', async () => {
    const auth = signIn(ALICE)
    inactiveFor(IDLE_TIMEOUT_MS - 60_000)

    await expect(enforceIdlePolicy()).resolves.toBe(false)

    expect(auth.token).toBe('alice@corp.example-token')
    expect(readCredential(TOKEN_STORAGE_KEY)).toBe('alice@corp.example-token')
    expect(takeSessionNotice()).toBeNull()
  })

  it('ne termine RIEN quand personne n’est connecté, même sur un horodatage périmé', async () => {
    // Le module d'inactivité ne connaît aucune clé métier : il horodate aussi les appels
    // anonymes. Un horodatage traînant ne doit pas se transformer en fin de session — ni
    // en motif affiché — pour un visiteur qui n'a pas de session.
    inactiveFor(IDLE_TIMEOUT_MS * 10)

    await expect(enforceIdlePolicy()).resolves.toBe(false)

    expect(takeSessionNotice()).toBeNull()
  })

  it('horodate — au lieu de déconnecter — une session sans horodatage', async () => {
    // Une session ouverte avant que cette story n'existe. L'absence n'établit pas
    // l'inactivité : la lire ainsi déconnecterait tout le monde au déploiement. Elle
    // devient mesurable à partir de maintenant, au prix d'une seule fenêtre.
    const auth = signIn(ALICE)
    expect(readLastActivity()).toBeNull()

    await expect(enforceIdlePolicy()).resolves.toBe(false)

    expect(auth.token).toBe('alice@corp.example-token')
    expect(readLastActivity()).toBe(T0.getTime())
  })

  it('a DÉJÀ vidé la session quand elle rend la main, sans qu’on attende sa promesse', async () => {
    // La propriété sur laquelle `main.ts` s'appuie pour ne pas attendre avant
    // `app.mount()` : `app.mount()` n'attend rien, et une session à demi démontée au
    // premier rendu serait le défaut même que ce contrôle existe pour fermer — le shell
    // est précaché (UX-DR46), donc l'interface authentifiée s'affiche AVANT tout appel API.
    stubCaches()
    const auth = signIn(ALICE)
    inactiveFor(IDLE_TIMEOUT_MS + 1000)

    const pending = enforceIdlePolicy()

    // Pas de `await` au-dessus : c'est tout l'objet de ce test.
    expect(auth.token).toBeNull()
    expect(auth.user).toBeNull()
    await expect(pending).resolves.toBe(true)
  })
})

describe('installIdleTimeout — la minuterie de l’onglet resté ouvert', () => {
  /** Un routeur réduit à ce que le gestionnaire lit vraiment. */
  function fakeRouter(fullPath: string, name = 'escrow-detail') {
    return {
      currentRoute: { value: { name, fullPath } },
      replace: vi.fn(async () => undefined),
    } as unknown as Router
  }

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(T0)
  })

  it('termine la session et ramène à l’authentification, cible conservée', async () => {
    // Pas d'IndexedDB dans ce bloc : la panoplie complète de minuteries factices — dont ce
    // test a besoin pour faire avancer l'échéance — fige aussi l'ordonnanceur de
    // fake-indexeddb. Ce qu'une expiration LAISSE sur l'appareil est prouvé plus haut, sur
    // des minuteries réelles ; ici on prouve le DÉCLENCHEUR.
    stubCaches()
    const auth = signIn(ALICE)
    const escrow = useEscrowStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    markActivity()
    const router = fakeRouter('/escrow/42')
    installIdleTimeout(router)

    // `advanceTimersByTimeAsync` et non `advanceTimersByTime` : le gestionnaire est
    // asynchrone, et `vi.waitFor` — l'outil habituel de ce dépôt — se BLOQUE sous des
    // minuteries factices.
    await vi.advanceTimersByTimeAsync(IDLE_TIMEOUT_MS)

    expect(auth.token).toBeNull()
    expect(escrow.transactions).toEqual([])
    expect(router.replace).toHaveBeenCalledWith({ name: 'auth', query: { redirect: '/escrow/42' } })
    expect(takeSessionNotice()).toBe('idle')
  })

  it('ne fait RIEN quand personne n’est connecté, et continue pourtant de veiller', async () => {
    const router = fakeRouter('/auth', 'auth')
    installIdleTimeout(router)

    await vi.advanceTimersByTimeAsync(IDLE_TIMEOUT_MS)

    // Sur l'écran d'authentification, une échéance atteinte ne doit produire ni purge ni
    // navigation — un `replace` vers `auth` depuis `auth` serait une boucle.
    expect(router.replace).not.toHaveBeenCalled()

    // Mais la veille continue : quelqu'un se connecte ensuite, et son inactivité compte.
    // Sans ce second temps, une veille désarmée au premier faux départ passerait le test.
    const auth = signIn(BOB)
    markActivity()
    await vi.advanceTimersByTimeAsync(IDLE_TIMEOUT_MS)
    expect(auth.token).toBeNull()
    expect(router.replace).toHaveBeenCalledTimes(1)
  })
})
