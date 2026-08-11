/**
 * Libellés dérivés d'un code machine — avec une RÈGLE UNIQUE de dégradation.
 *
 * <p>Ce module existe à cause d'une régression introduite par la migration i18n
 * elle-même (2e passe de revue). Avant, un composant écrivait
 * `state.replaceAll('_', ' ')` : moche, mais lisible pour n'importe quelle entrée.
 * Après, `t(`state.${state}`)` rendait la CLÉ BRUTE (`state.EXPIRED`) dès que l'état
 * sortait du catalogue — et `EXPIRED` arrive avec l'Epic 5. Le cas des événements était
 * pire : `$t(null)` ne dégrade pas, vue-i18n LÈVE « Invalid arguments », et la
 * bibliothèque n'entoure pas l'appel d'un `catch`, si bien que l'exception traversait le
 * rendu de la liste des boutons d'action.
 *
 * <p>La règle, une seule fois, pour tout le monde : <b>clé connue → traduction ; tout le
 * reste → forme lisible ; jamais de clé brute, jamais de levée.</b>
 *
 * <p>Les fonctions prennent un objet `{ t, te }` en premier argument plutôt que d'appeler
 * `useI18n()` : elles restent ainsi utilisables hors composant, et surtout testables sans
 * monter quoi que ce soit. `te()` (« translation exists ») est le seul moyen de distinguer
 * une clé absente d'une traduction qui vaudrait littéralement son propre nom.
 */

/**
 * Le strict nécessaire de vue-i18n, et rien de plus.
 *
 * <p>Déclarer ici les deux fonctions consommées plutôt que d'importer le type complet du
 * composeur prolonge la propriété que l'en-tête revendique : ces fonctions sont
 * appelables hors composant, et testables en leur passant deux `vi.fn()`.
 */
export interface I18nLike {
  // Le second paramètre est OPTIONNEL et le reste : `vi.fn()` en tient lieu sans rien
  // déclarer, et les appelants qui n'interpolent rien continuent d'écrire `t(key)`.
  t: (key: string, params?: Record<string, unknown>) => string
  te: (key: string) => boolean
}

/** Dernier filet : une valeur machine rendue lisible, sans jamais lever. */
export function humanize(code: unknown): string {
  if (code === null || code === undefined) return ''
  return String(code).replaceAll('_', ' ')
}

/**
 * Traduit `key` si elle existe, sinon `fallback`.
 *
 * <p><b>`params` absent ⇒ `t(key)` et non `t(key, {})`.</b> La distinction n'est pas
 * cosmétique : tous les appelants antérieurs à la 2.7 passent par ici, et plusieurs sont
 * testés contre un `t` simulé. Appeler systématiquement la forme à deux arguments aurait
 * changé la signature observée par ces doubles sans changer une seule ligne de leur code
 * — un test vert qui cesse d'attester ce qu'il attestait.
 */
function translateOr(
  { t, te }: I18nLike,
  key: string | null | undefined,
  fallback: string,
  params?: Record<string, unknown>,
): string {
  if (!key || !te(key)) return fallback
  return params ? t(key, params) : t(key)
}

/**
 * Traduit une clé quelconque, en dégradant sur sa dernière portion si elle est absente.
 *
 * <p>Pour les libellés LIBRES (boutons, titres) où l'appelant fournit la clé entière.
 * Rend `create` plutôt que `common.create` quand la clé manque : le préfixe n'apprend
 * rien à l'utilisateur, et une clé brute affichée est précisément ce que la Story 2.1 a
 * passé trois passes de revue à éliminer.
 *
 * <p><b>`params` (Story 2.7).</b> Certains libellés portent une valeur que l'utilisateur
 * doit lire AVANT d'agir — `common.loggingOut` annonce le délai maximal d'attente, comme
 * UX-DR26 l'exige. Sans interpolation ici, un tel libellé s'affichait avec son gabarit nu
 * (« ({seconds} s au plus) ») ou perdait le chiffre : les deux trahissent l'exigence.
 *
 * <p>La dégradation ne change pas : clé absente ⇒ forme lisible SANS interpolation. Le
 * gabarit n'existe que dans le catalogue, donc il n'y a rien à interpoler quand il manque.
 */
export function translateOrHumanize(
  i18n: I18nLike,
  key: string | null | undefined,
  params?: Record<string, unknown>,
): string {
  if (!key) return ''
  return translateOr(i18n, key, humanize(String(key).split('.').pop()), params)
}

/** Libellé d'un état du cycle de vie escrow. */
export function stateLabel(i18n: I18nLike, state: string | null | undefined): string {
  if (!state) return ''
  return translateOr(i18n, `state.${state}`, humanize(state))
}

/** Libellé d'un rôle de plateforme. */
export function roleLabel(i18n: I18nLike, role: string | null | undefined): string {
  if (!role) return ''
  return translateOr(i18n, `role.${role}`, humanize(role))
}

/**
 * Libellé d'une action proposée par la machine à états.
 *
 * <p>`action.labelKey` vaut `null` pour tout événement absent d'`EVENT_LABEL_KEYS` —
 * c'est ce `null` qui faisait lever `$t`. On dégrade sur le nom de l'événement, ce que
 * faisait l'implémentation d'origine.
 */
export function eventLabel(
  // Le MINIMUM lu, et TOUT est optionnel. Cette fonction ne regarde que la clé et le nom
  // de l'événement, jamais l'état visé — et elle existe précisément pour ne PAS lever sur
  // une action dégénérée : `labels.spec` lui passe `{}`, `null`, et un événement futur
  // absent du catalogue. C'est le défaut d'origine (`$t(null)` levait « Invalid
  // arguments ») que cette signature doit continuer d'admettre pour pouvoir le nier.
  i18n: I18nLike,
  action: { event?: string; labelKey?: string | null } | null | undefined,
): string {
  if (!action) return ''
  return translateOr(i18n, action.labelKey, humanize(action.event))
}
