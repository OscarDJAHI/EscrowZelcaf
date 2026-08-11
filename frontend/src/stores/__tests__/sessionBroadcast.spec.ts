import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { IDBFactory } from 'fake-indexeddb'
import apiClient, { TOKEN_STORAGE_KEY } from '@/api/client'
import { logoutUser } from '@/api/auth'
import { USER_STORAGE_KEY, useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useEvidenceStore } from '@/stores/evidence'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import {
  LAST_USER_STORAGE_KEY,
  READ_CACHE_NAME,
  endSession,
  installSessionBroadcastListener,
  takeSessionNotice,
} from '@/stores/session'
import { SESSION_CHANNEL_NAME, closeSessionBroadcast } from '@/utils/sessionBroadcast'
import { readCredential, writeCredential } from '@/utils/credentialStorage'
import {
  IDLE_TIMEOUT_MS,
  LAST_ACTIVITY_STORAGE_KEY,
  markActivity,
  stopIdleWatch,
} from '@/utils/idleTimeout'
import * as idb from '@/stores/offlineQueue.idb'
import { aTransaction, anEvidenceItem, aUser } from '@/test-support/factories'
import type { QueueEntry } from '@/types/queue'
import type { User } from '@/types/domain'
import type { Router } from 'vue-router'

/**
 * La propagation inter-onglets d'une fin de session (Story 2.7, AC3 — décision D-B).
 *
 * <p><b>Comment un « autre onglet » est simulé ici.</b> `BroadcastChannel` garantit qu'un
 * canal ne reçoit JAMAIS ses propres messages. La suite ouvre donc son propre canal sur le
 * même nom : tout ce qu'il reçoit vient forcément de la production, et tout ce qu'il émet
 * n'est entendu que par elle. C'est un oracle exact — pas un espion posé sur la fonction
 * qu'on prétend prouver.
 *
 * <p><b>Minuteries RÉELLES dans tout ce fichier</b>, délibérément : la livraison d'un
 * message est asynchrone et passe par la boucle d'événements, que `vi.useFakeTimers()`
 * fige. Le mélange n'aurait pas fait rougir la suite, il l'aurait suspendue — c'est le
 * piège que T3 a déjà payé 30 s de délai d'attente et zéro diagnostic. `vi.waitFor`
 * fonctionne ici précisément parce que rien n'est factice.
 *
 * <p><b>Chaque assertion négative est appariée à une positive dans le MÊME test</b>, avec
 * des comptes exacts : « aucun message reçu » et « aucune requête réseau » sont
 * satisfaits à la perfection par un canal mort ou par une fonction qui n'a rien fait.
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
 * CET onglet-ci vient d'agir : il n'est PAS inactif, et le veto du récepteur (D-F) le
 * protège d'une annonce `'idle'` venue d'ailleurs.
 *
 * <p>Horloge RÉELLE, comme partout dans ce fichier : `markActivity()` écrit `Date.now()`
 * et `isIdleExpired()` relit le même horodatage. Aucune minuterie factice n'est nécessaire
 * pour fabriquer « actif » ou « inactif » — il suffit d'écrire un instant, et une horloge
 * figée aurait suspendu la livraison des messages.
 */
function stillActive() {
  markActivity()
}

/**
 * CET onglet-ci n'a rien vu passer depuis plus longtemps que le délai : il est inactif LUI
 * AUSSI, donc l'appareil est réellement abandonné et le veto ne s'applique pas.
 *
 * <p>Le seuil est LU depuis la production (`IDLE_TIMEOUT_MS`) et jamais recopié : un « 15
 * minutes » écrit ici resterait vert après un ajustement du seuil, en prouvant une
 * politique que la production n'applique plus.
 */
function alsoIdle() {
  writeCredential(LAST_ACTIVITY_STORAGE_KEY, String(Date.now() - IDLE_TIMEOUT_MS - 1000))
}

/** Un routeur réduit à ce que le gestionnaire lit vraiment. */
function fakeRouter(fullPath: string, name = 'escrow-detail') {
  return {
    currentRoute: { value: { name, fullPath } },
    replace: vi.fn(async () => undefined),
  } as unknown as Router
}

/**
 * L'AUTRE onglet du même appareil : son propre canal, sur le même nom.
 *
 * <p>`received` ne peut contenir que ce que la PRODUCTION a émis — un canal n'entend pas
 * ses propres messages. C'est ce qui rend l'assertion « le récepteur ne ré-émet pas »
 * démontrable au lieu d'être une intention.
 */
