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
import type { AllowedEvent } from '@/utils/stateMachine'

/**
 * Le strict nécessaire de vue-i18n, et rien de plus.
 *
 * <p>Déclarer ici les deux fonctions consommées plutôt que d'importer le type complet du
 * composeur prolonge la propriété que l'en-tête revendique : ces fonctions sont
 * appelables hors composant, et testables en leur passant deux `vi.fn()`.
 */
export interface I18nLike {
  t: (key: string) => string
  te: (key: string) => boolean
}

/** Dernier filet : une valeur machine rendue lisible, sans jamais lever. */
export function humanize(code: unknown): string {
  if (code === null || code === undefined) return ''
  return String(code).replaceAll('_', ' ')
}

/** Traduit `key` si elle existe, sinon `fallback`. */
function translateOr({ t, te }: I18nLike, key: string | null | undefined, fallback: string): string {
  return key && te(key) ? t(key) : fallback
}

/**
 * Traduit une clé quelconque, en dégradant sur sa dernière portion si elle est absente.
 *
 * <p>Pour les libellés LIBRES (boutons, titres) où l'appelant fournit la clé entière.
 * Rend `create` plutôt que `common.create` quand la clé manque : le préfixe n'apprend
 * rien à l'utilisateur, et une clé brute affichée est précisément ce que la Story 2.1 a
 * passé trois passes de revue à éliminer.
 */
export function translateOrHumanize(i18n: I18nLike, key: string | null | undefined): string {
  if (!key) return ''
  return translateOr(i18n, key, humanize(String(key).split('.').pop()))
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
export function eventLabel(i18n: I18nLike, action: AllowedEvent | null | undefined): string {
  if (!action) return ''
  return translateOr(i18n, action.labelKey, humanize(action.event))
}
