/**
 * The shared reading of a frozen queue entry: who it belongs to, what was
 * refused, and what the transaction's real state is.
 *
 * Extracted from `SyncFailureNotice.vue`, which is no longer the only surface
 * that has to answer those questions — Story 4.5's recovery screen must answer
 * them *identically*. Story 4.4 established these answers at the price of five
 * review passes and two re-derivations (an optimistic marker is not staleness;
 * `currentDetail` is never cleared so it can never take precedence; the stamp is
 * taken at issue, not at return; `>=` because `escrow:sync` refetches within the
 * millisecond of the freeze). Copied into a second file, one of the two copies
 * drifts — and the drift is invisible, each surface having its own green tests.
 *
 * Pure functions over *raw data*, never over stores. That signature is the point
 * and not an accident: it makes the rules testable without mounting anything,
 * and it structurally denies a consumer the ability to reach for the network to
 * answer a question this module answers from what it was handed.
 *
 * <p>Les types viennent de `types/queue.ts` et non de `stores/offlineQueue.ts` : un
 * `import type` s'efface à la compilation, mais inscrire cet utilitaire pur dans le
 * graphe des stores contredirait la phrase ci-dessus.
 */
import type { QueueEntry, QueueEntryMeta, QueuedActionType } from '@/types/queue'
import type { EscrowState, Transaction, TransactionDetail, User } from '@/types/domain'

/** Ce que la lecture d'un état de transaction rend à l'appelant. */
export type RealState =
  | { kind: 'never-created' }
  | { kind: 'badge'; state: EscrowState }
  | { kind: 'link'; id: string }
  | { kind: 'none' }

/** What was refused, said in the user's terms. Only these three reach the queue. */
const ACTION_LABELS: Record<QueuedActionType, string> = {
  CREATE_TRANSACTION: 'Creating a transaction',
  SEND_EVENT: 'Updating a transaction',
  OPEN_DISPUTE: 'Opening a dispute',
}

/**
 * Codes whose label already says the transaction is gone or was never yours.
 * Offering to open it would walk the user straight into
 * `TransactionDetailView`'s error panel — the notice contradicting itself.
 *
 * Governs the link to the *transaction*, and nothing else. It says nothing
 * against recovering the user's own files or acknowledging the entry: those are
 * theirs whatever the server thinks of the transaction.
 *
 * Module-private, like `ACTION_LABELS`: nothing outside imports either, and
 * exporting them "for immutability" would be theatre — `Object.freeze` on a Set
 * blocks neither `.add()` nor `.delete()`, so privacy is the only real seal
 * available here.
 */
const NO_LINK_CODES: ReadonlySet<string> = new Set([
  'TRANSACTION_NOT_FOUND',
  'NOT_A_PARTY',
  'RESOURCE_NOT_FOUND',
])

/**
 * Only the current user's entries, and only while that user holds a session.
 * The two gates answer different questions and neither replaces the other:
 * `isAuthenticated` reads the token ("is a session open"), the ownership test
 * reads `meta.userId` ("is this theirs"). Ownership alone is what closes the
 * real leak — the queue is device-global and `logout()` does not clear it
 * (rightly: that is the loss Epic 4 exists to prevent), so without it the next
 * user reads the previous one's transaction ids and the server `message` that
 * interpolates their email. The token gate is kept on top because `token` and
 * `user` load from two independent localStorage keys (`auth.js:18-19`): should
 * they ever diverge, a sessionless page must show nothing rather than trust a
 * leftover user object.
 *
 * An entry with no `meta.userId` (queued before this shipped) is shown to
 * nobody: inventing an owner for it would reopen exactly that hole. Nothing is
 * lost either way — the entry outlives the session and comes back to its own
 * owner at their next login.
 *
 * `String(a) === String(b)`: `auth.user` round-trips through localStorage JSON
 * and `meta.userId` through IndexedDB structured clone, so the same id can come
 * back as a number on one side and a string on the other.
 *
 * <p>`user` est celui de la SESSION (`auth.user`), jamais celui de l'entrée. Le type est
 * volontairement plus large qu'`User` : `offlineQueue.idb.ts` appelle avec un
 * `{ id }` fabriqué, n'ayant que l'identifiant sous la main.
 */
export function ownsEntry(
  entry: Pick<QueueEntry, 'meta'> | null | undefined,
  user: { id?: number | string | null } | User | null | undefined,
): boolean {
  const userId = user?.id
  if (userId == null) return false
  const owner = entry?.meta?.userId
  return owner != null && String(owner) === String(userId)
}

/**
 * The label for the refused action.
 *
 * `hasOwn`, like `describeFailure`: `meta` round-trips through IndexedDB, and a
 * bare lookup of `constructor` would render a function into the page — `||`
 * cannot catch it, a function being truthy.
 */
export function describeAction(meta: QueueEntryMeta | null | undefined): string {
  const type = meta?.type
  return type != null && Object.hasOwn(ACTION_LABELS, type) ? ACTION_LABELS[type] : 'A queued action'
}