function otherTab() {
  const channel = new BroadcastChannel(SESSION_CHANNEL_NAME)
  const received: unknown[] = []
  channel.onmessage = (event: MessageEvent) => {
    received.push(event.data)
  }
  return {
    received,
    /** Ce que la production émet réellement, à la lettre. */
    announce(reason: string | null = 'logout') {
      channel.postMessage({ type: 'session-end', reason })
    },
    /** Pour les messages mal formés : on écrit la charge telle quelle. */
    post(payload: unknown) {
      channel.postMessage(payload)
    },
    close() {
      channel.close()
    },
  }
}

/**
 * COMMENT ON PROUVE QU'UN MESSAGE A ÉTÉ IGNORÉ — et pourquoi ce n'est pas une attente.
 *
 * <p>Première version de ce fichier : « on poste le message douteux, on attend 20 ms, on
 * constate que rien n'a bougé ». La passe de mutation l'a démasquée. Avec le discriminant
 * de `sessionBroadcast` RETIRÉ, le test correspondant rougissait lancé seul et restait
 * VERT dans la suite complète : sous charge, les 20 ms s'écoulaient avant que le message
 * ne soit livré, et l'assertion négative était satisfaite par le vide. Une preuve qui
 * dépend de la charge de la machine n'est pas une preuve.
 *
 * <p>Le remplacement s'appuie sur une garantie de la spécification plutôt que sur
 * l'horloge : un canal livre ses messages DANS L'ORDRE. On fait donc suivre le message
 * douteux d'un message bien formé, on attend l'effet du SECOND — donc le premier est
 * nécessairement traité — et on regarde ce que le premier aurait laissé s'il avait été
 * honoré. Le marqueur choisi est le MOTIF (`'idle'` en écrit un, `'logout'` non) : il
 * distingue les deux messages là où le jeton, la navigation et les comptes d'appels sont
 * identiques dans les deux hypothèses.
 *
 * <p>⚠️ Depuis le veto du récepteur (D-F), ce marqueur n'a de valeur QUE sur un onglet
 * lui-même inactif : sur un onglet actif, une annonce `'idle'` honorée ne laisserait plus
 * aucun motif, et l'assertion serait satisfaite par le veto au lieu de l'être par la garde
 * qu'elle nomme. Les deux tests qui s'en servent appellent donc `alsoIdle()` — sans quoi
 * l'ajout du veto aurait creusé DEUX gardes déjà prouvées par mutation en T4 (le
 * discriminant de message et le garde d'authentification).
 */
const IGNORED_BUT_IDLE = { type: 'session-end', reason: 'idle' }

/**
 * LE TÉMOIN — comment savoir qu'un message a été TRAITÉ quand il ne doit rien produire.
 *
 * <p>Le tour d'écriture ci-dessus ne marche que si le message douteux et le message
 * témoin s'adressent au même onglet DANS LE MÊME ÉTAT. Pour la garde « personne n'est
 * connecté ici », c'est impossible : signer quelqu'un juste après avoir posté rendrait
 * l'onglet authentifié AVANT la livraison, et le message serait honoré à bon droit.
 *
 * <p>On s'appuie donc sur l'autre garantie de la spécification : un message est distribué
 * aux canaux DANS LEUR ORDRE DE CRÉATION. Ce témoin est ouvert APRÈS le canal de
 * production ; quand il reçoit, la production a donc déjà reçu. Vérifié dans ce runtime
 * sur 200 messages et deux canaux : aucune inversion.
 */
function witness() {
  const channel = new BroadcastChannel(SESSION_CHANNEL_NAME)
  const seen: unknown[] = []
  channel.onmessage = (event: MessageEvent) => {
    seen.push(event.data)
  }
  return {
    seen,
    close: () => channel.close(),
    delivered: (count: number) => vi.waitFor(() => expect(seen).toHaveLength(count)),
  }
}

let other: ReturnType<typeof otherTab>

beforeEach(() => {
  globalThis.indexedDB = new IDBFactory()
  idb.resetDBForTests()
  localStorage.clear()
  sessionStorage.clear()
  stopIdleWatch()
  // Le canal de production est un singleton de MODULE : sans cette fermeture, l'abonné
  // du test précédent entendrait les messages du suivant et un test dépendrait de son
  // prédécesseur — exactement ce que `stopIdleWatch` évite pour la veille d'inactivité.
  closeSessionBroadcast()
  other = otherTab()
  setActivePinia(createPinia())
  vi.mocked(apiClient.request).mockReset()
  vi.mocked(apiClient.request).mockResolvedValue({ data: {} })
  vi.clearAllMocks()
})

