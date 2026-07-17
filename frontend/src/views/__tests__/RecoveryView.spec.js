import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { mount, flushPromises } from '@vue/test-utils'
import RecoveryView from '@/views/RecoveryView.vue'
import * as escrowApi from '@/api/escrow'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'

// Mocked so that "this screen emits no fetch" is an assertion and not a hope: a
// frozen entry is typically read offline, and the only load available writes
// `escrow.error`, which `TransactionDetailView` renders *instead of* the page.
vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

const USER = { id: 42, email: 'alice@corp.example' }

/** Sentinels, never `new Date()`: the freshness rule is an ordering of stamps. */
const FROZE_AT = '2026-01-01T00:05:00.000Z'
const AFTER_FREEZE = '2026-01-01T00:10:00.000Z'

const ENTRY_ID = 'entry-1'

function frozen({
  id = ENTRY_ID,
  type = 'OPEN_DISPUTE',
  transactionId = '7',
  userId = USER.id,
  code = 'DISPUTE_ALREADY_RESOLVED',
  message = null,
  files,
} = {}) {
  return {
    id,
    meta: {
      type,
      ...(transactionId === null ? {} : { transactionId }),
      ...(userId === null ? {} : { userId }),
    },
    ...(files ? { files } : {}),
    frozen: true,
    failure: { code, status: 409, message, at: FROZE_AT },
  }
}

/** A queued attachment as structured clone gives it back: name/type/size. */
function file(name = 'photo.jpg', type = 'image/jpeg', size = 2 * 1024 * 1024) {
  return { name, type, size }
}

function mountView(pinia, entryId = ENTRY_ID) {
  return mount(RecoveryView, {
    props: { entryId },
    global: { plugins: [pinia], stubs: { RouterLink: true } },
  })
}

let pinia

beforeEach(() => {
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
  // jsdom implements neither: stubbed here, in the test, so the production code
  // stays free of test-shaped conditionals.
  URL.createObjectURL = vi.fn(() => 'blob:fake-url')
  URL.revokeObjectURL = vi.fn()
  useAuthStore().applySession({ token: 'alice-token', user: { ...USER } })
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('RecoveryView — the nominal recovery', () => {
  it('shows the reason, the real state and the kept file, and fetches nothing', async () => {
    const queue = useOfflineQueueStore()
    const escrow = useEscrowStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    escrow.transactions = [{ id: 7, state: 'RELEASED' }]
    escrow.transactionsFetchedAt = AFTER_FREEZE

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toContain('This dispute had already been arbitrated.')
    expect(wrapper.findComponent({ name: 'StateBadge' }).props('state')).toBe('RELEASED')
    expect(wrapper.text()).toContain('photo.jpg')
    expect(wrapper.text()).toContain('image/jpeg')
    expect(wrapper.text()).toContain('2 MB')
    expect(escrowApi.fetchTransactions).not.toHaveBeenCalled()
    expect(escrowApi.fetchTransactionDetail).not.toHaveBeenCalled()
  })

  it('shows the server message as a secondary detail, never as the reason', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ code: 'EVIDENCE_INVALID', message: 'Uploaded file is empty', files: [file()] })]

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toContain('One of the attached files was refused')
    expect(wrapper.text()).toContain('Uploaded file is empty')
  })

  it('saves the file from memory and releases the object URL, with no HTTP', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    const attachment = file()
    queue.queue = [frozen({ files: [attachment] })]

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.get('li button').trigger('click')

    // The binary is already local: the whole point is that nothing is fetched.
    expect(URL.createObjectURL).toHaveBeenCalledWith(attachment)
    // Leaked, the URL would pin the Blob for the life of the document — and
    // nobody would ever see it.
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:fake-url')
  })

  it('falls back to a filename that feeds both the line and the download', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    // A bare Blob: structured clone kept the bytes, but there was never a name.
    queue.queue = [frozen({ files: [{ type: 'image/png', size: 1024 }] })]
    const anchors = []
    const realCreate = document.createElement.bind(document)
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      const el = realCreate(tag)
      if (tag === 'a') {
        el.click = vi.fn()
        anchors.push(el)
      }
      return el
    })

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).not.toContain('undefined')
    await wrapper.get('li button').trigger('click')

    // One expression feeds both: two would drift, and the user would save
    // `undefined` off a line reading something else.
    expect(anchors).toHaveLength(1)
    expect(anchors[0].download).toBe('attachment-1')
    expect(wrapper.text()).toContain('attachment-1')
  })

  it('links to the transaction rather than inventing a state nothing has seen', async () => {
    const queue = useOfflineQueueStore()
    const escrow = useEscrowStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    // Loaded, but before the freeze: it has not seen the rejection.
    escrow.transactions = [{ id: 7, state: 'DISPUTED' }]
    escrow.transactionsFetchedAt = '2026-01-01T00:00:00.000Z'

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
    const links = wrapper.findAll('router-link-stub').map((l) => l.attributes('to'))
    expect(links).toContain('/escrow/7')
  })

  it.each(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY', 'RESOURCE_NOT_FOUND'])(
    'shows the reason alone on %s — no badge, no transaction link',
    async (code) => {
      const queue = useOfflineQueueStore()
      const escrow = useEscrowStore()
      queue.hydrated = true
      queue.queue = [frozen({ code, files: [file()] })]
      escrow.transactions = [{ id: 7, state: 'FUNDS_LOCKED' }]
      escrow.transactionsFetchedAt = AFTER_FREEZE

      const wrapper = mountView(pinia)
      await flushPromises()

      expect(wrapper.findComponent({ name: 'StateBadge' }).exists()).toBe(false)
      const links = wrapper.findAll('router-link-stub').map((l) => l.attributes('to'))
      expect(links).not.toContain('/escrow/7')
      // The files are still the user's, whatever the server thinks of the
      // transaction: recovery is not what the code forbids.
      expect(wrapper.text()).toContain('photo.jpg')
    },
  )

  it('says a refused creation was never created', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ type: 'CREATE_TRANSACTION', transactionId: null, code: 'VALIDATION_ERROR' })]

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toContain('Creating a transaction')
    expect(wrapper.text()).toContain('This transaction was never created.')
  })
})

