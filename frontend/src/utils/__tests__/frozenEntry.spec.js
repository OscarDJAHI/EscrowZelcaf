import { describe, expect, it } from 'vitest'
import { describeAction, ownsEntry, resolveRealState } from '@/utils/frozenEntry'

/**
 * The extracted criterion, locked on raw data — no store, no mount, no clock.
 * That is what the extraction bought: Story 4.4 could only reach these rules
 * through a rendered banner, so every rule had to be inferred from a badge.
 *
 * Sentinel instants, never `new Date()`: the freshness rule is an ordering of
 * timestamps, and two real `toISOString()` calls land in the same millisecond —
 * a test built on them could not tell a fresh source from a stale one.
 */
const BEFORE_FREEZE = '2026-01-01T00:00:00.000Z'
const FROZE_AT = '2026-01-01T00:05:00.000Z'
const AFTER_FREEZE = '2026-01-01T00:10:00.000Z'
const LONG_AFTER_FREEZE = '2026-01-01T00:20:00.000Z'

function frozen({ type = 'OPEN_DISPUTE', transactionId = '7', code, at = FROZE_AT } = {}) {
  return {
    meta: { type, ...(transactionId === null ? {} : { transactionId }) },
    failure: { code, at: at === null ? undefined : at },
  }
}

/** Only the sources; the entry is what each test varies. */
function sources({ transactions = [], transactionsFetchedAt = null, currentDetail = null, currentDetailFetchedAt = null } = {}) {
  return { transactions, transactionsFetchedAt, currentDetail, currentDetailFetchedAt }
}