afterEach(() => {
  other.close()
  closeSessionBroadcast()
  stopIdleWatch()
  delete (globalThis as { caches?: CacheStorage }).caches
  vi.restoreAllMocks()
})

describe('L’émission — l’onglet où l’action a lieu annonce aux autres', () => {
  it('annonce les TROIS raisons, et chacune porte la sienne', async () => {
    // La raison voyage : c'est elle qui permettra à l'onglet récepteur d'afficher le
    // motif que l'onglet émetteur affiche (`'idle'` en a un, les deux autres non).
    // Écrites à la file plutôt qu'en boucle : `no-await-in-loop` est actif, et surtout un
    // `it.each` dirait « chaque raison est annoncée » là où ce qu'on veut prouver est
    // « les TROIS le sont, et rien d'autre ne l'est ».
    stubCaches()
    signIn(ALICE)
    await endSession({ reason: 'logout' })
    signIn(ALICE)
    await endSession({ reason: 'expired' })
    signIn(ALICE)
    await endSession({ reason: 'idle' })

    // Compte EXACT : « au moins un message » serait satisfait par une émission unique
    // câblée sur la seule déconnexion, et l'expiration d'inactivité ne se propagerait pas.
    await vi.waitFor(() => expect(other.received).toHaveLength(3))
    expect(other.received).toEqual([
      { type: 'session-end', reason: 'logout' },
      { type: 'session-end', reason: 'expired' },
      { type: 'session-end', reason: 'idle' },
    ])
  })

  it('le récepteur ne RÉ-ÉMET pas — sinon deux onglets se renvoient la balle sans fin', async () => {
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    signIn(ALICE)
    // Cet onglet-ci est inactif lui aussi : sans cela le veto du récepteur (D-F) écarterait
    // l'annonce `'idle'` ci-dessous et il n'y aurait plus de terminaison dont observer la
    // ré-émission. Le veto est prouvé pour lui-même plus bas.
    alsoIdle()

    // La raison reçue (`'idle'`) est DIFFÉRENTE de celle qu'on émettra ensuite
    // (`'logout'`) : c'est ce qui rend une ré-émission reconnaissable. Deux `'logout'`
    // auraient produit deux messages littéralement identiques, et le premier arrivé
    // aurait satisfait l'assertion à la place du second.
    other.announce('idle')
    // On attend que la terminaison ait bien eu lieu AVANT de conclure quoi que ce soit :
    // sans ce point d'ancrage, « aucun message en retour » serait aussi vrai d'un canal
    // mort que d'un récepteur discipliné.
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    // Puis on émet POUR DE BON depuis cet onglet. L'ordre de livraison étant garanti,
    // une ré-émission du récepteur serait arrivée AVANT ce message-ci : le premier
    // élément reçu la trahirait.
    signIn(ALICE)
    await endSession({ reason: 'logout' })

    await vi.waitFor(() => expect(other.received).toHaveLength(1))
    expect(other.received).toEqual([{ type: 'session-end', reason: 'logout' }])
  })
})

