import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { writeCredential, writePersistence } from '@/utils/credentialStorage'
import {
  IDLE_TIMEOUT_MINUTES,
  IDLE_TIMEOUT_MS,
  LAST_ACTIVITY_STORAGE_KEY,
  forgetActivity,
  inFlightRequests,
  isIdleExpired,
  markActivity,
  noteRequestSettled,
  noteRequestStarted,
  readLastActivity,
  startIdleWatch,
  stopIdleWatch,
} from '@/utils/idleTimeout'

/**
 * L'expiration d'inactivité (Story 2.7, AC2).
 *
 * <p><b>Le seul fichier du dépôt à faire avancer des minuteries factices.</b> Un seul
 * autre en utilise (`escrow.offline.spec.ts`) et uniquement par `setSystemTime` ; ce motif
 * est nouveau, et il est ISOLÉ ici pour une raison précise : `vi.waitFor` s'appuie sur les
 * minuteries RÉELLES et se bloque sous `vi.useFakeTimers()`. `session.spec.ts` en dépend
 * en deux endroits, et basculer ce fichier-là aurait suspendu la suite au lieu de la faire
 * rougir.
 *
 * <p><b>Instants sentinelles, jamais `new Date()`.</b> Deux horodatages réels tombent dans
 * la même milliseconde et ne distinguent rien — en particulier pas « il reste une
 * milliseconde » de « le budget est consommé », qui est exactement ce que cette suite
 * mesure.
 */
const T0 = new Date('2026-01-01T09:00:00.000Z')

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  // La veille est un état de MODULE : sans cet arrêt, une minuterie survivant à un test
  // se déclencherait pendant le suivant et lui attribuerait un appel qu'il n'a pas causé.
  stopIdleWatch()
  vi.useFakeTimers()
  vi.setSystemTime(T0)
})

afterEach(() => {
  stopIdleWatch()
  vi.useRealTimers()
  localStorage.clear()
  sessionStorage.clear()
})

describe('le délai, tel que le PO l’a tranché', () => {
  it('vaut 15 minutes, et les millisecondes en DÉRIVENT', () => {
    // Cette valeur n'existait dans aucun artefact amont : elle a été tranchée par Oscard
    // le 2026-08-10 et vit désormais dans la constante que la production applique. Ce test
    // ne vérifie pas un calcul, il ASSERVIT une décision produit — les autres tests, eux,
    // lisent la constante, ce qui les rendrait aveugles à un changement de sa valeur.
    expect(IDLE_TIMEOUT_MINUTES).toBe(15)
    expect(IDLE_TIMEOUT_MS).toBe(15 * 60 * 1000)
  })
})

describe('isIdleExpired — le seuil, à la milliseconde', () => {
  it('n’expire pas une milliseconde trop tôt, et expire à l’échéance', () => {
    markActivity()

    vi.setSystemTime(new Date(T0.getTime() + IDLE_TIMEOUT_MS - 1))
    expect(isIdleExpired()).toBe(false)

    // Appariée à la négative ci-dessus, dans le même test : sans elle, un
    // `return false` inconditionnel passerait pour un seuil respecté.
    vi.setSystemTime(new Date(T0.getTime() + IDLE_TIMEOUT_MS))
    expect(isIdleExpired()).toBe(true)
  })

  it('un horodatage ABSENT n’est pas une inactivité constatée', () => {
    // L'absence décrit une session ouverte avant cette story, ou dont l'horodatage s'est
    // perdu. La lire comme « expirée » déconnecterait tout le monde au déploiement pour un
    // fait que personne n'a établi. `enforceIdlePolicy` horodate ce cas-là sur-le-champ.
    expect(readLastActivity()).toBeNull()
    expect(isIdleExpired()).toBe(false)

    markActivity()
    vi.setSystemTime(new Date(T0.getTime() + IDLE_TIMEOUT_MS))
    expect(isIdleExpired()).toBe(true)
  })

  it('un horodatage ILLISIBLE est traité comme absent, pas comme frais', () => {
    // Un `try/catch` ne garde que contre ce qui lève, et `Number('hier matin')` ne lève
    // pas : il rend `NaN`, qui se compare `false` à tout. Sans traitement explicite, une
    // valeur éditée à la main rendrait la session éternelle en silence.
    writeCredential(LAST_ACTIVITY_STORAGE_KEY, 'hier matin')

    expect(readLastActivity()).toBeNull()

    markActivity()
    expect(readLastActivity()).toBe(T0.getTime())
  })
})

describe('l’horodatage vit dans le substrat du jeton', () => {
  it('suit la préférence « rester connecté », et n’est écrit que là', () => {
    writePersistence('local')

    markActivity()

    // Rangé ailleurs que le jeton, il survivrait à l'onglet qui l'a produit et la
    // personne suivante hériterait de la fraîcheur de la précédente.
    expect(localStorage.getItem(LAST_ACTIVITY_STORAGE_KEY)).toBe(String(T0.getTime()))
    expect(sessionStorage.getItem(LAST_ACTIVITY_STORAGE_KEY)).toBeNull()
  })

  it('forgetActivity le retire des DEUX substrats', () => {
    // Asymétrique avec la lecture, et délibérément : elle s'exécute quand on ne veut plus
    // rien laisser derrière soi, y compris dans le substrat qu'une bascule de préférence
    // en cours de route a pu peupler.
    localStorage.setItem(LAST_ACTIVITY_STORAGE_KEY, '111')
    sessionStorage.setItem(LAST_ACTIVITY_STORAGE_KEY, '222')

    forgetActivity()

    expect(localStorage.getItem(LAST_ACTIVITY_STORAGE_KEY)).toBeNull()
    expect(sessionStorage.getItem(LAST_ACTIVITY_STORAGE_KEY)).toBeNull()
  })
})