/** True if a row carries any optimistic marker — matched by shape, so a marker added later is caught too. */
function isOptimistic(row: object | null | undefined): boolean {
  return Object.keys(row || {}).some((key) => key.startsWith('_queued'))
}

/**
 * A source may be badged only if it has provably seen the rejection, i.e. it
 * was issued to the server after the freeze was recorded (`escrow.js` stamps
 * at issue, `offlineQueue.js:198-201` stamps the freeze, same client clock).
 * No fixed precedence between the list and the detail: `escrow:sync` only
 * refreshes *mounted* views, so either one can be the stale one depending on
 * where the user stands. Absence of an optimistic marker is required on top —
 * a fresh row may have been re-dirtied by a later enqueue — but never suffices
 * on its own: `escrow.js:104-106` marks only `currentDetail` for a SEND_EVENT,
 * so no `transactions` row ever carries a marker for one.
 *
 * `>=`, not `>`: the freeze stamps `failure.at` and then dispatches
 * `escrow:sync` (`offlineQueue.js:201,252`), whose listener issues the refetch
 * a single `await` later — the two stamps routinely land in the same
 * millisecond, and a strict `>` would reject the very reload the notice is
 * waiting for, on its first render. Equality is safe: the replay had already
 * been refused when `at` was stamped, so a load issued that same millisecond
 * still went out after the server's verdict.
 */
function trustworthy(
  row: Transaction | null | undefined,
  fetchedAt: string | null | undefined,
  failureAt: string | null | undefined,
): { state: EscrowState; fetchedAt: string } | null {
  if (!row || !fetchedAt || !failureAt) return null
  if (fetchedAt < failureAt) return null
  if (isOptimistic(row)) return null
  // The store is fed by the API, which always sends a state — but the notice
  // renders above `RouterView`, so throwing here would blank every route, not
  // one.
  if (typeof row.state !== 'string' || !row.state) return null
  return { state: row.state, fetchedAt }
}

/**
 * What to show as the real state of a frozen entry's transaction:
 * `{kind:'never-created'}`, `{kind:'badge', state}`, `{kind:'link', id}` or
 * `{kind:'none'}`. Never a guess — an unknown state degrades to a link, and a
 * link the reason forbids degrades to nothing at all.
 *
 * Takes the two sources and their stamps as plain values rather than reading a
 * store: same rule, same answer, on the banner and on the recovery page.
 */
export interface RealStateInput {
  entry: QueueEntry | null | undefined
  /** `unknown` assumé : le contrat dit une liste, la valeur reçue peut n'en être pas une. */
  transactions: unknown
  transactionsFetchedAt: string | null | undefined
  currentDetail: TransactionDetail | null | undefined
  currentDetailFetchedAt: string | null | undefined
}

export function resolveRealState({
  entry,
  transactions,
  transactionsFetchedAt,
  currentDetail,
  currentDetailFetchedAt,
}: RealStateInput): RealState {
  const id = entry?.meta?.transactionId
  // Keyed on the type, not merely on a missing id: a CREATE_TRANSACTION never
  // carries one, but a malformed SEND_EVENT/OPEN_DISPUTE without an id must not
  // be told "this transaction was never created" about a transaction that
  // exists. Unknown target, nothing truthful to say: say nothing.
  if (id == null) {
    return entry?.meta?.type === 'CREATE_TRANSACTION' ? { kind: 'never-created' } : { kind: 'none' }
  }

  // Checked before any badge, not only as a fallback: the reason already says
  // the transaction is gone or was never yours, so whatever a row still claims
  // about it, showing that state would have the surface contradict its own
  // sentence one line down.
  const failureCode = entry?.failure?.code
  if (failureCode != null && NO_LINK_CODES.has(failureCode)) return { kind: 'none' }

  const failureAt = entry?.failure?.at
  const detail = currentDetail?.transaction
  const candidates = [
    trustworthy(
      detail && String(detail.id) === String(id) ? detail : null,
      currentDetailFetchedAt,
      failureAt,
    ),
    trustworthy(
      // Same reason as the `state` type guard above: the API contract says a
      // list, but the notice renders above `RouterView`, so a payload that is
      // not one must degrade to a link rather than blank every route.
      (Array.isArray(transactions) ? (transactions as Transaction[]) : []).find(
        (t) => String(t.id) === String(id),
      ),
      transactionsFetchedAt,
      failureAt,
    ),
  ].filter((candidate): candidate is { state: EscrowState; fetchedAt: string } => candidate !== null)

  const fallback: RealState = { kind: 'link', id: String(id) }

  if (candidates.length === 0) return fallback

  const newest = candidates.reduce((a, b) => (b.fetchedAt > a.fetchedAt ? b : a))
  // Same instant, different answers: nothing here can break the tie, and
  // splitting it on millisecond order would be drawing the displayed state out
  // of a hat. Say nothing rather than pick.
  const contested = candidates.some((c) => c.fetchedAt === newest.fetchedAt && c.state !== newest.state)
  if (contested) return fallback

  return { kind: 'badge', state: newest.state }
}