describe('La réception — terminaison locale, sans intervention et observable', () => {
  it('termine la session et ramène à l’authentification, cible conservée', async () => {
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    const auth = signIn(ALICE)
    const escrow = useEscrowStore()
    const evidence = useEvidenceStore()
    const queue = useOfflineQueueStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    evidence.items = [anEvidenceItem({ id: 1, originalFilename: 'invoice.pdf' })]
    queue.queue = [entry('alice-pending', ALICE.id, 1)]

    other.announce('logout')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    // « cessent d'afficher une interface authentifiée » : plus de jeton en mémoire, plus
    // de jeton dans le substrat de CET onglet — chaque onglet a le sien (AC1) — et plus
    // rien à l'écran.
    expect(auth.token).toBeNull()
    expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    expect(readCredential(USER_STORAGE_KEY)).toBeNull()
    expect(escrow.transactions).toEqual([])
    expect(evidence.items).toEqual([])
    expect(queue.queue).toEqual([])
    // Observable, et par une NAVIGATION : pas de rechargement silencieux (convention
    // Frontend du spine), et la cible de l'utilisateur est reportée (UX-DR32).
    expect(router.replace).toHaveBeenCalledTimes(1)
    expect(router.replace).toHaveBeenCalledWith({ name: 'auth', query: { redirect: '/escrow/42' } })
  })

  it('n’appelle AUCUN endpoint — l’émetteur est le révocateur unique (NFR-P2)', async () => {
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    signIn(ALICE)

    other.announce('logout')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    // N onglets ouverts produiraient N révocations sur `/auth/*`, que NFR-P2 limite en
    // débit : la déconnexion de l'utilisateur deviendrait une rafale anti-bruteforce
    // contre lui-même.
    expect(logoutUser).not.toHaveBeenCalled()

    // Appariée : l'onglet où l'action a VRAIMENT lieu, lui, révoque — une fois, avec le
    // jeton qui valait encore. Sans ce second temps, « personne n'appelle » passerait
    // aussi bien si plus personne n'appelait jamais.
    const auth = signIn(ALICE)
    const revokedToken = auth.token
    await endSession({ reason: 'logout' })
    expect(logoutUser).toHaveBeenCalledTimes(1)
    expect(logoutUser).toHaveBeenCalledWith(revokedToken)
  })

  it('ne retouche pas l’état PARTAGÉ de l’appareil — l’émetteur vient de le traiter', async () => {
    const del = stubCaches()
    await seedSharedDevice()
    localStorage.setItem(LAST_USER_STORAGE_KEY, String(ALICE.id))
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    signIn(ALICE)

    other.announce('logout')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    // IndexedDB, cache de lecture Workbox et marqueur d'appareil sont partagés par tous
    // les onglets. Les rejouer ici, ce serait N tours d'IndexedDB pour un travail déjà
    // fait — et concurrents de celui de l'émetteur.
    expect(await storedIds()).toEqual(['alice-pending', 'bob-pending'])
    expect(del).not.toHaveBeenCalled()
    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBe(String(ALICE.id))

    // Appariée : c'est bien l'ÉMETTEUR qui fait ce travail-là, et il le fait pour de bon.
    signIn(ALICE)
    await endSession({ reason: 'logout' })
    expect(await storedIds()).toEqual(['bob-pending'])
    expect(del).toHaveBeenCalledWith(READ_CACHE_NAME)
    expect(localStorage.getItem(LAST_USER_STORAGE_KEY)).toBeNull()
  })

  it('le MOTIF voyage : une inactivité annoncée ailleurs s’explique ici aussi', async () => {
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    signIn(ALICE)
    // Inactif lui aussi — c'est le cas où l'annonce `'idle'` traverse (voir le veto, D-F).
    alsoIdle()

    other.announce('idle')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    // Le motif est écrit dans le substrat de CET onglet : sans lui, la personne trouverait
    // l'écran de connexion sans la moindre explication de ce qui vient de se passer.
    expect(takeSessionNotice()).toBe('idle')

    // Appariée : une déconnexion volontaire annoncée par un autre onglet n'a rien à
    // expliquer — la personne l'a voulue. Un motif affiché ici serait un mensonge.
    signIn(ALICE)
    other.announce('logout')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalledTimes(2))
    expect(takeSessionNotice()).toBeNull()
  })

  it('un onglet qui n’a rien à terminer ne navigue pas', async () => {
    stubCaches()
    const router = fakeRouter('/auth?redirect=/escrow/42', 'auth')
    installSessionBroadcastListener(router)
    // Ouvert APRÈS le canal de production : voir `witness`.
    const seen = witness()
    // Inactif AUSSI, et c'est load-bearing : le message porte `'idle'`, et sur un onglet
    // actif le veto (D-F) l'écarterait AVANT que le garde d'authentification ait à se
    // prononcer — l'assertion ci-dessous serait alors satisfaite par la mauvaise garde.
    alsoIdle()

    // Personne n'est connecté dans cet onglet-ci : un `replace` vers `auth` depuis `auth`
    // serait une navigation visible et sans objet, qui écraserait au passage le
    // `redirect` que l'utilisateur venait d'obtenir. Le message porte `'idle'` — honoré,
    // il laisserait un motif derrière lui.
    other.post(IGNORED_BUT_IDLE)
    await seen.delivered(1)

    expect(router.replace).not.toHaveBeenCalled()
    expect(takeSessionNotice()).toBeNull()

    // Appariée : l'écouteur est bien vivant, il attendait seulement d'avoir quelque chose
    // à terminer. Sans ce second temps, un écouteur jamais installé passerait le test.
    const auth = signIn(ALICE)
    other.announce('logout')
    await vi.waitFor(() => expect(auth.token).toBeNull())
    expect(router.replace).toHaveBeenCalledTimes(1)

    seen.close()
  })
})