describe('la minuterie', () => {
  it('ne prévient pas une milliseconde trop tôt, et prévient une seule fois à l’échéance', () => {
    const onIdle = vi.fn()
    startIdleWatch(onIdle)
    markActivity()

    vi.advanceTimersByTime(IDLE_TIMEOUT_MS - 1)
    expect(onIdle).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(onIdle).toHaveBeenCalledTimes(1)
  })

  it('est réarmée par une frappe', () => {
    const onIdle = vi.fn()
    startIdleWatch(onIdle)

    vi.advanceTimersByTime(IDLE_TIMEOUT_MS - 1000)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'a' }))
    // Sans la frappe, l'échéance tombait ici même.
    vi.advanceTimersByTime(1000)
    expect(onIdle).not.toHaveBeenCalled()

    // Et le report est bien d'un délai PLEIN, pas d'un sursis arbitraire.
    vi.advanceTimersByTime(IDLE_TIMEOUT_MS)
    expect(onIdle).toHaveBeenCalledTimes(1)
  })

  it('arrêtée, elle ne prévient plus — et réinstallée, elle prévient de nouveau', () => {
    const onIdle = vi.fn()
    const stop = startIdleWatch(onIdle)

    stop()
    vi.advanceTimersByTime(IDLE_TIMEOUT_MS * 3)
    expect(onIdle).not.toHaveBeenCalled()

    // Contre-épreuve : la négative ci-dessus serait tout aussi verte si la veille ne
    // fonctionnait pas du tout.
    startIdleWatch(onIdle)
    markActivity()
    vi.advanceTimersByTime(IDLE_TIMEOUT_MS)
    expect(onIdle).toHaveBeenCalledTimes(1)
  })
})

describe('🔴 « inactivité » n’est PAS « absence de clic »', () => {
  it('un versement en vol tient la session vivante bien au-delà du délai', () => {
    // LE scénario le plus coûteux de cette story, et le plus silencieux : un dépôt de
    // preuve de 10 Mo sur une liaison de corridor lente. L'utilisateur clique « déposer »
    // puis ATTEND — plus longtemps que le délai, sans une seule interaction. Une minuterie
    // nourrie des seuls événements d'entrée tuerait la session au milieu du transfert et
    // détruirait exactement le travail que le refus de purger la file (décision Q2)
    // existe pour protéger.
    const onIdle = vi.fn()
    startIdleWatch(onIdle)

    noteRequestStarted()
    vi.advanceTimersByTime(IDLE_TIMEOUT_MS * 2)

    expect(onIdle).not.toHaveBeenCalled()
    expect(inFlightRequests()).toBe(1)

    // Appariée dans le même souffle : le transfert terminé, le compteur repart et la
    // session expire normalement. Sans cette moitié, un `onIdle` jamais appelé — une
    // minuterie cassée — satisferait la négative ci-dessus.
    noteRequestSettled()
    expect(inFlightRequests()).toBe(0)
    vi.advanceTimersByTime(IDLE_TIMEOUT_MS)
    expect(onIdle).toHaveBeenCalledTimes(1)
  })

  it('l’horodatage persisté reste frais pendant le transfert, pas seulement la minuterie', () => {
    // La minuterie meurt avec l'onglet ; l'horodatage, lui, est ce que lit le contrôle au
    // DÉMARRAGE. S'il n'était pas rafraîchi pendant le versement, un rechargement en
    // cours de transfert ferait passer la session pour inactive depuis une demi-heure.
    //
    // ⚠️ La seule assertion sur l'horodatage était CREUSE, et c'est la passe de mutation
    // qui l'a montré : le chemin « budget consommé » horodate lui aussi avant de prévenir,
    // si bien que retirer la garde des requêtes en vol laissait ce test parfaitement vert.
    // C'est la notification — et elle seule — qui distingue les deux chemins ; elle est
    // donc assérée ici avec le reste.
    const onIdle = vi.fn()
    startIdleWatch(onIdle)
    noteRequestStarted()

    vi.advanceTimersByTime(IDLE_TIMEOUT_MS)

    expect(onIdle).not.toHaveBeenCalled()
    expect(readLastActivity()).toBe(T0.getTime() + IDLE_TIMEOUT_MS)
    expect(isIdleExpired()).toBe(false)
  })

  it('le compteur ne descend jamais sous zéro', () => {
    // Un décompte négatif rendrait `inFlight > 0` faux pendant les requêtes SUIVANTES et
    // rouvrirait, sans bruit, le défaut que ce compteur ferme.
    noteRequestSettled()
    noteRequestSettled()
    expect(inFlightRequests()).toBe(0)

    noteRequestStarted()
    expect(inFlightRequests()).toBe(1)
  })
})
