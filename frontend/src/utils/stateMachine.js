/**
 * Client-side mirror of the escrow state machine so the UI can compute,
 * without another round-trip, which events the *current* user is allowed
 * to trigger from the transaction's *current* state.
 */

export const MAIN_FLOW_STATES = ['INITIATED', 'FUNDS_LOCKED', 'SHIPPED', 'RELEASED']
export const BRANCH_STATES = ['DISPUTED', 'REFUNDED']
export const ALL_STATES = [...MAIN_FLOW_STATES, ...BRANCH_STATES]

// Tailwind utility classes per state, used for badges, stepper dots, and timelines.
export const STATE_COLORS = {
  INITIATED: {
    badge: 'bg-gray-200 text-gray-700',
    dot: 'bg-gray-400',
    ring: 'ring-gray-300',
  },
  FUNDS_LOCKED: {
    badge: 'bg-blue-100 text-blue-700',
    dot: 'bg-blue-500',
    ring: 'ring-blue-300',
  },
  SHIPPED: {
    badge: 'bg-amber-100 text-amber-700',
    dot: 'bg-amber-500',
    ring: 'ring-amber-300',
  },
  RELEASED: {
    badge: 'bg-green-100 text-green-700',
    dot: 'bg-green-500',
    ring: 'ring-green-300',
  },
  DISPUTED: {
    badge: 'bg-red-100 text-red-700',
    dot: 'bg-red-500',
    ring: 'ring-red-300',
  },
  REFUNDED: {
    badge: 'bg-purple-100 text-purple-700',
    dot: 'bg-purple-500',
    ring: 'ring-purple-300',
  },
}

export const EVENT_LABELS = {
  PAY_FUNDS: 'Pay funds',
  SHIP_GOODS: 'Mark as shipped',
  DELIVERY_CONFIRMED: 'Confirm delivery',
  OPEN_DISPUTE: 'Open dispute',
  RESOLVE_RELEASE: 'Resolve: release to seller',
  RESOLVE_REFUND: 'Resolve: refund buyer',
}

// state -> event -> { next, roles }
// `roles` lists the account roles allowed to trigger the event from the UI.
// PAY_FUNDS / DELIVERY_CONFIRMED can also be triggered by an automated
// payment gateway ("system") server-side; from the PWA only the buyer
// has a manual button for them.
export const TRANSITIONS = {
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

export function isTerminal(state) {
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
export function getAllowedEvents(state, role) {
  const transitions = TRANSITIONS[state] || {}
  return Object.entries(transitions)
    .filter(([event]) => event !== 'OPEN_DISPUTE')
    .filter(([, definition]) => definition.roles.includes(role))
    .map(([event, definition]) => ({
      event,
      next: definition.next,
      label: EVENT_LABELS[event] || event,
    }))
}

/**
 * Whether `user` may open a dispute on `transaction` right now. Reads the
 * allowed roles from the OPEN_DISPUTE transition (single source of truth) and
 * enforces party membership: BUYER must match buyerEmail, SELLER must match
 * sellerEmail. ADMIN never opens disputes (arbitration only).
 */
export function canOpenDispute(transaction, user) {
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
export function getAllowedEventsForTransaction(transaction, user) {
  if (!transaction || !user) return []
  const candidates = getAllowedEvents(transaction.state, user.role)

  return candidates.filter(() => {
    if (user.role === 'ADMIN') return true
    if (user.role === 'BUYER') return transaction.buyerEmail === user.email
    if (user.role === 'SELLER') return transaction.sellerEmail === user.email
    return false
  })
}
