import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import AuthView from '@/views/AuthView.vue'
import { useAuthStore } from '@/stores/auth'
import { createEscrowI18n } from '@/i18n'

/**
 * UX-DR32 has two halves and they fail in opposite directions. The guard must
 * *keep* the target, or a deep link opened without a session is silently lost;
 * `AuthView` must *distrust* it, because a value that came off the URL came off
 * whoever wrote the link — and `?redirect=//evil.example/x` is the shape that
 * turns a sign-in screen into an open redirect while passing a naive
 * "starts with /" check.
 */

vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(),
  fetchTransactionDetail: vi.fn(),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

let pinia

beforeEach(() => {
  localStorage.clear()
  pinia = createPinia()
  setActivePinia(pinia)
  vi.clearAllMocks()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('the route guard keeps the target of a deep link', () => {
  it('sends a signed-out visitor to /auth carrying where they were going', async () => {
    // The real guard, driven for real: reading `meta` would prove nothing about
    // what happens on navigation.
    const { default: router } = await import('@/router')

    await router.push('/escrow/42')
    await router.isReady()

    expect(router.currentRoute.value.name).toBe('auth')
    expect(router.currentRoute.value.query.redirect).toBe('/escrow/42')
  })

  it('keeps the query string of the target, which is part of where they were going', async () => {
    const { default: router } = await import('@/router')

    await router.push('/recovery/entry-1?from=notice')

    expect(router.currentRoute.value.query.redirect).toBe('/recovery/entry-1?from=notice')
  })

  it('carries no target for the dashboard, which is where sign-in lands anyway', async () => {
    const { default: router } = await import('@/router')

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('auth')
    expect(router.currentRoute.value.query.redirect).toBeUndefined()
  })
})

describe('AuthView resumes only a target it can vouch for', () => {
  /**
   * Mounts the sign-in screen at `/auth` with the given `redirect`, with a
   * login that succeeds. `login` is stubbed rather than the API mocked: what is
   * under test is where the view goes afterwards, not how it got a session.
   */
  async function submitFrom(redirect) {
    const router = createRouter({
      history: createWebHistory(),
      routes: [
        { path: '/auth', name: 'auth', component: AuthView },
        { path: '/:pathMatch(.*)*', name: 'catch-all', component: { template: '<div />' } },
      ],
    })
    await router.push(redirect === undefined ? '/auth' : `/auth?redirect=${redirect}`)
    await router.isReady()

    const auth = useAuthStore()
    vi.spyOn(auth, 'login').mockResolvedValue(true)
    const replace = vi.spyOn(router, 'replace').mockResolvedValue(undefined)

    // Instance i18n NEUVE par montage, jamais le singleton applicatif : la langue est
    // un état mutable global, et un test qui la basculerait contaminerait les suivants.
    const wrapper = mount(AuthView, {
      global: { plugins: [pinia, router, createEscrowI18n('en')] },
    })
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    return replace
  }

  it('goes to the target when it is a relative path', async () => {
    const replace = await submitFrom('/escrow/42')

    expect(replace).toHaveBeenCalledWith('/escrow/42')
  })

  it('rejects a protocol-relative target — the open redirect that looks like a path', async () => {
    // `'//evil.example/x'.startsWith('/')` is true: this is precisely the case a
    // one-condition guard waves through, and the browser reads it as
    // `https://evil.example/x`.
    const replace = await submitFrom('//evil.example/x')

    expect(replace).toHaveBeenCalledWith('/')
  })

  it('rejects the backslash form, which slips past both of the other conditions', async () => {
    // `'/\\evil.example'` starts with `/` and not with `//`, yet browsers and
    // several URL parsers read `\` as `/`. vue-router happens to resolve it
    // same-origin today, so nothing escapes — but that is a property of the
    // consumer, not of the guard, and a guard whose safety depends on its caller
    // is not a guard.
    const replace = await submitFrom('/\\evil.example/x')

    expect(replace).toHaveBeenCalledWith('/')
  })

  it('rejects an absolute URL', async () => {
    const replace = await submitFrom('https://evil.example/x')

    expect(replace).toHaveBeenCalledWith('/')
  })

  it('rejects a target that only looks relative until a URL parser reads it', async () => {
    // `%09` reaches the guard percent-decoded, as a real TAB: the string starts
    // with `/`, does not start with `//`, and passes every character test the
    // other cases cover. It is the WHATWG rule of discarding tabs and newlines
    // inside a URL that turns it back into `//evil.example/x` further down.
    // Stripping control characters *before* the prefix tests is what makes the
    // string the guard inspects the string a parser will see — and this is the
    // only case that can tell whether that stripping is still there.
    const replace = await submitFrom('/%09/evil.example/x')

    expect(replace).toHaveBeenCalledWith('/')
  })

  it('keeps a legitimate target byte for byte', async () => {
    // The other half of the control-character strip: it must not become a
    // sanitiser. A path that carries no control character comes back unchanged,
    // hyphens and all — a character class written with the literal bytes it
    // matches has been seen to degrade into one that eats `-`, and that failure
    // is invisible to every test above.
    const replace = await submitFrom('/escrow/42-b?tab=evidence')

    expect(replace).toHaveBeenCalledWith('/escrow/42-b?tab=evidence')
  })

  it('goes to the dashboard when there is no target at all', async () => {
    const replace = await submitFrom(undefined)

    expect(replace).toHaveBeenCalledWith('/')
  })

  it('rejects a repeated redirect parameter, which arrives as an array', async () => {
    // Not a string, so not a target: sanitising an array into "the first one"
    // would be choosing on the attacker's behalf.
    const replace = await submitFrom('/escrow/42&redirect=//evil.example')

    expect(replace).toHaveBeenCalledWith('/')
  })
})
