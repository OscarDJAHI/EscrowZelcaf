/**
 * Lecture d'une erreur d'appel API, sans assertion de type.
 *
 * <p>En JavaScript, `catch (err) { err.response?.data?.message }` marchait par
 * ignorance : `err` peut être n'importe quoi — une `TypeError` levée par notre propre
 * code, un rejet de chaîne, une panne réseau sans réponse. Le typage rend cette
 * ignorance visible (`err` est `unknown`), et la tentation est alors d'écrire
 * `(err as any).response`, ce qui restaure exactement le défaut qu'on vient de rendre
 * visible. Ces fonctions INTERROGENT la valeur avant de la lire.
 *
 * <p><b>Lecture STRUCTURELLE, et non `axios.isAxiosError`.</b> Le garde d'axios paraît
 * plus rigoureux ; il change en réalité le contrat. Il exige un `isAxiosError === true`
 * sur l'objet, si bien qu'un rejet fabriqué à la main — ce que font les suites, et ce que
 * `replayFailure.ts` lit déjà structurellement dans ce dépôt — retomberait
 * silencieusement sur le repli. Le défaut ne serait visible nulle part : aucune assertion
 * ne couvre aujourd'hui le message d'erreur de connexion, si bien qu'un message serveur
 * cessant de s'afficher laisserait la suite entièrement verte. On lit donc la forme, comme
 * le faisait le code d'origine, et on la lit prudemment.
 */

/** Corps d'erreur du backend : `GlobalExceptionHandler` sérialise `code` et `message`. */
interface ApiErrorBody {
  code?: unknown
  message?: unknown
}

interface ResponseLike {
  status?: unknown
  data?: unknown
  headers?: unknown
}

/** La réponse portée par un rejet, quand il y en a une. */
function responseOf(err: unknown): ResponseLike | null {
  if (!err || typeof err !== 'object') return null
  const res = (err as { response?: unknown }).response
  return res && typeof res === 'object' ? (res as ResponseLike) : null
}

function bodyOf(err: unknown): ApiErrorBody | null {
  const data = responseOf(err)?.data
  return data && typeof data === 'object' ? (data as ApiErrorBody) : null
}

/**
 * Le rejet porte-t-il une réponse HTTP ?
 *
 * <p>Distinct d'un statut absent : « aucune réponse » est une panne réseau ou un délai
 * dépassé, que `classifyReplayFailure` doit classer transitoire. Un statut illisible sur
 * une réponse bien présente, lui, ne dit pas la même chose.
 */
export function apiHasResponse(err: unknown): boolean {
  return responseOf(err) !== null
}

/**
 * Message porté par la réponse d'erreur, ou `null`.
 *
 * <p>Rend `null` plutôt qu'un message générique : c'est à l'appelant de choisir son
 * repli, et il le choisit souvent traduit. Un défaut anglais rendu ici traverserait
 * l'i18n sans que personne ne le voie (le défaut relevé en Story 2.1).
 */
export function apiErrorMessage(err: unknown): string | null {
  const message = bodyOf(err)?.message
  return typeof message === 'string' ? message : null
}

/** Code d'erreur applicatif (`ErrorCode` côté backend), ou `null`. */
export function apiErrorCode(err: unknown): string | null {
  const code = bodyOf(err)?.code
  return typeof code === 'string' ? code : null
}

/** Statut HTTP de la réponse d'erreur, ou `null` quand la requête n'a jamais abouti. */
export function apiErrorStatus(err: unknown): number | null {
  const status = responseOf(err)?.status
  return typeof status === 'number' ? status : null
}

/**
 * En-tête de réponse, lu en minuscules.
 *
 * <p>Existe pour `Retry-After` (AD-11) : c'est l'horloge SERVEUR qui dicte le compte à
 * rebours du renvoi de code, un minuteur local se remettant à zéro au rechargement.
 */
export function apiErrorHeader(err: unknown, name: string): string | null {
  const headers = responseOf(err)?.headers
  if (!headers || typeof headers !== 'object') return null
  const value = (headers as Record<string, unknown>)[name.toLowerCase()]
  return typeof value === 'string' ? value : null
}
