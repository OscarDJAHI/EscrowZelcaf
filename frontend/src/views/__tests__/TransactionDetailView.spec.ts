import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import { flushPromises, mount } from '@vue/test-utils'
import TransactionDetailView from '@/views/TransactionDetailView.vue'
import { useAuthStore } from '@/stores/auth'
import { createEscrowI18n } from '@/i18n'
import { aUser } from '@/test-support/factories'

/**
 * Ce fichier existe parce que la revue a constaté qu'AUCUN test ne montait cette vue,
 * alors qu'elle porte deux correctifs revendiqués par la Story 2.1 et vérifiés jusqu'ici
 * « à l'œil » : le formatage des montants suivant la langue de l'application (AC3), et le
 * libellé des boutons d'action, dont une version intermédiaire FAISAIT LEVER le rendu.
 *
 * <p>Les appels réseau sont mockés : cette vue les déclenche depuis `onMounted`, et ce
 * n'est pas eux qu'on teste ici.
 */

vi.mock('@/api/escrow', () => ({
  fetchTransactions: vi.fn(() => Promise.resolve([])),
  // Renvoie le détail voulu par le test : la vue recharge depuis `onMounted`, donc un
  // état seedé directement dans le store serait ÉCRASÉ avant le premier rendu.
  fetchTransactionDetail: vi.fn(() => Promise.resolve(detailFixture)),
  createTransaction: vi.fn(),
  sendTransactionEvent: vi.fn(),
  openDispute: vi.fn(),
}))

vi.mock('@/api/evidence', () => ({
  listEvidence: vi.fn(() => Promise.resolve([])),
  uploadEvidence: vi.fn(),
  withdrawEvidence: vi.fn(),
  downloadEvidence: vi.fn(),
}))

/** Détail servi par le mock ; réassigné par `renderDetail` avant chaque montage. */
let detailFixture = null

const BUYER = aUser({ id: 1, email: 'alice@corp.example', role: 'BUYER' })

const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: { template: '<div />' } }] })

async function renderDetail({ state = 'SHIPPED', locale = 'en', amount = 12500.5 } = {}) {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = BUYER
  auth.token = 'token'
  detailFixture = {
    transaction: {
      id: 7,
      state,
      amount,
      currency: 'USD',
      buyerEmail: BUYER.email,
      sellerEmail: 'bob@corp.example',
    },
    auditLogs: [],
  }

  const wrapper = mount(TransactionDetailView, {
    props: { id: '7' },
    global: {
      plugins: [router, createEscrowI18n(locale)],
      stubs: { EvidenceDeposit: true, EvidenceList: true, OpenDisputeForm: true },
    },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => setActivePinia(createPinia()))

describe('TransactionDetailView — AC3, le montant suit la langue de l’application', () => {
  it('rend le montant, dans les deux langues', () => {
    // Cette assertion a déjà servi : un commentaire glissé entre `return` et l'expression
    // avait déclenché l'insertion automatique de point-virgule, et `formattedAmount`
    // rendait `undefined`. Build vert, suite verte.
    return Promise.all(
      ['en', 'fr'].map(async (locale) => {
        const text = (await renderDetail({ locale })).text()
        expect(text, `montant absent en ${locale}`).toMatch(/12[\s,.]?500/)
      }),
    )
  })

  it('ne formate PAS identiquement en anglais et en français', async () => {
    const en = (await renderDetail({ locale: 'en' })).text()
    const fr = (await renderDetail({ locale: 'fr' })).text()
    // en-US : « $12,500.50 » — fr-FR : « 12 500,50 $US ».
    expect(en).toContain('12,500.50')
    expect(fr).not.toContain('12,500.50')
  })
})

describe('TransactionDetailView — les boutons d’action', () => {
  it('affiche un libellé traduit, jamais une clé ni le code de l’événement', async () => {
    // En SHIPPED, l'acheteur peut confirmer la livraison.
    const wrapper = await renderDetail({ state: 'SHIPPED', locale: 'fr' })
    const text = wrapper.text()

    expect(text).toContain('Confirmer la livraison')
    expect(text).not.toContain('event.')
    expect(text).not.toContain('DELIVERY_CONFIRMED')
  })

  it('traduit aussi en anglais', async () => {
    expect((await renderDetail({ state: 'SHIPPED', locale: 'en' })).text()).toContain(
      'Confirm delivery',
    )
  })
})

describe('TransactionDetailView — aucune clé brute nulle part', () => {
  it('ne laisse échapper aucun préfixe de clé i18n, quel que soit l’état', async () => {
    // Garde large et volontairement peu spécifique : elle survivra aux remaniements de
    // gabarit et attrapera n'importe quelle interpolation de clé oubliée.
    // Rendus indépendants : rien n'impose de les enchaîner, et `no-await-in-loop` est
    // active dans ce dépôt précisément pour qu'on ne sérialise pas par distraction.
    await Promise.all(
      ['INITIATED', 'FUNDS_LOCKED', 'SHIPPED', 'DISPUTED', 'RELEASED'].map(async (state) => {
        const text = (await renderDetail({ state, locale: 'fr' })).text()
        expect(text, `clé brute rendue en ${state}`).not.toMatch(
          /\b(transaction|common|event|state|role)\.[a-zA-Z]/,
        )
      }),
    )
  })
})
