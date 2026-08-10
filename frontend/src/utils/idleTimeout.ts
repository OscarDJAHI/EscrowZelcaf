import { readCredential, writeCredential, removeCredential } from './credentialStorage'

/**
 * L'expiration d'inactivité (Story 2.7, AC2, décision D4).
 *
 * <p><b>Pourquoi ce module vit dans `utils/` et non dans `stores/`.</b> Il est importé par
 * `api/client.ts`, qui a interdiction d'importer le routeur ou un store — le cycle
 * `router → stores/auth → api/auth → api/client` est documenté en `client.ts:101-102` et
 * son gagnant serait un client axios à moitié construit. `utils/` est la seule direction
 * que `client.ts` emprunte déjà. Ce module ne connaît donc ni store, ni routeur, ni
 * `endSession` : il mesure et il prévient, `stores/session.ts` décide.
 *
 * <p><b>Ce qu'il compte comme activité, et pourquoi ce n'est pas seulement le clic.</b>
 * Un versement de preuve de 10 Mo sur une liaison de corridor lente peut dépasser le
 * délai SANS UNE SEULE INTERACTION : l'utilisateur clique « déposer » puis attend. Une
 * minuterie qui n'écouterait que le clavier et le pointeur tuerait la session au milieu
 * du transfert et détruirait exactement le travail que la décision Q2 protège en
 * refusant de purger la file hors-ligne. Les requêtes EN VOL sont donc de l'activité de
 * plein droit, au même titre qu'une frappe — c'est le scénario le plus coûteux de cette
 * story s'il est manqué, et le plus silencieux.
 */

/**
 * 15 minutes, tranché par Oscard le 2026-08-10. La valeur n'existait dans aucun artefact
 * amont ; elle vit ICI et nulle part ailleurs.
 *
 * <p>Les minutes sont la constante SOURCE et les millisecondes en dérivent, parce que
 * l'affichage a besoin des unes et la minuterie des autres : écrire « 15 » dans le
 * catalogue i18n à côté d'un `15 * 60 * 1000` ici, c'est un seuil à deux endroits, et un
 * seuil à deux endroits diverge au premier ajustement — le message annoncerait un délai
 * que la minuterie n'applique plus, sans que rien ne rougisse.
 */
export const IDLE_TIMEOUT_MINUTES = 15
export const IDLE_TIMEOUT_MS = IDLE_TIMEOUT_MINUTES * 60 * 1000

/**
 * L'horodatage de dernière activité, dans le MÊME substrat que le jeton (T3).
 *
 * <p>Suivre le jeton n'est pas une commodité : rangé en `localStorage` pendant qu'une
 * session vit en `sessionStorage`, cet horodatage survivrait à l'onglet qui l'a produit
 * et la personne suivante hériterait de la fraîcheur de la précédente. Rangé dans le
 * substrat du jeton, il naît et meurt avec lui.
 */
export const LAST_ACTIVITY_STORAGE_KEY = 'escrow_last_activity'

/**
 * Les événements d'entrée écoutés — DISCRETS, délibérément.
 *
 * <p>`mousemove` et `scroll` sont exclus : ils se déclenchent des dizaines de fois par
 * seconde, donc autant d'écritures dans le stockage, et une souris bousculée n'est pas
 * une intention. Le prix de ce choix est nommé plutôt que caché : quelqu'un qui LIRAIT un
 * écran pendant quinze minutes sans toucher ni clavier ni pointeur verrait sa session
 * expirer. C'est le comportement voulu d'une politique d'appareil partagé — et toute
 * action réelle, y compris un simple appel API déclenché par l'écran, réarme le compteur.
 */
const ACTIVITY_EVENTS = ['pointerdown', 'keydown'] as const

let inFlight = 0
let timer: ReturnType<typeof setTimeout> | undefined
let notifyIdle: (() => void) | null = null

/**
 * Le nombre de requêtes actuellement en vol.
 *
 * <p>Exposé pour que la suite puisse asserter l'état du compteur lui-même, et pas
 * seulement son effet : un compteur qui ne redescend jamais tiendrait la session
 * éternellement vivante, et l'observer par le seul comportement de la minuterie ne
 * distinguerait pas ce défaut-là d'un fonctionnement correct.
 */
export function inFlightRequests(): number {
  return inFlight
}

/** Une requête part. Elle compte comme activité DÈS son émission, pas à son retour. */
export function noteRequestStarted(): void {
  inFlight += 1
  markActivity()
}

/**
 * Une requête se règle — succès ou échec, les deux passent ici.
 *
 * <p>Le plancher à zéro n'est pas de la superstition : un décompte qui passerait sous
 * zéro rendrait `inFlight > 0` faux pendant les requêtes SUIVANTES et rouvrirait
 * exactement le défaut que ce compteur ferme.
 */
export function noteRequestSettled(): void {
  inFlight = Math.max(0, inFlight - 1)
  markActivity()
}

/**
 * Horodate l'instant présent et remet la minuterie à plein.
 *
 * <p>Appelée sans condition, y compris quand personne n'est connecté : ce module ne sait
 * pas ce qu'est une session — il ne connaît aucune clé métier, exactement comme
 * `credentialStorage`. C'est `enforceIdlePolicy` (`stores/session.ts`) qui vérifie
 * l'authentification avant d'agir, et `endSession` qui efface l'horodatage en partant.
 */
