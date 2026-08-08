import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import SyncFailureNotice from '@/components/SyncFailureNotice.vue'
import { createEscrowI18n } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import { aUser } from '@/test-support/factories'

/**
 * The way *out* of the notice — deliberately a separate file from
 * `SyncFailureNotice.spec.js`, which is the non-regression proof of the
 * `utils/frozenEntry` extraction and must not be entangled with the behaviour
 * change Story 4.5 makes on purpose.
 *
 * What this file exists to catch: iteration 1 put the recovery link inside the
 * `v-if="entry.fileCount > 0"` block. Only OPEN_DISPUTE ever queues binaries
 * (`escrow.js:180`), so a frozen SEND_EVENT / CREATE_TRANSACTION had no path to
 * the recovery screen — and that screen holds the only acknowledgement, hence
 * the only deletion and the only way to silence a permanent `role="alert"` band
 * sitting above every route. No test looked at exactly those entries.
 */

vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

const USER = { id: 42, email: 'alice@corp.example' }
const FROZE_AT = '2026-01-01T00:05:00.000Z'

function frozen({ id, type, transactionId = '7', code = 'DISPUTE_ALREADY_RESOLVED', files } = {}) {
  return {
    id,
    meta: {
      type,
      ...(transactionId === null ? {} : { transactionId }),
      userId: USER.id,
    },
    ...(files ? { files } : {}),
    frozen: true,
    failure: { code, status: 409, message: null, at: FROZE_AT },
  }
}

function mountNotice(pinia) {
  return mount(SyncFailureNotice, { global: { plugins: [pinia, createEscrowI18n('en')], stubs: { RouterLink: true } } })
}

/**
 * Same mount, but with a RouterLink stub that renders its default slot.
 * `stubs: { RouterLink: true }` drops the children entirely, so under it any
 * assertion on a link's *text* passes for the wrong reason — the label being
 * absent looks exactly like the label being right. The `to` attribute stays the
 * evidence everywhere else; this stub exists only where the wording itself is
 * the claim.
 */
function mountWithLinkText(pinia) {
  return mount(SyncFailureNotice, {
    global: {
      plugins: [pinia, createEscrowI18n('en')],
      stubs: { RouterLink: { props: ['to'], template: '<a :href="to"><slot /></a>' } },
    },
  })
}

/** A stubbed RouterLink renders no slot, so the `to` attribute is the only evidence. */
function links(wrapper) {
  return wrapper.findAll('router-link-stub').map((link) => link.attributes('to'))
}

let pinia

beforeEach(() => {
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
  useAuthStore().applySession({ token: 'alice-token', user: { ...USER } })
})

describe('SyncFailureNotice — every frozen entry has a way out', () => {
  it('links an OPEN_DISPUTE carrying files to its recovery screen', () => {
    useOfflineQueueStore().queue = [
      frozen({ id: 'e-dispute', type: 'OPEN_DISPUTE', files: [{}, {}] }),
    ]

    const wrapper = mountNotice(pinia)

    expect(links(wrapper)).toContain('/recovery/e-dispute')
  })

  it('links a frozen SEND_EVENT to its recovery screen although it carries no file', () => {
    // The regression of iteration 1: a SEND_EVENT never has a `files` key
    // (`escrow.js:123-127`), so a link gated on `fileCount > 0` left this entry
    // with `["/escrow/7"]` and nothing else — no acknowledgement, no way to ever
    // dismiss the band.
    useOfflineQueueStore().queue = [frozen({ id: 'e-event', type: 'SEND_EVENT' })]

    const wrapper = mountNotice(pinia)

    expect(links(wrapper)).toContain('/recovery/e-event')
  })

  it('links a frozen CREATE_TRANSACTION to its recovery screen although it carries no file', () => {
    // Worse still: it has no `transactionId` either (`escrow.js:73-82`), so it
    // has no `/escrow/` link to offer as a consolation. Without the recovery
    // link this entry is a red band with no clickable thing on it at all.
    useOfflineQueueStore().queue = [
      frozen({ id: 'e-create', type: 'CREATE_TRANSACTION', transactionId: null, code: 'VALIDATION_ERROR' }),
    ]

    const wrapper = mountNotice(pinia)

    expect(links(wrapper)).toEqual(['/recovery/e-create'])
  })

  it.each(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY', 'RESOURCE_NOT_FOUND'])(
    'still offers recovery on %s, which only forbids the link to the transaction',
    (code) => {
      // `NO_LINK_CODES` governs the transaction link and nothing else: "it no
      // longer exists" / "it was never yours" is no argument against recovering
      // *your own* files, nor against acknowledging the entry.
      useOfflineQueueStore().queue = [frozen({ id: 'e-nolink', type: 'OPEN_DISPUTE', code, files: [{}] })]

      const wrapper = mountNotice(pinia)

      expect(links(wrapper)).toEqual(['/recovery/e-nolink'])
    },
  )

  it('gives every frozen entry its own link when several are listed', () => {
    useOfflineQueueStore().queue = [
      frozen({ id: 'e-a', type: 'OPEN_DISPUTE', transactionId: '7', files: [{}] }),
      frozen({ id: 'e-b', type: 'SEND_EVENT', transactionId: '8', code: 'ILLEGAL_TRANSITION' }),
      frozen({ id: 'e-c', type: 'CREATE_TRANSACTION', transactionId: null, code: 'VALIDATION_ERROR' }),
    ]

    const wrapper = mountNotice(pinia)

    // Not "at least one somewhere": one per entry, or an entry is stranded.
    expect(links(wrapper)).toEqual(
      expect.arrayContaining(['/recovery/e-a', '/recovery/e-b', '/recovery/e-c']),
    )
  })

  it('labels the link for what the entry actually holds', () => {
    useOfflineQueueStore().queue = [
      frozen({ id: 'e-files', type: 'OPEN_DISPUTE', files: [{}] }),
      frozen({ id: 'e-bare', type: 'SEND_EVENT', transactionId: '8', code: 'ILLEGAL_TRANSITION' }),
    ]

    // The one place the slot must actually render — see `mountWithLinkText`.
    const wrapper = mountWithLinkText(pinia)
    const [withFiles, without] = wrapper.findAll('li')

    // `fileCount` decides the wording only — the door itself is unconditional,
    // and both entries below still have their link.
    expect(withFiles.text()).toContain('Recover files')
    expect(without.text()).toContain('Review this entry')
    expect(without.text()).not.toContain('Recover files')
  })

  it('offers no recovery link to a user the entries do not belong to', () => {
    // The link carries the queue id, and the recovery screen is reachable by id:
    // rendering it for the wrong session would hand the next user the door to
    // someone else's binaries.
    const auth = useAuthStore()
    useOfflineQueueStore().queue = [frozen({ id: 'e-alice', type: 'OPEN_DISPUTE', files: [{}] })]

    auth.logout()
    auth.applySession({ token: 'bob-token', user: aUser({ id: 7, email: 'bob@corp.example' }) })
    const wrapper = mountNotice(pinia)

    expect(links(wrapper)).toEqual([])
    expect(wrapper.text()).toBe('')
  })
})
