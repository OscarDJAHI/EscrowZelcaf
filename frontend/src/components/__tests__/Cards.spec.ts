import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AppCard from '@/components/AppCard.vue'
import WalletCard from '@/components/WalletCard.vue'
import { createEscrowI18n } from '@/i18n'

/** AC2 — carte standard et carte wallet. */

function renderWallet(props = {}, locale = 'en') {
  return mount(WalletCard, {
    props: { balance: 12500.5, currency: 'USD', ...props },
    global: { plugins: [createEscrowI18n(locale)] },
  })
}

describe('AppCard — la surface de base', () => {
  it('est bordée, sur surface-card, rayon lg, padding 16', () => {
    const classes = mount(AppCard).find('div').classes().join(' ')
    expect(classes).toContain('border')
    expect(classes).toContain('bg-surface-card')
    expect(classes).toContain('rounded-lg')
    expect(classes).toContain('p-4')
  })

  it('ne porte AUCUNE ombre au repos', () => {
    // La règle que la Story 2.1 a relevée et reportée ici : la hiérarchie vient du
    // contraste surface-page / surface-card, jamais d'une élévation.
    expect(mount(AppCard).find('div').classes().join(' ')).not.toMatch(/\bshadow(-\w+)?\b/)
  })

  it('devient un bouton quand elle est interactive — accessible au clavier', () => {
    // Une `div` cliquable n'est ni focusable ni annoncée : le composant change de balise
    // plutôt que d'ajouter un gestionnaire sur un élément muet.
    expect(mount(AppCard, { props: { interactive: true } }).find('button').exists()).toBe(true)
  })

  it('interactive : le bouton porte type="button", jamais le submit par défaut', () => {
    // Un `<button>` sans `type` vaut `submit` en HTML. Placée dans un formulaire, une
    // carte interactive le soumettrait au premier clic — sur un produit qui manipule de
    // l'argent, c'est une action déclenchée par erreur.
    const button = mount(AppCard, { props: { interactive: true } }).find('button')
    expect(button.attributes('type')).toBe('button')
  })

  it('rend son contenu', () => {
    expect(mount(AppCard, { slots: { default: 'contenu' } }).text()).toBe('contenu')
  })
})

describe('WalletCard — présentation pure', () => {
  it('affiche le solde formaté selon la langue active', () => {
    expect(renderWallet({}, 'en').text()).toContain('12,500.50')
    expect(renderWallet({}, 'fr').text()).not.toContain('12,500.50')
  })

  it('rend le solde en chiffres tabulaires et en amount-hero', () => {
    const html = renderWallet().html()
    expect(html).toContain('tabular-amount')
    expect(html).toContain('text-amount-hero')
  })

  it('porte le fond navy inverse', () => {
    expect(renderWallet().find('section').classes().join(' ')).toContain('bg-surface-inverse')
  })

  it('affiche la mention du compte cantonné, dans les deux langues', () => {
    expect(renderWallet({}, 'en').text()).toContain('segregated account')
    expect(renderWallet({}, 'fr').text()).toContain('compte cantonné')
  })

  it('tolère un solde absent sans casser le rendu', () => {
    expect(() => renderWallet({ balance: null })).not.toThrow()
  })

  it('un solde NaN n’affiche PAS « $NaN » à l’utilisateur', () => {
    // Un solde est ce que l'utilisateur croit posséder : y afficher « NaN » est pire
    // que de n'afficher rien. `Intl` ne lève pas sur NaN, le try/catch ne servait à rien.
    expect(renderWallet({ balance: Number.NaN }).text()).not.toContain('NaN')
  })

  it('un horodatage illisible n’affiche PAS « Invalid Date »', () => {
    // `new Date('n-importe-quoi').toLocaleString()` rend « Invalid Date » SANS lever.
    const text = renderWallet({ offline: true, updatedAt: 'pas-une-date' }, 'en').text()
    expect(text).not.toContain('Invalid Date')
  })

  it('sans horodatage exploitable, la mention hors-ligne disparaît proprement', () => {
    const text = renderWallet({ offline: true, updatedAt: 'pas-une-date' }, 'en').text()
    expect(text).not.toContain('Last known value')
  })
})

describe('WalletCard — variante hors ligne', () => {
  it('DÉSACTIVE les deux actions, même si l’appelant les autorise', () => {
    // Proposer un dépôt sur un solde périmé serait mentir sur ce que l'app peut faire.
    const wrapper = renderWallet({ offline: true, canDeposit: true, canWithdraw: true })
    const buttons = wrapper.findAll('button')
    expect(buttons).toHaveLength(2)
    for (const b of buttons) expect(b.attributes('disabled')).toBeDefined()
  })

  it('affiche l’horodatage de la dernière valeur connue', () => {
    const wrapper = renderWallet({ offline: true, updatedAt: '2026-07-28T08:00:00.000Z' }, 'en')
    expect(wrapper.text()).toContain('Last known value')
  })

  it('ne montre PAS cet horodatage en ligne', () => {
    const wrapper = renderWallet({ offline: false, updatedAt: '2026-07-28T08:00:00.000Z' }, 'en')
    expect(wrapper.text()).not.toContain('Last known value')
  })

  it('respecte les autorisations en ligne', () => {
    const wrapper = renderWallet({ canDeposit: true, canWithdraw: false })
    const [deposit, withdraw] = wrapper.findAll('button')
    expect(deposit.attributes('disabled')).toBeUndefined()
    expect(withdraw.attributes('disabled')).toBeDefined()
  })

  it('émet ses intentions sans rien exécuter lui-même', async () => {
    // Aucun store, aucun réseau : le wallet appartient à l'Epic 4 (AD-13).
    const wrapper = renderWallet()
    await wrapper.findAll('button')[0].trigger('click')
    expect(wrapper.emitted('deposit')).toHaveLength(1)
  })
})