export function markActivity(): void {
  writeCredential(LAST_ACTIVITY_STORAGE_KEY, String(Date.now()))
  if (notifyIdle) arm(IDLE_TIMEOUT_MS)
}

/** Efface l'horodatage des DEUX substrats — appelée à la fin de toute session. */
export function forgetActivity(): void {
  removeCredential(LAST_ACTIVITY_STORAGE_KEY)
}

/**
 * L'instant de dernière activité, ou `null` s'il est absent OU illisible.
 *
 * <p>Les deux cas sont confondus À DESSEIN. Un `try/catch` ne garde que contre ce qui
 * lève, et `Number('n\'importe quoi')` ne lève pas : il rend `NaN`, qui se compare `false`
 * à tout et ferait silencieusement passer une session pour fraîche. Le nul est explicite,
 * et son traitement l'est aussi (voir `enforceIdlePolicy`).
 */
export function readLastActivity(): number | null {
  const raw = readCredential(LAST_ACTIVITY_STORAGE_KEY)
  if (raw === null || raw.trim() === '') return null
  const at = Number(raw)
  return Number.isFinite(at) ? at : null
}

/**
 * Le délai est-il dépassé ?
 *
 * <p>Un horodatage ABSENT rend `false`, et c'est une décision. L'absence n'établit pas
 * l'inactivité : elle décrit une session ouverte avant que cette story n'existe, ou dont
 * l'horodatage s'est perdu. La lire comme « expirée » déconnecterait tout le monde au
 * déploiement, pour un fait que personne n'a constaté. L'écart est nommé plutôt que coché
 * — voir `enforceIdlePolicy`, qui horodate cette session-là sur-le-champ pour qu'elle
 * devienne mesurable, au prix d'une seule fenêtre de 15 minutes, une seule fois.
 */
export function isIdleExpired(): boolean {
  const last = readLastActivity()
  if (last === null) return false
  return Date.now() - last >= IDLE_TIMEOUT_MS
}

/** Ce qu'il reste du budget d'inactivité, jamais négatif. */
function remainingBudget(): number {
  const last = readLastActivity()
  if (last === null) return IDLE_TIMEOUT_MS
  return Math.max(0, IDLE_TIMEOUT_MS - (Date.now() - last))
}

function arm(delay: number): void {
  if (timer !== undefined) clearTimeout(timer)
  timer = setTimeout(tick, delay)
}

/**
 * L'échéance de la minuterie — et les trois raisons de ne PAS conclure à l'inactivité.
 *
 * <p>Ce n'est pas de la défense en profondeur décorative : la minuterie est réarmée par
 * `markActivity`, mais elle peut aussi échoir sur un horodatage plus frais qu'elle
 * (onglet réveillé après une mise en veille, où le navigateur a retardé le `setTimeout`).
 * On recalcule donc toujours depuis l'horodatage, source unique de vérité.
 */
function tick(): void {
  // 1. Une requête en vol EST de l'activité. Le versement de 10 Mo qui dépasse le délai
  //    sans une seule interaction ne doit pas être interrompu par sa propre lenteur.
  if (inFlight > 0) {
    markActivity()
    return
  }
  // 2. Le budget n'est pas consommé : on repose l'échéance sur ce qu'il en reste.
  const remaining = remainingBudget()
  if (remaining > 0) {
    arm(remaining)
    return
  }
  // 3. Consommé. On repart pour un tour AVANT de prévenir : si l'appelant décide de ne
  //    rien faire — personne n'est connecté, on est déjà sur l'écran d'authentification —
  //    la veille doit continuer pour la session suivante, sinon un seul faux départ
  //    désarmerait la mesure pour la vie de l'onglet.
  markActivity()
  notifyIdle?.()
}

/**
 * Arrête la veille : écouteurs retirés, minuterie éteinte, compteur remis à zéro.
 *
 * <p>Idempotente, et appelée par `startIdleWatch` avant toute installation : une seconde
 * installation laisserait sinon les écouteurs de la première derrière elle, et chaque
 * frappe compterait deux fois — inoffensif ici, mais c'est le genre de fuite qui rend un
 * test suivant dépendant de son prédécesseur.
 */
export function stopIdleWatch(): void {
  if (typeof window !== 'undefined') {
    for (const type of ACTIVITY_EVENTS) window.removeEventListener(type, onActivity)
  }
  if (timer !== undefined) clearTimeout(timer)
  timer = undefined
  notifyIdle = null
  inFlight = 0
}

function onActivity(): void {
  markActivity()
}

/**
 * Installe la veille d'inactivité et rend sa fonction d'arrêt.
 *
 * <p>N'horodate PAS à l'installation, volontairement : elle repart du budget restant.
 * Écrire un horodatage neuf ici rendrait l'ordre des appels de `main.ts` load-bearing —
 * installée avant le contrôle au démarrage, la veille rafraîchirait l'horodatage que ce
 * contrôle doit lire, et l'onglet rouvert après une heure passerait pour actif. Une
 * mesure qui dépend de l'ordre de deux lignes est une mesure qu'un réordonnancement
 * innocent supprime.
 */
export function startIdleWatch(onIdle: () => void): () => void {
  stopIdleWatch()
  if (typeof window === 'undefined') return () => {}
  notifyIdle = onIdle
  for (const type of ACTIVITY_EVENTS) {
    window.addEventListener(type, onActivity, { passive: true })
  }
  arm(remainingBudget())
  return stopIdleWatch
}
