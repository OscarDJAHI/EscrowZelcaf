/**
 * Client-side mirror of the escrow state machine so the UI can compute,
 * without another round-trip, which events the *current* user is allowed
 * to trigger from the transaction's *current* state.
 */
import type { EscrowEventName, EscrowState, Role, StateToken, Transaction, User } from '@/types/domain'

// `as const` et non `string[]` : sans lui, ces tableaux se typent `string[]` et
// n'imposent plus rien — un état inventé y passerait sans que rien ne bronche, ce qui
// est exactement ce que le miroir est censé empêcher.
export const MAIN_FLOW_STATES = ['INITIATED', 'FUNDS_LOCKED', 'SHIPPED', 'RELEASED'] as const
export const BRANCH_STATES = ['DISPUTED', 'REFUNDED'] as const
export const ALL_STATES: readonly EscrowState[] = [...MAIN_FLOW_STATES, ...BRANCH_STATES]

/**
 * Mapping état → FAMILLE SÉMANTIQUE. Source unique du code couleur du cycle de vie,
 * pour l'app cliente comme pour le back-office (DESIGN.md). Un écran ne redéfinit
 * jamais une couleur d'état : il lit ici.
 *
 * <p>Deux valeurs ont été CORRIGÉES à la Story 2.1, et ce sont des changements de
 * comportement visibles :
 * - `INITIATED` était gris (neutre), donc indistinguable d'une transaction annulée.
 *   C'est une ATTENTE qui appelle une action : `warning`.
 * - `SHIPPED` était ambre, couleur réservée à l'attente. C'est un AVANCEMENT du flux
 *   principal : `info`, comme `FUNDS_LOCKED`.
 *
 * <p>`EXPIRED` rejoindra `neutral` quand l'Epic 5 introduira l'expiration (AD-19/AD-22) ;
 * l'état n'existe pas encore dans cette machine, on ne le devine pas ici.
 */
export const STATE_TOKENS: Record<EscrowState, StateToken> = {
  INITIATED: 'warning',
  FUNDS_LOCKED: 'info',
  SHIPPED: 'info',
  RELEASED: 'success',
  DISPUTED: 'danger',
  REFUNDED: 'neutral',
}

/**
 * Classes utilitaires par famille sémantique.
 *
 * <p>Les chaînes sont écrites en TOUTES LETTRES et non composées (`bg-${token}-surface`) :
 * Tailwind extrait les classes par analyse statique des sources et ne verrait jamais une
 * classe construite à l'exécution — la feuille de style sortirait sans elles et le rendu
 * serait muet, sans la moindre erreur.
 */
/** Les trois surfaces qu'une famille sémantique habille. */
export interface TokenClasses {
  badge: string
  dot: string
  ring: string
}

const TOKEN_CLASSES: Record<StateToken, TokenClasses> = {
  warning: { badge: 'bg-warning-surface text-warning', dot: 'bg-warning', ring: 'ring-warning' },
  info: { badge: 'bg-info-surface text-info', dot: 'bg-info', ring: 'ring-info' },
  success: { badge: 'bg-success-surface text-success', dot: 'bg-success', ring: 'ring-success' },
  danger: { badge: 'bg-danger-surface text-danger', dot: 'bg-danger', ring: 'ring-danger' },
  neutral: {
    badge: 'bg-neutral-surface text-neutral-state',
    dot: 'bg-neutral-state',
    ring: 'ring-neutral-state',
  },
  offline: { badge: 'bg-offline-surface text-offline', dot: 'bg-offline', ring: 'ring-offline' },
}

/** Classes par état, DÉRIVÉES du mapping sémantique — jamais une seconde table à maintenir. */
export const STATE_COLORS = Object.fromEntries(
  Object.entries(STATE_TOKENS).map(([state, token]) => [state, TOKEN_CLASSES[token]]),
) as Record<EscrowState, TokenClasses>

/**
 * Familles sémantiques de la chrome hors-ligne — DEUX états, pas un.
 *
 * <p>Le bandeau distingue « vous êtes hors ligne » (rien ne part) de « en ligne, la file
 * se vide » (tout part, patientez). En remplaçant le ternaire d'origine par une classe
 * unique, la Story 2.1 avait SUPPRIMÉ cette distinction au lieu de la re-pointer vers les
 * tokens — les deux états s'affichaient à l'identique (constat de la 2e passe de revue).
 *
 * <p>DESIGN.md range « hors-ligne / en file de sync » dans une même ligne de son tableau
 * d'états, ce qui vaut pour la couleur d'un ÉLÉMENT en file. Le bandeau, lui, est de la
 * chrome applicative et doit rester lisible d'un coup d'œil : l'attente subie garde la
 * famille `offline`, la synchronisation en cours prend `info`, qui est la famille de
 * l'avancement dans tout le reste de l'application.
 */
export const OFFLINE_CLASSES = TOKEN_CLASSES.offline
export const SYNCING_CLASSES = TOKEN_CLASSES.info

