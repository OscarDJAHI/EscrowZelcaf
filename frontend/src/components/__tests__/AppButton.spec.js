import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AppButton from '@/components/AppButton.vue'
import { createEscrowI18n } from '@/i18n'

/**
 * AC1. Le bouton est le composant le plus monté de la plateforme : quatre variantes, une
 * cible tactile minimale, et un libellé porteur de montant pour les confirmations
 * financières — celles où l'utilisateur engage de l'argent et doit lire la somme AVANT de
 * cliquer, pas après.
 */

function render(props = {}, locale = 'en') {
  return mount(AppButton, {
    props: { labelKey: 'common.create', ...props },
    global: { plugins: [createEscrowI18n(locale)] },
  })
}

describe('AppButton — variantes', () => {
  it('rend les quatre variantes avec des classes distinctes', () => {
    const seen = new Set()
    for (const variant of ['primary', 'danger', 'secondary', 'ghost']) {
      seen.add(render({ variant }).find('button').classes().join(' '))
    }
    // Quatre variantes visuellement distinctes : sinon la hiérarchie d'action disparaît.
    expect(seen.size).toBe(4)
  })

  it('applique la variante primary par défaut', () => {
    expect(render().find('button').classes().join(' ')).toContain('bg-primary')
  })

  it("n'emploie aucune couleur hors tokens", () => {
    // Interdit le retour d'une palette Tailwind brute : la couleur vient des tokens de 2.1.
    for (const variant of ['primary', 'danger', 'secondary', 'ghost']) {
      const classes = render({ variant }).find('button').classes().join(' ')
      expect(classes, variant).not.toMatch(/\b(bg|text|border)-(red|green|blue|amber|gray|teal)-\d{2,3}\b/)
    }
  })

  it('ne porte aucune ombre : un bouton n’est pas une surface flottante', () => {
    for (const variant of ['primary', 'danger', 'secondary', 'ghost']) {
      expect(render({ variant }).find('button').classes().join(' '), variant).not.toMatch(
        /\bshadow(-(sm|md|lg|xl))?\b/,
      )
    }
  })
})

describe('AppButton — cible tactile', () => {
  it('garantit une hauteur minimale de 44 px sur TOUTES les variantes', () => {
    // 44 px est la cible tactile minimale de DESIGN.md. Une variante qui l'oublie devient
    // inatteignable au pouce sur mobile, où l'application est utilisée en premier.
    for (const variant of ['primary', 'danger', 'secondary', 'ghost']) {
      expect(render({ variant }).find('button').classes().join(' '), variant).toContain(
        'min-h-[44px]',
      )
    }
  })
})

describe('AppButton — libellé', () => {
  it('traduit la clé fournie, dans les deux langues', () => {
    expect(render({ labelKey: 'common.cancel' }, 'en').text()).toBe('Cancel')
    expect(render({ labelKey: 'common.cancel' }, 'fr').text()).toBe('Annuler')
  })

  it("dégrade lisiblement si la clé n'existe pas, au lieu de l'afficher brute", () => {
    // Même règle que partout depuis la 2.1 : jamais de clé brute rendue à l'utilisateur.
    expect(render({ labelKey: 'common.inexistante' }).text()).not.toContain('common.')
  })
})

describe('AppButton — libellé porteur de montant (confirmations financières)', () => {
  it('affiche le montant dans le libellé', () => {
    const text = render({ labelKey: 'common.create', amount: 12500.5, currency: 'USD' }, 'en').text()
    expect(text).toContain('12,500.50')
  })

  it('formate le montant selon la langue ACTIVE, pas celle du navigateur', () => {
    const en = render({ labelKey: 'common.create', amount: 12500.5, currency: 'USD' }, 'en').text()
    const fr = render({ labelKey: 'common.create', amount: 12500.5, currency: 'USD' }, 'fr').text()
    expect(en).not.toBe(fr)
    expect(fr).not.toContain('12,500.50')
  })

  it('porte les chiffres tabulaires — un montant mal aligné est illisible en colonne', () => {
    const wrapper = render({ labelKey: 'common.create', amount: 1, currency: 'USD' })
    expect(wrapper.html()).toContain('tabular-amount')
  })

  it('sans montant, le libellé reste inchangé', () => {
    expect(render({ labelKey: 'common.cancel' }, 'en').text()).toBe('Cancel')
  })
})

describe('AppButton — états', () => {
  it('désactive réellement le bouton, pas seulement visuellement', () => {
    const button = render({ disabled: true }).find('button')
    expect(button.attributes('disabled')).toBeDefined()
  })

  it('en cours : désactivé ET porteur d’un libellé, jamais un spinner muet', () => {
    // Règle de DESIGN.md : toute attente a une couleur ET un libellé.
    const wrapper = render({ pending: true, pendingLabelKey: 'common.pleaseWait' }, 'en')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toBe('Please wait…')
  })

  it('n’émet pas d’événement quand il est désactivé', async () => {
    const wrapper = render({ disabled: true })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('click')).toBeUndefined()
  })

  it('émet au clic quand il est actif', async () => {
    const wrapper = render()
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('click')).toHaveLength(1)
  })
})
