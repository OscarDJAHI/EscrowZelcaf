import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import {
  DEFAULT_LOCALE,
  LOCALE_STORAGE_KEY,
  createEscrowI18n,
  readStoredLocale,
} from '@/i18n'
import type { Locale } from '@/i18n'

/**
 * Le sélecteur de langue est le seul chemin par lequel un utilisateur exerce l'AC3.
 * Ces tests montent le composant RÉEL — pas un double — parce que la règle qu'ils
 * gardent est autant visuelle (libellés texte, jamais de drapeaux) que fonctionnelle.
 */

// `Locale` et non le type inféré de `DEFAULT_LOCALE` : ce dernier vaut le littéral
// `'en'`, si bien que passer `'fr'` — ce que fait la moitié des tests — était refusé.
function mountSwitcher(locale: Locale = DEFAULT_LOCALE) {
  // Instance neuve à chaque montage : la langue est un état global mutable, et un test
  // qui la basculerait contaminerait silencieusement les suivants.
  const i18n = createEscrowI18n(locale)
  return { i18n, wrapper: mount(LanguageSwitcher, { global: { plugins: [i18n] } }) }
}

beforeEach(() => localStorage.clear())
afterEach(() => localStorage.clear())

describe('LanguageSwitcher — la bascule EN / FR', () => {
  it('propose exactement les deux langues supportées, en libellés TEXTE', () => {
    const { wrapper } = mountSwitcher()
    const labels = wrapper.findAll('button').map((b) => b.text())
    expect(labels).toEqual(['EN', 'FR'])
  })

  it("n'affiche AUCUN drapeau — DESIGN.md l'interdit explicitement", () => {
    // Un drapeau désigne un pays, pas une langue. La garde porte sur le rendu, parce
    // que c'est là que la règle se viole (un emoji glissé dans un libellé passerait
    // toute revue de code distraite).
    const { wrapper } = mountSwitcher()
    expect(wrapper.text()).not.toMatch(/\p{Extended_Pictographic}|\p{Regional_Indicator}/u)
  })

  it('marque la langue courante comme active pour les technologies d’assistance', () => {
    const { wrapper } = mountSwitcher('fr')
    const pressed = wrapper.findAll('button').filter((b) => b.attributes('aria-pressed') === 'true')
    expect(pressed).toHaveLength(1)
    expect(pressed[0].text()).toBe('FR')
  })

  it('bascule la langue au clic', async () => {
    const { i18n, wrapper } = mountSwitcher('en')
    expect(i18n.global.locale.value).toBe('en')

    await wrapper.findAll('button')[1].trigger('click')

    expect(i18n.global.locale.value).toBe('fr')
  })

  it('persiste le choix, qui est relu au démarrage suivant', async () => {
    const { wrapper } = mountSwitcher('en')

    await wrapper.findAll('button')[1].trigger('click')

    expect(localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('fr')
    // C'est bien la relecture au démarrage qui compte, pas seulement l'écriture.
    expect(readStoredLocale()).toBe('fr')
  })

  it("retombe sur l'anglais si la valeur persistée n'est plus supportée", () => {
    // Une langue retirée du produit, ou une clé trafiquée à la main, ne doit pas
    // laisser l'application dans une langue qu'elle ne sait plus rendre.
    localStorage.setItem(LOCALE_STORAGE_KEY, 'de')
    expect(readStoredLocale()).toBe('en')
  })
})