describe('RecoveryView — an entry with no binary is still a whole screen', () => {
  // (f) Reached by URL from the notice's link, which every frozen entry now has.
  it('renders the reason and the acknowledgement, and no files section at all', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ type: 'SEND_EVENT', code: 'ILLEGAL_TRANSITION' })]

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toContain('Updating a transaction')
    expect(wrapper.text()).toContain('This action is not allowed from the state')
    // The acknowledgement is the whole reason this entry can reach this screen.
    expect(wrapper.text()).toContain('Acknowledge and delete')
    // No empty "your files" section: a SEND_EVENT never had any, and promising
    // otherwise would be a lie about data that never existed.
    expect(wrapper.text()).not.toContain('kept on this device')
    expect(wrapper.findAll('li')).toHaveLength(0)
    expect(wrapper.text()).toContain('Review this entry')
  })

  it('warns about the entry, not about files it never had', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ type: 'SEND_EVENT', code: 'ILLEGAL_TRANSITION' })]

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.get('.bg-red-600').trigger('click')

    expect(wrapper.text()).toContain('permanently remove this entry')
    expect(wrapper.text()).not.toContain('file(s) above')
  })
})

describe('RecoveryView — what it says while the queue is still unread', () => {
  // (a) The mutation this kills: dropping the `hydrated` guard and rendering the
  // empty screen whenever no entry matches.
  it('shows loading, never "nothing to recover", while un-hydrated', async () => {
    const queue = useOfflineQueueStore()
    // Deep link / reload: `main.js:17` calls `init()` without awaiting it, so
    // this is the state the screen genuinely mounts in.
    queue.hydrated = false
    vi.spyOn(queue, 'init').mockImplementation(async () => {
      queue.hydrated = false // still in flight
    })

    const wrapper = mountView(pinia)

    expect(wrapper.text()).toContain('Loading your queued files…')
    // The entry may well exist — saying it does not would be a guess with the
    // user's last copy of their files riding on it.
    expect(wrapper.text()).not.toContain('Nothing to recover')
  })

  it('calls init() itself when it mounts un-hydrated', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = false
    const init = vi.spyOn(queue, 'init').mockImplementation(async () => {
      queue.hydrated = true
      queue.queue = [frozen({ files: [file()] })]
    })

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(init).toHaveBeenCalled()
    expect(wrapper.text()).toContain('photo.jpg')
  })

  it('does not re-hydrate a queue that is already hydrated', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    const init = vi.spyOn(queue, 'init')

    mountView(pinia)
    await flushPromises()

    expect(init).not.toHaveBeenCalled()
  })
})

