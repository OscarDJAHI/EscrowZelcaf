import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import SyncFailureNotice from '@/components/SyncFailureNotice.vue'
import { createEscrowI18n } from '@/i18n'
import * as escrowApi from '@/api/escrow'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import type { QueueEntry, QueuedActionType } from '@/types/queue'
import type { Pinia } from 'pinia'
import { aTransaction, aUser } from '@/test-support/factories'
import type { VueWrapper } from '@vue/test-utils'
import { aQueueEntry } from '@/test-support/factories'
import type { DisplayTransaction } from '@/stores/escrow'

// Mocked so that "the notice emits no fetch" is an assertion and not a hope: it
// reads the real state out of the store, and the only load action available
// writes `escrow.error`, which `TransactionDetailView` renders *instead of* the
// detail. A chatty notice would blank the page under the user.
vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

const USER = aUser({ id: 42, email: 'alice@corp.example' })

/**
 * Sentinel instants, never `new Date()`: the freshness rule is an ordering of
 * timestamps, and two real `toISOString()` calls land in the same millisecond —
 * a test built on them could not tell a fresh source from a stale one.
 */
const BEFORE_FREEZE = '2026-01-01T00:00:00.000Z'
const FROZE_AT = '2026-01-01T00:05:00.000Z'
const AFTER_FREEZE = '2026-01-01T00:10:00.000Z'
const LONG_AFTER_FREEZE = '2026-01-01T00:20:00.000Z'

/**
 * A frozen entry as `offlineQueue.flush()` really writes one
 * (`offlineQueue.js:198-201`), owned by USER unless told otherwise.
 *
 * `null` — not `undefined` — is how a caller asks for an absent `userId`,
 * `transactionId` or `failure.at`: a default parameter fires on `undefined`, so
 * spelling the absent cases that way would silently hand them the default and
 * test the opposite of what they claim.
 */
interface FrozenOptions {
  type?: QueuedActionType
  /** `null` = entrée sans cible ; distinct d'`undefined`, qui prendrait le défaut. */
  transactionId?: string | null
  /** `null` = entrée sans propriétaire, invisible pour tout le monde. */
  userId?: number | null
  code?: string | null
  status?: number
  message?: string | null
  /** `null` = gel non horodaté (entrée d'avant l'existence du champ). */
  at?: string | null
  files?: Blob[]
}

function frozen({
  type = 'OPEN_DISPUTE',
  transactionId = '7',
  userId = USER.id,
  code = null,
  status = 409,
  message = null,
  at = FROZE_AT,
  files,
}: FrozenOptions = {}): QueueEntry {
  return {
    id: `entry-${type}-${transactionId}`,
    // `timestamp`, `method` et `url` sont ajoutés par la migration TypeScript : ils
    // manquaient à cette fabrique alors que la production les porte TOUJOURS. Le
    // composant ne les lit pas — mais un test qui construit une entrée que `flush()`
    // n'aurait jamais pu produire prouve son comportement sur une forme irréelle.
    timestamp: FROZE_AT,
    method: 'post',
    url: `/api/v1/escrow/${transactionId ?? '7'}/dispute`,
    meta: {
      type,
      ...(transactionId === null ? {} : { transactionId }),
      ...(userId === null ? {} : { userId }),
    },
    ...(files ? { files } : {}),
    frozen: true,
    failure: { code, status, message, at: at === null ? undefined : at },
  }
}

/** Mounts against a real Pinia — `@pinia/testing` is not installed and must not be. */
function mountNotice(pinia: Pinia) {
  return mount(SyncFailureNotice, { global: { plugins: [pinia, createEscrowI18n('en')], stubs: { RouterLink: true } } })
}

/**
 * The links *to a transaction*, which are the only ones the tests below are
 * about. Since Story 4.5 every frozen entry also carries a `/recovery/` link —
 * the acknowledgement being the only way to silence this band — so a bare
 * `find('router-link-stub')` no longer isolates the claim these tests make.
 * Narrowed on the target, not weakened: "the reason forbids opening the
 * transaction" is still asserted exactly, and `SyncFailureNotice.recovery.spec.js`
 * holds the other half.
 */