describe('resolveRealState — a state is badged only once it has seen the rejection', () => {
  it('badges a row loaded after the freeze', () => {
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({ transactions: [{ id: 7, state: 'RELEASED' }], transactionsFetchedAt: AFTER_FREEZE }),
    })

    expect(state).toEqual({ kind: 'badge', state: 'RELEASED' })
  })

  it('badges a row loaded in the very millisecond of the freeze', () => {
    // `>=`, not `>`. `flush()` stamps `failure.at` and dispatches `escrow:sync`
    // one `await` later, so the refetch it triggers is issued inside the same
    // millisecond. A strict `>` rejected the very reload the notice waits for,
    // on its first render — the bug Story 4.4 needed four passes to find.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED', at: FROZE_AT }),
      ...sources({ transactions: [{ id: 7, state: 'RELEASED' }], transactionsFetchedAt: FROZE_AT }),
    })

    expect(state).toEqual({ kind: 'badge', state: 'RELEASED' })
  })

  it('degrades to a link when the only row predates the freeze', () => {
    const state = resolveRealState({
      entry: frozen({ type: 'SEND_EVENT', code: 'ILLEGAL_TRANSITION' }),
      ...sources({ transactions: [{ id: 7, state: 'SHIPPED' }], transactionsFetchedAt: BEFORE_FREEZE }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('never badges a row carrying an optimistic marker, however fresh', () => {
    // Fresh is necessary, clean is necessary too: a row loaded after the freeze
    // can have been re-dirtied by a *later* offline enqueue.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        transactions: [{ id: 7, state: 'DISPUTED', _queuedDispute: true }],
        transactionsFetchedAt: AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('catches an optimistic marker by shape, not by name', () => {
    // Matched on the `_queued` prefix, so a marker invented after this shipped
    // is caught without touching this rule.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        transactions: [{ id: 7, state: 'DISPUTED', _queuedSomethingNobodyHasWrittenYet: true }],
        transactionsFetchedAt: AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('lets the most recently loaded of two qualifying sources win — the list here', () => {
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        currentDetail: { transaction: { id: 7, state: 'DISPUTED' } },
        currentDetailFetchedAt: AFTER_FREEZE,
        transactions: [{ id: 7, state: 'RELEASED' }],
        transactionsFetchedAt: LONG_AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'badge', state: 'RELEASED' })
  })

  it('lets the most recently loaded of two qualifying sources win — the detail here', () => {
    // The mirror image, and the reason no fixed precedence is allowed in either
    // direction: whichever view is unmounted is the one that goes stale.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        currentDetail: { transaction: { id: 7, state: 'RELEASED' } },
        currentDetailFetchedAt: LONG_AFTER_FREEZE,
        transactions: [{ id: 7, state: 'DISPUTED' }],
        transactionsFetchedAt: AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'badge', state: 'RELEASED' })
  })

  it('badges nothing when two equally fresh sources contradict each other', () => {
    // Nothing can break the tie, and splitting it on array order would be
    // drawing the state shown to the user out of a hat.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        currentDetail: { transaction: { id: 7, state: 'REFUNDED' } },
        currentDetailFetchedAt: AFTER_FREEZE,
        transactions: [{ id: 7, state: 'RELEASED' }],
        transactionsFetchedAt: AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('badges the state when two equally fresh sources agree', () => {
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        currentDetail: { transaction: { id: 7, state: 'RELEASED' } },
        currentDetailFetchedAt: AFTER_FREEZE,
        transactions: [{ id: 7, state: 'RELEASED' }],
        transactionsFetchedAt: AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'badge', state: 'RELEASED' })
  })

  it('degrades to a link when the entry carries no failure.at to compare against', () => {
    // Nothing to measure freshness against, so no source can be trusted — not
    // even one loaded a second ago.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED', at: null }),
      ...sources({ transactions: [{ id: 7, state: 'RELEASED' }], transactionsFetchedAt: AFTER_FREEZE }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('never borrows another transaction\'s state', () => {
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({
        currentDetail: { transaction: { id: 99, state: 'RELEASED' } },
        currentDetailFetchedAt: AFTER_FREEZE,
        transactions: [],
        transactionsFetchedAt: AFTER_FREEZE,
      }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('matches a queued id given as a string against the numeric id the API sends', () => {
    const state = resolveRealState({
      entry: frozen({ transactionId: '7', code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({ transactions: [{ id: 7, state: 'RELEASED' }], transactionsFetchedAt: AFTER_FREEZE }),
    })

    expect(state).toEqual({ kind: 'badge', state: 'RELEASED' })
  })

  it('degrades to a link when a fresh row carries no usable state', () => {
    // The API always sends a state, but the notice renders above `RouterView`:
    // `StateBadge` would throw on `undefined.replaceAll` and blank every route.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({ transactions: [{ id: 7 }], transactionsFetchedAt: AFTER_FREEZE }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('degrades to a link when a fresh row carries an empty state', () => {
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({ transactions: [{ id: 7, state: '' }], transactionsFetchedAt: AFTER_FREEZE }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })

  it('degrades to a link instead of throwing when the list is not an array', () => {
    // The contract says a list, but a paginated payload must degrade rather than
    // take down the surface whose job is to be the last thing standing.
    const state = resolveRealState({
      entry: frozen({ code: 'DISPUTE_ALREADY_RESOLVED' }),
      ...sources({ transactions: { items: [] }, transactionsFetchedAt: AFTER_FREEZE }),
    })

    expect(state).toEqual({ kind: 'link', id: '7' })
  })
})

describe('resolveRealState — no link where the reason forbids one', () => {
  it.each(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY', 'RESOURCE_NOT_FOUND'])(
    'says nothing at all on %s', (code) => {
      expect(resolveRealState({ entry: frozen({ code }), ...sources() })).toEqual({ kind: 'none' })
    },
  )

  it.each(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY', 'RESOURCE_NOT_FOUND'])(
    'short-circuits before the badge on %s, even when a trustworthy row holds a state',
    (code) => {
      // Checked *before* any badge, not as a fallback: the reason already says
      // the transaction is gone or was never yours, so badging what a row still
      // claims would contradict the sentence one line above — "This transaction
      // no longer exists. Current state: FUNDS_LOCKED".
      const state = resolveRealState({
        entry: frozen({ code }),
        ...sources({ transactions: [{ id: 7, state: 'FUNDS_LOCKED' }], transactionsFetchedAt: AFTER_FREEZE }),
      })

      expect(state).toEqual({ kind: 'none' })
    },
  )
})

describe('resolveRealState — what has no transaction id', () => {
  it('says a CREATE_TRANSACTION was never created', () => {
    const state = resolveRealState({
      entry: frozen({ type: 'CREATE_TRANSACTION', transactionId: null, code: 'VALIDATION_ERROR' }),
      ...sources(),
    })

    expect(state).toEqual({ kind: 'never-created' })
  })

  it('never tells a malformed SEND_EVENT that its transaction was never created', () => {
    // Keyed on the type, not merely on the missing id: only a CREATE_TRANSACTION
    // legitimately carries none. A SEND_EVENT without one is malformed, and its
    // transaction may well exist.
    const state = resolveRealState({
      entry: frozen({ type: 'SEND_EVENT', transactionId: null, code: 'ILLEGAL_TRANSITION' }),
      ...sources(),
    })

    expect(state).toEqual({ kind: 'none' })
  })
})

describe('ownsEntry', () => {
  it('matches an owner stored as a number against a session id read back as a string', () => {
    // `auth.user` round-trips through localStorage JSON, `meta.userId` through
    // IndexedDB structured clone: the two need not come back the same type.
    expect(ownsEntry({ meta: { userId: 42 } }, { id: '42' })).toBe(true)
    expect(ownsEntry({ meta: { userId: '42' } }, { id: 42 })).toBe(true)
  })

  it('refuses another user\'s entry', () => {
    expect(ownsEntry({ meta: { userId: 42 } }, { id: 7 })).toBe(false)
  })

  it('gives an entry with no owner to nobody', () => {
    // Queued before Story 4.4 shipped. Inventing an owner would reopen the leak
    // the ownership gate exists to close.
    expect(ownsEntry({ meta: { type: 'SEND_EVENT' } }, { id: 42 })).toBe(false)
    expect(ownsEntry({ meta: { userId: null } }, { id: 42 })).toBe(false)
  })

  it('gives nothing to a session with no user', () => {
    expect(ownsEntry({ meta: { userId: 42 } }, null)).toBe(false)
    expect(ownsEntry({ meta: { userId: 42 } }, {})).toBe(false)
  })

  it('does not match an absent owner against an absent user', () => {
    // The dangerous coincidence: `String(undefined) === String(undefined)` is
    // true, so a null-blind implementation would hand every ownerless entry to
    // a session that has no user either.
    expect(ownsEntry({ meta: {} }, {})).toBe(false)
    expect(ownsEntry({}, {})).toBe(false)
  })
})

describe('describeAction', () => {
  it.each([
    ['CREATE_TRANSACTION', 'Creating a transaction'],
    ['SEND_EVENT', 'Updating a transaction'],
    ['OPEN_DISPUTE', 'Opening a dispute'],
  ])('names %s in the user\'s terms', (type, label) => {
    expect(describeAction({ type })).toBe(label)
  })

  it('never renders a prototype member as the refused action', () => {
    // `meta` round-trips through IndexedDB; a bare `ACTION_LABELS[type]` lookup
    // of `constructor` returns a function, which `||` cannot fall back on.
    expect(describeAction({ type: 'constructor' })).toBe('A queued action')
    expect(describeAction({ type: 'toString' })).toBe('A queued action')
    expect(describeAction({ type: '__proto__' })).toBe('A queued action')
  })

  it('falls back for an unknown, absent or malformed type', () => {
    expect(describeAction({ type: 'SOMETHING_NEW' })).toBe('A queued action')
    expect(describeAction({})).toBe('A queued action')
    expect(describeAction(null)).toBe('A queued action')
    expect(describeAction(undefined)).toBe('A queued action')
  })
})