describe('RecoveryView — a swallowed storage failure is not a loading state', () => {
  // (d) `init()` logs and returns, leaving `hydrated` false, and nothing ever
  // calls it again: without an explicit failure state this page — which holds
  // the last copy of the files — would spin forever.
  it('shows an explicit error and a retry, not an endless spinner', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = false
    // Exactly what `init()` does on a storage error (`offlineQueue.js:100-106`):
    // it swallows it and returns, resolving cleanly with `hydrated` still false.
    vi.spyOn(queue, 'init').mockResolvedValue(undefined)

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toContain("This device's storage could not be read.")
    expect(wrapper.text()).toContain('Try again')
    expect(wrapper.text()).not.toContain('Loading your queued files…')
    // And it must not claim the files are gone: they are not.
    expect(wrapper.text()).not.toContain('Nothing to recover')
  })

  it('retries init() when the user asks, and recovers when storage comes back', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = false
    const init = vi.spyOn(queue, 'init').mockResolvedValue(undefined)

    const wrapper = mountView(pinia)
    await flushPromises()
    expect(init).toHaveBeenCalledTimes(1)

    // Second attempt succeeds: the store itself allows the retry
    // (`:94 if (this.hydrated) return` then the try).
    init.mockImplementation(async () => {
      queue.hydrated = true
      queue.queue = [frozen({ files: [file()] })]
    })
    await wrapper.get('.bg-red-600').trigger('click')
    await flushPromises()

    expect(init).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('photo.jpg')
    expect(wrapper.text()).not.toContain("This device's storage could not be read.")
  })
})

describe('RecoveryView — an entry that is not yours does not exist', () => {
  /** The exact screen an unknown id produces — the yardstick for every case below. */
  async function unknownIdScreen() {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = []
    const wrapper = mountView(pinia, 'no-such-entry')
    await flushPromises()
    return wrapper.text()
  }

  it('shows an explicit empty screen for an unknown id', async () => {
    expect(await unknownIdScreen()).toContain('Nothing to recover.')
  })

  // (b) The mutation this kills: any message that distinguishes the two.
  it('renders exactly the unknown-id screen for another user\'s entry', async () => {
    const reference = await unknownIdScreen()

    // Fresh pinia: the yardstick above consumed one.
    pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().applySession({ token: 'alice-token', user: { ...USER } })
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    // BOB's entry, reached by ALICE by guessing the id — which is a timestamp
    // plus seven characters, and enumerable.
    queue.queue = [frozen({ userId: 7, message: 'bob@corp.example is not a party to transaction 4242' })]

    const wrapper = mountView(pinia)
    await flushPromises()

    // Character for character. The queue is device-global and this route is
    // reachable by id: any difference at all teaches ALICE that BOB's entry
    // exists — and here it is the *binaries* that a leak would hand over.
    expect(wrapper.text()).toBe(reference)
    expect(wrapper.text()).not.toContain('bob@corp.example')
    expect(wrapper.text()).not.toContain('4242')
  })

  it('renders exactly the unknown-id screen for an entry with no owner', async () => {
    const reference = await unknownIdScreen()

    pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().applySession({ token: 'alice-token', user: { ...USER } })
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    // Queued before the ownership stamp shipped: inventing an owner for it would
    // reopen the leak Story 4.4 closed.
    queue.queue = [frozen({ userId: null, files: [file()] })]

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toBe(reference)
    expect(wrapper.text()).not.toContain('photo.jpg')
  })

  it('shows nothing of an entry to a session that holds no token', async () => {
    const auth = useAuthStore()
    auth.token = null
    auth.user = { ...USER } // a leftover user object, `token` and `user` being independent keys
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(auth.isAuthenticated).toBe(false)
    expect(wrapper.text()).toContain('Nothing to recover.')
    expect(wrapper.text()).not.toContain('photo.jpg')
  })

  it('does not offer a pending entry for recovery', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    // Not frozen: still waiting, and it may yet succeed. Deleting it here would
    // cancel an action the user still expects to land.
    queue.queue = [{ ...frozen({ files: [file()] }), frozen: false }]

    const wrapper = mountView(pinia)
    await flushPromises()

    expect(wrapper.text()).toContain('Nothing to recover.')
  })
})