function transactionLinks(wrapper: VueWrapper) {
  return wrapper
    .findAll('router-link-stub')
    .map((link) => link.attributes('to'))
    .filter((to) => to?.startsWith('/escrow/'))
}

let pinia: Pinia

beforeEach(() => {
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
  // A real session: both halves. `token` and `user` are two independent
  // localStorage keys, so a fixture setting only `user` would leave
  // `isAuthenticated` false and quietly test the logged-out path everywhere.
  useAuthStore().applySession({ token: 'alice-token', user: { ...USER } })
})

describe('SyncFailureNotice — what it renders at all', () => {
  it('renders nothing when no entry is frozen', () => {
    useOfflineQueueStore().queue = [
      // Still waiting, not refused: `OnlineBanner` owns this one.
      aQueueEntry({ id: 'pending', meta: { type: 'SEND_EVENT', transactionId: '7', userId: USER.id } }),
    ]

    const wrapper = mountNotice(pinia)

    // Not an empty container either: nothing at all.
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(wrapper.text()).toBe('')
  })

  it('names the refused action, the label of its code, and the real state', async () => {
    const queue = useOfflineQueueStore()
    const escrow = useEscrowStore()
    queue.queue = [
      frozen({ code: 'DISPUTE_ALREADY_RESOLVED', message: 'Dispute on transaction 7 was already arbitrated' }),
    ]
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('Opening a dispute')
    expect(wrapper.text()).toContain('This dispute had already been arbitrated.')
    // The real state, read from the store and rendered by the shared badge.
    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
    // No load may be issued from here — see the module mock's rationale.
    expect(escrowApi.fetchTransactions).not.toHaveBeenCalled()
    expect(escrowApi.fetchTransactionDetail).not.toHaveBeenCalled()
  })

  it('shows the server message as a secondary detail, never as the reason', () => {
    useOfflineQueueStore().queue = [
      frozen({ code: 'EVIDENCE_INVALID', status: 400, message: 'Uploaded file is empty' }),
    ]

    const wrapper = mountNotice(pinia)

    // Both: the label decides, the message adds the detail the label cannot know.
    expect(wrapper.text()).toContain('One of the attached files was refused')
    expect(wrapper.text()).toContain('Uploaded file is empty')
  })

  it('falls back on the server message for a code it does not know', () => {
    useOfflineQueueStore().queue = [
      frozen({ code: 'SOME_NEW_CODE', status: 400, message: 'Server said no' }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('Server said no')
    // A raw code invented into a label would be worse than the message.
    expect(wrapper.text()).not.toContain('SOME_NEW_CODE')
  })

  it('falls back on a generic refusal when the response carried no envelope', () => {
    // A routing 404 / Spring's /error: `extractFailureReason` yields all nulls.
    useOfflineQueueStore().queue = [frozen({ code: null, status: 404, message: null })]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('The server refused this action.')
    // Never an axios-internal string.
    expect(wrapper.text()).not.toMatch(/status code/i)
  })

  it('lists every frozen entry with its own reason', () => {
    useOfflineQueueStore().queue = [
      frozen({ type: 'OPEN_DISPUTE', transactionId: '7', code: 'DISPUTE_ALREADY_RESOLVED' }),
      frozen({ type: 'SEND_EVENT', transactionId: '8', code: 'ILLEGAL_TRANSITION', status: 409 }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.text()).toContain('This dispute had already been arbitrated.')
    expect(wrapper.text()).toContain('This action is not allowed from the state')
  })

  it('says a refused creation was never created, and offers no link', () => {
    useOfflineQueueStore().queue = [
      frozen({ type: 'CREATE_TRANSACTION', transactionId: null, code: 'VALIDATION_ERROR', status: 400 }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('Creating a transaction')
    expect(wrapper.text()).toContain('This transaction was never created.')
    // There is no id to link to; inventing one is the alternative to saying so.
    expect(transactionLinks(wrapper)).toEqual([])
  })

  it('mentions kept files only on an entry that carries some', () => {
    useOfflineQueueStore().queue = [
      frozen({ type: 'OPEN_DISPUTE', transactionId: '7', code: 'DISPUTE_ALREADY_RESOLVED', files: [{} as Blob, {} as Blob] }),
      frozen({ type: 'SEND_EVENT', transactionId: '8', code: 'ILLEGAL_TRANSITION' }),
    ]

    const wrapper = mountNotice(pinia)
    const [dispute, event] = wrapper.findAll('li')

    // Only OPEN_DISPUTE ever queues binaries (`escrow.js:155`): promising a
    // SEND_EVENT its files are safe would assure the user about data that never
    // existed.
    expect(dispute.text()).toContain('2 attached file(s) are still stored on this device.')
    expect(event.text()).not.toContain('still stored on this device')
  })
})

describe('SyncFailureNotice — the notice belongs to one user', () => {
  it('shows nothing at all once the owner has logged out', () => {
    useAuthStore().logout()
    useOfflineQueueStore().queue = [frozen({ code: 'NOT_A_PARTY', status: 403 })]

    const wrapper = mountNotice(pinia)

    // The queue survives `logout()` (rightly), and this component is mounted on
    // every route including /auth.
    expect(wrapper.text()).toBe('')
  })

  it('never shows one user their predecessor\'s entries after a re-login', () => {
    // The scenario that actually breaks: `isAuthenticated` is true for BOB, so a
    // gate asking "is someone logged in" would render ALICE's entry here —
    // handing BOB her transaction id and the email the server interpolated into
    // the message.
    const auth = useAuthStore()
    useOfflineQueueStore().queue = [
      frozen({
        transactionId: '4242',
        userId: 42, // ALICE
        code: 'NOT_A_PARTY',
        status: 403,
        message: 'alice@corp.example is not a party to transaction 4242',
      }),
    ]

    auth.logout()
    auth.applySession({ token: 'bob-token', user: aUser({ id: 7, email: 'bob@corp.example' }) })
    const wrapper = mountNotice(pinia)

    expect(auth.isAuthenticated).toBe(true) // the gate that was not enough
    expect(wrapper.text()).toBe('')
    expect(wrapper.text()).not.toContain('4242')
    expect(wrapper.text()).not.toContain('alice@corp.example')
  })

  it('shows each user only their own entries when both have frozen ones', () => {
    useOfflineQueueStore().queue = [
      frozen({ transactionId: '4242', userId: 7, code: 'NOT_A_PARTY', status: 403, message: 'bob@corp.example is not a party to transaction 4242' }),
      frozen({ transactionId: '99', userId: USER.id, code: 'TRANSACTION_TERMINAL', status: 409 }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.text()).toContain('This transaction was already closed')
    expect(wrapper.text()).not.toContain('4242')
    expect(wrapper.text()).not.toContain('bob@corp.example')
  })

  it('shows an entry with no owner to nobody', () => {
    // Queued before Story 4.4 shipped. Inventing an owner for it would reopen
    // the leak; Story 4.5's recovery screen is what brings it back.
    useOfflineQueueStore().queue = [
      frozen({ userId: null, code: 'DISPUTE_ALREADY_RESOLVED', message: 'Dispute on transaction 7 was already arbitrated' }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toBe('')
  })

  it('shows nothing when no session is open, whatever user object is left behind', () => {
    // The two gates answer different questions: this one is the token's. Both
    // `logout()` and the 401 interceptor clear `escrow_token` and `escrow_user`
    // together, so this state is not reachable today — but they are independent
    // keys (`auth.js:18-19`), and a sessionless page must show nothing rather
    // than trust a leftover owner.
    const auth = useAuthStore()
    auth.token = null
    auth.user = { ...USER }
    useOfflineQueueStore().queue = [
      frozen({ transactionId: '4242', code: 'NOT_A_PARTY', status: 403, message: 'alice@corp.example is not a party to transaction 4242' }),
    ]

    const wrapper = mountNotice(pinia)

    expect(auth.isAuthenticated).toBe(false)
    expect(wrapper.text()).toBe('')
    expect(wrapper.text()).not.toContain('alice@corp.example')
  })

  it('matches an owner stored as a number against a session id read back as a string', () => {
    // `auth.user` round-trips through localStorage JSON, `meta.userId` through
    // IndexedDB structured clone: the two need not come back the same type.
    // `id` en CHAÎNE, exprès : `auth.user` fait l'aller-retour par le JSON de
    // localStorage et `meta.userId` par le clone structuré d'IndexedDB, si bien que le
    // même identifiant revient nombre d'un côté et chaîne de l'autre. C'est ce que
    // `ownsEntry` compare avec `String(a) === String(b)`, et l'assertion locale ci-dessous
    // est la seule façon d'exprimer ce cas sans relâcher `User.id` partout.
    useAuthStore().user = { ...USER, id: '42' as unknown as number }
    useOfflineQueueStore().queue = [frozen({ userId: 42, code: 'TRANSACTION_TERMINAL' })]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('This transaction was already closed')
  })
})

describe('SyncFailureNotice — a state is badged only once it has seen the rejection', () => {
  it('degrades to a link when the only row predates the freeze, marker or not', () => {
    // The case iteration 1 could not write: for a frozen SEND_EVENT no row of
    // `transactions` ever carries a marker (`escrow.js:104-106` marks only
    // `currentDetail`), so an "unmarked" test would badge this stale row as the
    // server's truth. Freshness, not the marker, is what decides.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [
      frozen({ type: 'SEND_EVENT', transactionId: '7', code: 'ILLEGAL_TRANSITION' }),
    ]
    escrow.transactions = [aTransaction({ id: 7, state: 'SHIPPED' })]
    escrow.transactionsFetchedAt = BEFORE_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
    expect(wrapper.text()).not.toContain('SHIPPED')
    expect(wrapper.find('router-link-stub').attributes('to')).toBe('/escrow/7')
  })

  it('badges the freshly loaded list over a detail loaded before the freeze', () => {
    // On the dashboard `currentDetail` is the source that *cannot* refresh — its
    // `escrow:sync` listener died at unmount and nothing ever clears it — while
    // `transactions` has just been refetched. No fixed precedence: the newest
    // wins, and here that is the list.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'FUNDS_LOCKED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = BEFORE_FREEZE
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
  })

  it('badges the freshly loaded detail over a list loaded before the freeze', () => {
    // The mirror image, and the reason precedence is forbidden in either
    // direction: on the detail view it is `transactions` that goes stale.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'RELEASED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = AFTER_FREEZE
    escrow.transactions = [aTransaction({ id: 7, state: 'DISPUTED' })].map((t) => ({
      ...t,
      _queuedDispute: true,
    }))
    escrow.transactionsFetchedAt = BEFORE_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
  })

  it('badges the newer of two sources that have both seen the rejection — the list here', () => {
    // Both qualify, so filtering cannot settle it and only the ordering can:
    // this is what forbids a fixed precedence rather than merely not needing one.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'DISPUTED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = AFTER_FREEZE
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = LONG_AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
  })

  it('badges the newer of two sources that have both seen the rejection — the detail here', () => {
    // Same setup, reversed clocks: the answer has to follow the timestamps and
    // nothing else.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'RELEASED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = LONG_AFTER_FREEZE
    escrow.transactions = [aTransaction({ id: 7, state: 'DISPUTED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
  })

  it('degrades to a link when the entry carries no failure.at to compare against', () => {
    // Nothing to measure freshness against, so no source can be trusted — not
    // even one loaded a second ago.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED', at: null })]
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
    expect(wrapper.find('router-link-stub').attributes('to')).toBe('/escrow/7')
  })

  it('degrades to a link when a fresh row is dirtied by a later enqueue', () => {
    // Loaded after the freeze, then re-optimistically flipped by a *new* offline
    // dispute: fresh is necessary, clean is necessary too.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.transactions = [aTransaction({ id: 7, state: 'DISPUTED' })].map((t) => ({
      ...t,
      _queuedDispute: true,
    }))
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
    // Libellé RENDU, pas la chaîne machine : depuis que StateBadge traduit l'état
    // (Story 2.1), asserter l'absence de « DISPUTED » serait vide de sens — cette
    // chaîne ne sort plus jamais, et le test passerait même si le badge s'affichait.
    expect(wrapper.text()).not.toContain('Disputed')
    // Named, not merely counted: every frozen entry now also carries a
    // `/recovery/` link, so `find('router-link-stub').exists()` would be true
    // here even if the degradation this test is about had stopped happening.
    expect(transactionLinks(wrapper)).toEqual(['/escrow/7'])
  })

  it('badges nothing when two equally fresh sources contradict each other', () => {
    // Same instant, opposite answers. Nothing here can break the tie, and
    // picking the list "because it is second" would be drawing the state shown
    // to the user out of a hat.
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'REFUNDED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = AFTER_FREEZE
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
    // Named for the same reason as above: the recovery link would satisfy a
    // bare existence check regardless of the tie-breaking rule under test.
    expect(transactionLinks(wrapper)).toEqual(['/escrow/7'])
  })

  it('badges the state when two equally fresh sources agree', () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.currentDetail = { transaction: aTransaction({ id: 7, state: 'RELEASED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = AFTER_FREEZE
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
  })

  it('links to the transaction when the store has never heard of it', () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.transactions = []
    escrow.transactionsFetchedAt = AFTER_FREEZE
    escrow.currentDetail = { transaction: aTransaction({ id: 99, state: 'RELEASED' }), auditLogs: [] }
    escrow.currentDetailFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    // The other transaction's state must not be borrowed for this one.
    expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
    expect(wrapper.find('router-link-stub').attributes('to')).toBe('/escrow/7')
    expect(escrowApi.fetchTransactionDetail).not.toHaveBeenCalled()
  })

  it('matches a route id given as a string against the numeric id the API sends', () => {
    const escrow = useEscrowStore()
    useOfflineQueueStore().queue = [frozen({ transactionId: '7', code: 'DISPUTE_ALREADY_RESOLVED' })]
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
  })
})

describe('SyncFailureNotice — no link where the reason forbids one', () => {
  it.each(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY', 'RESOURCE_NOT_FOUND'])(
    'offers no link on %s — the reason says opening it would fail',
    (code) => {
      // The link would land on `TransactionDetailView`'s error panel, the notice
      // contradicting its own sentence one line above.
      useOfflineQueueStore().queue = [frozen({ code, status: 404 })]

      const wrapper = mountNotice(pinia)

      expect(transactionLinks(wrapper)).toEqual([])
      expect(wrapper.findAll('li')).toHaveLength(1) // the reason is still shown
    },
  )
})

describe('SyncFailureNotice — the fixes review found', () => {
  it('badges a refetch issued in the same millisecond as the freeze', () => {
    // The production path, and the one the sentinel-based tests above cannot
    // reach: `flush()` stamps `failure.at` and dispatches `escrow:sync` one
    // `await` later, so the refetch it triggers is issued within the same
    // millisecond. A strict `>` rejected it and left the notice showing a link
    // instead of the answer, on its very first render.
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED', at: FROZE_AT })]
    const escrow = useEscrowStore()
    escrow.transactions = [aTransaction({ id: 7, state: 'RELEASED' })]
    escrow.transactionsFetchedAt = FROZE_AT

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('Released')
    expect(transactionLinks(wrapper)).toEqual([])
  })

  it.each(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY'])(
    'badges no state on %s even when a trustworthy row still holds one',
    (code) => {
      // The no-link guard used to apply only when no row qualified, so a fresh
      // row badged its state next to a reason denying the transaction is there
      // to have one: "This transaction no longer exists. Current state: …".
      useOfflineQueueStore().queue = [frozen({ code, status: 404 })]
      const escrow = useEscrowStore()
      escrow.transactions = [aTransaction({ id: 7, state: 'FUNDS_LOCKED' })]
      escrow.transactionsFetchedAt = AFTER_FREEZE

      const wrapper = mountNotice(pinia)

      // Idem : le libellé rendu, sinon l'assertion négative devient tautologique.
      expect(wrapper.text()).not.toContain('Funds locked')
      expect(wrapper.text()).not.toContain('Current state')
      expect(transactionLinks(wrapper)).toEqual([])
    },
  )

  it('renders the reason instead of crashing when a row carries no state', () => {
    // The API always sends a state, but this renders above `RouterView`:
    // `StateBadge` would throw on `undefined.replaceAll` and blank every route.
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    const escrow = useEscrowStore()
    // Une ligne SANS état : `trustworthy` doit dégrader vers un lien plutôt que badger.
    escrow.transactions = [{ ...aTransaction({ id: 7 }), state: undefined } as unknown as DisplayTransaction]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('This dispute had already been arbitrated.')
    expect(wrapper.get('router-link-stub').attributes('to')).toBe('/escrow/7')
  })

  it('renders no empty detail line for a whitespace-only server message', () => {
    // `describeFailure` already rejects a blank message (`replayFailure.js:113`)
    // and falls back to the generic label; a raw truthiness test here would then
    // print the same blank string as a styled red line under it.
    useOfflineQueueStore().queue = [frozen({ code: null, status: 400, message: '   ' })]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('The server refused this action.')
    expect(wrapper.findAll('span.text-red-700')).toHaveLength(0)
  })

  it('never tells an update that its transaction was never created', () => {
    // Only a CREATE_TRANSACTION legitimately carries no `transactionId`. A
    // SEND_EVENT without one is malformed, not a creation: it must not be told
    // about a transaction that exists.
    useOfflineQueueStore().queue = [
      frozen({ type: 'SEND_EVENT', transactionId: null, code: 'ILLEGAL_TRANSITION' }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('Updating a transaction')
    expect(wrapper.text()).toContain('This action is not allowed from the state')
    expect(wrapper.text()).not.toContain('never created')
    expect(transactionLinks(wrapper)).toEqual([])
  })

  it('degrades to a link instead of blanking every route when the list is not an array', () => {
    // Same posture as the `state` type guard: the contract says a list, but this
    // renders above `RouterView`, so `.find` on a non-array would take down the
    // whole app from the component whose job is to be the last thing standing.
    useOfflineQueueStore().queue = [frozen({ code: 'DISPUTE_ALREADY_RESOLVED' })]
    const escrow = useEscrowStore()
    // Charge PAGINÉE, donc pas un tableau : le contrat dit une liste, et la vue rend au
    // dessus de `RouterView` — elle doit dégrader, pas blanchir toutes les routes.
    escrow.transactions = { items: [] } as unknown as DisplayTransaction[] // e.g. a paginated payload
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('This dispute had already been arbitrated.')
    expect(wrapper.get('router-link-stub').attributes('to')).toBe('/escrow/7')
  })

  it('never renders a prototype member as the refused action', () => {
    // `meta` round-trips through IndexedDB; a bare `ACTION_LABELS[type]` lookup
    // of `constructor` returns a function, which `||` cannot fall back on.
    // `constructor` : une valeur hostile venue d'IndexedDB, que `describeAction` repousse
    // par `Object.hasOwn`. Assertion LOCALE — élargir `QueuedActionType` affaiblirait le
    // contrat partout pour un seul test.
    useOfflineQueueStore().queue = [
      frozen({ type: 'constructor' as QueuedActionType, code: 'CONFLICT' }),
    ]

    const wrapper = mountNotice(pinia)

    expect(wrapper.text()).toContain('A queued action')
    expect(wrapper.text()).not.toContain('Object')
    expect(wrapper.text()).not.toContain('native code')
  })
})