describe('Le message — ce que le canal accepte, et ce qu’il ignore', () => {
  it('un message étranger sur le canal ne détruit aucune session', async () => {
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    const auth = signIn(ALICE)
    // Inactif, pour la même raison que ci-dessus : le marqueur des messages ignorés est le
    // motif de `'idle'`, et sur un onglet actif c'est le veto (D-F) qui les écarterait —
    // le discriminant de message ne serait plus prouvé par rien.
    alsoIdle()

    // Le canal porte un nom générique ; une story ultérieure pourrait y faire transiter
    // autre chose. Sans discriminant, chacun de ces messages déconnecterait tout le monde.
    // Le premier porte `reason: 'idle'` : honoré, il laisserait un motif derrière lui.
    other.post({ ...IGNORED_BUT_IDLE, type: 'locale-changed', locale: 'fr' })
    other.post('session-end')
    other.post(null)

    // Appariée : le message ATTENDU, lui, passe. Le discriminant filtre, il ne bloque pas.
    other.announce('logout')
    await vi.waitFor(() => expect(auth.token).toBeNull())

    // Les trois messages étrangers ont été traités avant celui-ci (ordre garanti) et
    // n'ont rien terminé : aucun motif, et une seule navigation.
    expect(takeSessionNotice()).toBeNull()
    expect(router.replace).toHaveBeenCalledTimes(1)
  })

  it('une annonce sans raison lisible termine quand même la session, et le signale', async () => {
    // Direction sûre : le canal est de même origine, seule notre propre application y
    // écrit. Une raison qu'on ne comprend pas vient d'une version plus récente de
    // l'application dans un autre onglet — la session y a bel et bien pris fin. On
    // termine donc, sur le chemin CONSERVATEUR d'`endSession`, et on le dit.
    const complain = vi.spyOn(console, 'error').mockImplementation(() => {})
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    const auth = signIn(ALICE)

    other.post({ type: 'session-end', reason: 42 })
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    expect(auth.token).toBeNull()
    expect(complain).toHaveBeenCalledWith(expect.stringContaining('unknown end-of-session reason'))

    // Appariée : une raison reconnue ne produit AUCUNE plainte. Sans ce second temps, un
    // diagnostic émis à chaque annonce normale satisferait l'assertion ci-dessus.
    complain.mockClear()
    signIn(ALICE)
    other.announce('logout')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalledTimes(2))
    expect(complain).not.toHaveBeenCalledWith(
      expect.stringContaining('unknown end-of-session reason'),
    )
  })
})