describe('RecoveryView — nothing is deleted without two deliberate steps', () => {
  it('deletes nothing on the first click, and warns about the loss instead', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    const remove = vi.spyOn(queue, 'removeFromQueue').mockResolvedValue(undefined)

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.get('.bg-red-600').trigger('click')

    // The entry holds the only copy: one stray click must never destroy it.
    expect(remove).not.toHaveBeenCalled()
    // The warning names the loss rather than asking "are you sure?", which
    // informs nobody.
    expect(wrapper.text()).toContain('permanently delete')
    expect(wrapper.text()).toContain('cannot be recovered')
  })

  it('deletes on the second click and returns to the dashboard', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    const remove = vi.spyOn(queue, 'removeFromQueue').mockResolvedValue(undefined)

    const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const wrapper = mount(RecoveryView, {
      props: { entryId: ENTRY_ID },
      global: { plugins: [pinia, router], stubs: { RouterLink: true } },
    })
    await flushPromises()

    await wrapper.get('.bg-red-600').trigger('click')
    await wrapper.get('.bg-red-600').trigger('click')
    await flushPromises()

    expect(remove).toHaveBeenCalledWith(ENTRY_ID)
    expect(push).toHaveBeenCalledWith('/')
  })

  // (e) The mutation this kills: an arming that never disarms.
  it('disarms on Cancel — a later click deletes nothing', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    const remove = vi.spyOn(queue, 'removeFromQueue').mockResolvedValue(undefined)

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.get('.bg-red-600').trigger('click')

    const cancel = wrapper.findAll('button').find((b) => b.text() === 'Cancel')
    expect(cancel).toBeDefined()
    await cancel.trigger('click')

    expect(wrapper.text()).not.toContain('permanently delete')

    // The click that a one-way arming would have honoured ten minutes later.
    await wrapper.get('.bg-red-600').trigger('click')
    await flushPromises()

    expect(remove).not.toHaveBeenCalled()
    // It re-armed instead: the gesture is back to needing two steps.
    expect(wrapper.text()).toContain('permanently delete')
  })
})

describe('RecoveryView — a failed deletion keeps the entry', () => {
  // (c) The mutation this kills: replacing the try/catch with a bare call, or
  // dropping the row optimistically the way `flush()` does.
  it('shows the error, keeps the entry listed, and does not navigate', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    vi.spyOn(queue, 'removeFromQueue').mockRejectedValue(new Error('IndexedDB is unavailable'))

    const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })
    const push = vi.spyOn(router, 'push').mockResolvedValue(undefined)
    const wrapper = mount(RecoveryView, {
      props: { entryId: ENTRY_ID },
      global: { plugins: [pinia, router], stubs: { RouterLink: true } },
    })
    await flushPromises()

    await wrapper.get('.bg-red-600').trigger('click')
    await wrapper.get('.bg-red-600').trigger('click')
    await flushPromises()

    // Shown, not swallowed.
    expect(wrapper.text()).toContain('could not be deleted')
    expect(wrapper.text()).toContain('IndexedDB is unavailable')
    // Still there, still recoverable: `flush()` drops the row from memory when
    // IDB refuses (`:238-245`) — right for a queue that empties itself, and
    // exactly wrong here. The line would vanish while the files stayed on disk.
    expect(wrapper.text()).toContain('photo.jpg')
    expect(wrapper.get('li button').text()).toBe('Download')
    // The user is still on the page that holds their files.
    expect(push).not.toHaveBeenCalled()
  })

  it('disarms after a failure rather than leaving the gesture armed', async () => {
    const queue = useOfflineQueueStore()
    queue.hydrated = true
    queue.queue = [frozen({ files: [file()] })]
    vi.spyOn(queue, 'removeFromQueue').mockRejectedValue(new Error('IndexedDB is unavailable'))

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.get('.bg-red-600').trigger('click')
    await wrapper.get('.bg-red-600').trigger('click')
    await flushPromises()

    // A retry must be as deliberate as the first attempt was.
    expect(wrapper.text()).not.toContain('permanently delete')
  })
})

describe('RecoveryView — the route is protected like every other', () => {
  it('sends a signed-out visitor to /auth rather than to the entry', async () => {
    // The guard driven for real: asserting `meta.public === undefined` would
    // prove nothing about what actually happens on navigation.
    const { default: router } = await import('@/router')
    useAuthStore().logout()

    await router.push('/recovery/entry-1')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('auth')
  })

  it('lets a signed-in user reach it', async () => {
    const { default: router } = await import('@/router')
    useAuthStore().applySession({ token: 'alice-token', user: { ...USER } })

    await router.push('/recovery/entry-1')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('recovery')
    // `props: true`: the id must arrive as a prop, not be dug out of the route.
    expect(router.currentRoute.value.params.entryId).toBe('entry-1')
  })
})
