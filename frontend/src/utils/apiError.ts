import axios from 'axios'

/**
 * Lecture d'une erreur d'appel API, sans assertion de type.
 *
 * <p>En JavaScript, `catch (err) { err.response?.data?.message }` marchait par
 * ignorance : `err` peut être n'importe quoi — une `TypeError` levée par notre propre
 * code, un rejet de chaîne, une panne réseau sans réponse. Le typage rend cette
 * ignorance visible (`err` est `unknown`), et la tentation est alors d'écrire
 * `(err as any).response`, ce qui restaure exactement le défaut qu'on vient de rendre
 * visible.
 *
 * <p>Ces deux fonctions font le vrai travail : elles INTERROGENT la valeur avant de la
 * lire. `axios.isAxiosError` est le garde fourni par la bibliothèque elle-même ; tout ce
 * qui n'en est pas une n'a ni réponse, ni en-têtes, et se comporte ici comme une panne
 * sans corps — ce qu'elle est.
 */

/** Corps d'erreur du backend : `GlobalExceptionHandler` sérialise `code` et `message`. */
interface ApiErrorBody {
  code?: string
  message?: string
}

/**
 * Message porté par la réponse d'erreur, ou `null`.
 *
 * <p>Rend `null` plutôt qu'un message générique : c'est à l'appelant de choisir son
 * repli, et il le choisit souvent traduit. Un défaut anglais rendu ici traverserait
 * l'i18n sans que personne ne le voie (le défaut relevé en Story 2.1).
 */
export function apiErrorMessage(err: unknown): string | null {
  if (!axios.isAxiosError(err)) return null
  const body = err.response?.data as ApiErrorBody | undefined
  return typeof body?.message === 'string' ? body.message : null
}

/** Code d'erreur applicatif (`ErrorCode` côté backend), ou `null`. */
export function apiErrorCode(err: unknown): string | null {
  if (!axios.isAxiosError(err)) return null
  const body = err.response?.data as ApiErrorBody | undefined
  return typeof body?.code === 'string' ? body.code : null
}

/** Statut HTTP de la réponse d'erreur, ou `null` quand la requête n'a jamais abouti. */
export function apiErrorStatus(err: unknown): number | null {
  if (!axios.isAxiosError(err)) return null
  return err.response?.status ?? null
}

/**
 * En-tête de réponse, en minuscules.
 *
 * <p>Existe pour `Retry-After` (AD-11) : c'est l'horloge SERVEUR qui dicte le compte à
 * rebours du renvoi de code, un minuteur local se remettant à zéro au rechargement.
 */
export function apiErrorHeader(err: unknown, name: string): string | null {
  if (!axios.isAxiosError(err)) return null
  const value = err.response?.headers?.[name.toLowerCase()]
  return typeof value === 'string' ? value : null
}