describe('Le VETO DU RÉCEPTEUR — l’inactivité d’un onglet n’est pas celle des autres (D-F)', () => {
  /**
   * <p><b>Le défaut que ces quatre tests gardent.</b> L'horodatage d'inactivité vit dans le
   * substrat du jeton, donc en `sessionStorage` par défaut : il est propre à chaque onglet.
   * Un onglet laissé en arrière-plan atteint son échéance, annonce `'idle'`, et tuait la
   * session d'un onglet où quelqu'un travaillait. Le veto rend la main au récepteur — pour
   * cette raison-là, et pour elle seule.
   */

  it('un onglet ACTIF ignore l’annonce d’inactivité et poursuit sa session', async () => {
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    const auth = signIn(ALICE)
    stillActive()
    // Ouvert APRÈS le canal de production : quand le témoin reçoit, la production a déjà
    // reçu. C'est ce qui distingue « le message a été IGNORÉ » de « le message n'est pas
    // encore arrivé » — une attente de vingt millisecondes ne le distinguerait pas, et
    // c'est le défaut de preuve que la passe de mutation de T4 a démasqué.
    const seen = witness()

    other.announce('idle')
    await seen.delivered(1)

    // La session tient : en mémoire, dans le substrat de cet onglet, et à l'écran.
    expect(auth.token).toBe('alice@corp.example-token')
    expect(readCredential(TOKEN_STORAGE_KEY)).toBe('alice@corp.example-token')
    expect(router.replace).not.toHaveBeenCalled()
    expect(takeSessionNotice()).toBeNull()

    // Appariée, et indispensable : sans ce second temps, un écouteur jamais installé — ou
    // un canal mort — satisferait les quatre assertions ci-dessus à la perfection.
    other.announce('logout')
    await vi.waitFor(() => expect(auth.token).toBeNull())
    expect(router.replace).toHaveBeenCalledTimes(1)

    seen.close()
  })

  it('un onglet lui-même INACTIF meurt de la même annonce — NFR-P8 est intact', async () => {
    // L'appareil réellement abandonné : tous les onglets sont inactifs, donc tous
    // terminent. C'est le scénario de la story, et le veto ne le touche pas.
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)
    const auth = signIn(ALICE)
    alsoIdle()

    other.announce('idle')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalled())

    expect(auth.token).toBeNull()
    expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    expect(readCredential(USER_STORAGE_KEY)).toBeNull()
    expect(router.replace).toHaveBeenCalledWith({ name: 'auth', query: { redirect: '/escrow/42' } })
    // Et il l'explique : le motif est celui de l'inactivité, pas le silence d'un logout.
    expect(takeSessionNotice()).toBe('idle')
  })

  it('une DÉCONNEXION traverse le veto — actif ou non, le geste ne se discute pas', async () => {
    // Un veto qui s'appliquerait à `'logout'` laisserait un onglet actif authentifié sur un
    // appareil que son propriétaire vient de rendre. Ce serait un défaut de sécurité, pas
    // une amélioration de confort.
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)

    const auth = signIn(ALICE)
    stillActive()
    other.announce('logout')
    await vi.waitFor(() => expect(auth.token).toBeNull())
    expect(router.replace).toHaveBeenCalledTimes(1)

    // Le même message sur un onglet inactif : il traverse aussi, évidemment. Les deux cas
    // dans le même test, parce que ce qu'on prouve est précisément que l'état d'activité
    // n'entre PAS en ligne de compte pour cette raison-là.
    signIn(ALICE)
    alsoIdle()
    other.announce('logout')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalledTimes(2))
    expect(auth.token).toBeNull()
  })

  it('un 403 nu (« expired ») traverse le veto — c’est un verdict du serveur', async () => {
    // Le jeton est déjà refusé côté serveur : un onglet qui se déclarerait « actif »
    // continuerait d'afficher une interface authentifiée avec un jeton mort.
    stubCaches()
    const router = fakeRouter('/escrow/42')
    installSessionBroadcastListener(router)

    const auth = signIn(ALICE)
    stillActive()
    other.announce('expired')
    await vi.waitFor(() => expect(auth.token).toBeNull())
    expect(router.replace).toHaveBeenCalledTimes(1)
    // Un 403 nu reste muet : c'est le comportement que la Story 1.9 lui a donné, et
    // l'assertion le verrouille ici aussi.
    expect(takeSessionNotice()).toBeNull()

    signIn(ALICE)
    alsoIdle()
    other.announce('expired')
    await vi.waitFor(() => expect(router.replace).toHaveBeenCalledTimes(2))
    expect(auth.token).toBeNull()
  })
})

describe('Le repli silencieux — `BroadcastChannel` absent', () => {
  it('la propagation dégrade, la déconnexion aboutit', async () => {
    stubCaches()
    // Fermé d'abord : le module garde son canal ouvert une fois créé, et retirer la
    // globale après coup ne prouverait rien de la branche de repli.
    closeSessionBroadcast()
    const saved = globalThis.BroadcastChannel
    delete (globalThis as { BroadcastChannel?: typeof BroadcastChannel }).BroadcastChannel
    try {
      const router = fakeRouter('/escrow/42')
      expect(() => installSessionBroadcastListener(router)).not.toThrow()
      const auth = signIn(ALICE)

      // Une déconnexion qui lèverait parce qu'une API optionnelle manque serait un défaut
      // bien pire que l'absence de propagation.
      await expect(endSession({ reason: 'logout' })).resolves.toBeUndefined()
      expect(auth.token).toBeNull()
      expect(readCredential(TOKEN_STORAGE_KEY)).toBeNull()
    } finally {
      globalThis.BroadcastChannel = saved
    }

    // Appariée, et elle n'est pas décorative : sans elle, un module qui n'annoncerait
    // JAMAIS rien passerait le test ci-dessus à la perfection.
    closeSessionBroadcast()
    other.close()
    other = otherTab()
    signIn(ALICE)
    await endSession({ reason: 'logout' })
    await vi.waitFor(() => expect(other.received).toHaveLength(1))
    expect(other.received).toEqual([{ type: 'session-end', reason: 'logout' }])
  })
})
