import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import TransactionCard from '@/components/TransactionCard.vue'
import { createEscrowI18n } from '@/i18n'

/**
 * AC3, clause « formats de date/nombre localisés ».
 *
 * <p>Le code appelait `Intl.NumberFormat(undefined, …)` et `toLocaleString()` sans
 * argument : ces deux formes suivent la locale du NAVIGATEUR. Basculer l'interface en
 * français ne changeait donc ni un montant ni une date, alors que la sous-tâche
 * correspondante était cochée en revendiquant l'inverse (constat de revue).
 *
 * <p>Ce test compare les deux langues sur le MÊME montant : c'est la seule forme
 * d'assertion qui ne peut pas passer par accident. Asserter une chaîne précise aurait
 * lié le test à la version d'ICU embarquée dans Node, qui change les espaces insécables
 * d'une version à l'autre.
 */

const TRANSACTION = {
  id: 7,
  state: 'FUNDS_LOCKED',
  amount: 12500.5,
  currency: 'USD',
  buyerEmail: 'alice@corp.example',
  sellerEmail: 'bob@corp.example',
}

function renderIn(locale) {
  setActivePinia(createPinia())
  const wrapper = mount(TransactionCard, {
    props: { transaction: TRANSACTION },
    global: { plugins: [createEscrowI18n(locale)], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
  return wrapper.text()
}

describe('Formats de nombre — liés à la langue de l’application', () => {
  it('le montant est RENDU, dans les deux langues', () => {
    // Cette assertion existe parce qu'elle a déjà servi : en insérant un commentaire
    // entre `return` et l'expression, l'insertion automatique de point-virgule avait
    // transformé le calcul en code mort et `formattedAmount` rendait `undefined`. Le
    // build passait, la suite passait — rien n'asservissait la présence du montant.
    for (const locale of ['en', 'fr']) {
      expect(renderIn(locale), `montant absent du rendu en ${locale}`).toMatch(
        /12[\s\u202f,.]?500/,
      )
    }
  })

  it('l’anglais utilise la virgule comme séparateur de milliers', () => {
    expect(renderIn('en')).toContain('12,500.50')
  })

  it('le français n’utilise PAS le format anglo-saxon', () => {
    // Assertion négative volontaire : elle survit à un changement d'espace insécable
    // dans ICU, là où une comparaison littérale casserait à la prochaine version de Node.
    expect(renderIn('fr')).not.toContain('12,500.50')
  })
})