/**
 * Clés i18n des événements — PAS leurs libellés.
 *
 * <p>Ce module est pur : il ne compose aucun texte destiné à l'utilisateur, exactement
 * comme AD-23 l'exige du backend. Il portait auparavant des littéraux anglais qui
 * arrivaient jusqu'aux boutons d'action par la propriété `label` : sous FR, tous les
 * boutons du parcours de transaction restaient en anglais, et aucune garde ne pouvait le
 * voir — la chaîne vivait dans un `.js` et transitait par une liaison (constat de revue).
 */
export const EVENT_LABEL_KEYS: Record<EscrowEventName, string> = {
  PAY_FUNDS: 'event.PAY_FUNDS',
  SHIP_GOODS: 'event.SHIP_GOODS',
  DELIVERY_CONFIRMED: 'event.DELIVERY_CONFIRMED',
  OPEN_DISPUTE: 'event.OPEN_DISPUTE',
  RESOLVE_RELEASE: 'event.RESOLVE_RELEASE',
  RESOLVE_REFUND: 'event.RESOLVE_REFUND',
}

/** Un bouton d'action proposable : l'événement, l'état visé, et sa clé de libellé. */
export interface AllowedEvent {
  event: EscrowEventName
  next: EscrowState
  labelKey: string | null
}

// state -> event -> { next, roles }
// `roles` lists the account roles allowed to trigger the event from the UI.
// PAY_FUNDS / DELIVERY_CONFIRMED can also be triggered by an automated
// payment gateway ("system") server-side; from the PWA only the buyer
// has a manual button for them.
/** Cible d'une transition et rôles autorisés à la déclencher depuis l'interface. */
export interface TransitionDefinition {
  next: EscrowState
  roles: readonly Role[]
}

export const TRANSITIONS: Record<EscrowState, Partial<Record<EscrowEventName, TransitionDefinition>>> = {
  INITIATED: {
    PAY_FUNDS: { next: 'FUNDS_LOCKED', roles: ['BUYER'] },
  },
  FUNDS_LOCKED: {
    SHIP_GOODS: { next: 'SHIPPED', roles: ['SELLER'] },
    OPEN_DISPUTE: { next: 'DISPUTED', roles: ['BUYER', 'SELLER'] },
  },
  SHIPPED: {
    DELIVERY_CONFIRMED: { next: 'RELEASED', roles: ['BUYER'] },
    OPEN_DISPUTE: { next: 'DISPUTED', roles: ['BUYER'] },
  },
  DISPUTED: {
    RESOLVE_RELEASE: { next: 'RELEASED', roles: ['ADMIN'] },
    RESOLVE_REFUND: { next: 'REFUNDED', roles: ['ADMIN'] },
  },
  RELEASED: {},
  REFUNDED: {},
}

export function isTerminal(state: EscrowState | null | undefined): boolean {
  return state === 'RELEASED' || state === 'REFUNDED'
}

/**
 * All events that *could* be triggered from `state` by someone holding `role`,
 * ignoring whether that particular user is a party to the transaction.
 *
 * OPEN_DISPUTE is deliberately excluded here: the backend no longer accepts it
 * on the generic POST /{id}/event endpoint (it 400s — opening a dispute now
 * requires evidence via the composite POST /{id}/dispute). Its entry is kept in
 * TRANSITIONS as the single source of allowed roles (read by `canOpenDispute`),
 * but it must never be offered as a plain event button.
 */
export function getAllowedEvents(
  state: EscrowState | null | undefined,
  role: Role | null | undefined,
): AllowedEvent[] {
  const transitions = (state ? TRANSITIONS[state] : undefined) ?? {}
  return Object.entries(transitions)
    .filter((pair): pair is [EscrowEventName, TransitionDefinition] => pair[0] !== 'OPEN_DISPUTE')
    .filter(([, definition]) => role != null && definition.roles.includes(role))
    .map(([event, definition]) => ({
      event,
      next: definition.next,
      labelKey: EVENT_LABEL_KEYS[event] || null,
    }))
}

/**
 * Whether `user` may open a dispute on `transaction` right now. Reads the
 * allowed roles from the OPEN_DISPUTE transition (single source of truth) and
 * enforces party membership: BUYER must match buyerEmail, SELLER must match
 * sellerEmail. ADMIN never opens disputes (arbitration only).
 */
export function canOpenDispute(
  transaction: Transaction | null | undefined,
  user: User | null | undefined,
): boolean {
  if (!transaction || !user) return false
  const roles = TRANSITIONS[transaction.state]?.OPEN_DISPUTE?.roles
  if (!roles || !roles.includes(user.role)) return false
  if (user.role === 'BUYER') return transaction.buyerEmail === user.email
  if (user.role === 'SELLER') return transaction.sellerEmail === user.email
  return false
}

/**
 * Events the given `user` may trigger on `transaction` right now: matches
 * both the account role AND (for BUYER/SELLER) that the user is actually a
 * party to this specific transaction. ADMIN can always arbitrate disputes.
 */
export function getAllowedEventsForTransaction(
  transaction: Transaction | null | undefined,
  user: User | null | undefined,
): AllowedEvent[] {
  if (!transaction || !user) return []
  const candidates = getAllowedEvents(transaction.state, user.role)

  return candidates.filter(() => {
    if (user.role === 'ADMIN') return true
    if (user.role === 'BUYER') return transaction.buyerEmail === user.email
    if (user.role === 'SELLER') return transaction.sellerEmail === user.email
    return false
  })
}
