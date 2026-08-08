import { describe, expect, it } from 'vitest'
import { createEscrowI18n } from '@/i18n'
import { eventLabel, humanize, roleLabel, stateLabel } from '@/i18n/labels'

/**
 * Constats de la 2e passe de revue : la migration vers des clés i18n avait REMPLACÉ une
 * dégradation gracieuse par deux échecs.
 *
 * <p>Avant, `state.replaceAll('_', ' ')` rendait quelque chose de lisible pour N'IMPORTE
 * quelle entrée. Après, `t(`state.${state}`)` rendait la CLÉ BRUTE (`state.EXPIRED`) pour
 * tout état hors catalogue — et `EXPIRED` arrive avec l'Epic 5, le commentaire de
 * `stateMachine.js` le dit lui-même. Pire, `$t(null)` ne dégrade pas : vue-i18n LÈVE
 * « Invalid arguments », et la bibliothèque n'enveloppe pas l'appel dans un `catch`, donc
 * l'exception traverse le rendu de la liste de boutons.
 *
 * <p>Ces libellés vivent donc dans un seul module, avec une règle unique : une clé connue
 * est traduite, tout le reste dégrade lisiblement. Jamais de clé brute, jamais de levée.
 */

const i18n = createEscrowI18n('fr')
const { t, te } = i18n.global
const translate = { t: (k, p) => t(k, p), te: (k) => te(k) }

describe('humanize — le dernier filet', () => {
  it('rend lisible une valeur machine', () => {
    expect(humanize('FUNDS_LOCKED')).toBe('FUNDS LOCKED')
  })

  it('ne lève sur aucune entrée dégénérée', () => {
    for (const input of [null, undefined, '', 0, false]) {
      expect(() => humanize(input)).not.toThrow()
    }
    expect(humanize(null)).toBe('')
  })
})

describe('stateLabel', () => {
  it('traduit un état connu', () => {
    expect(stateLabel(translate, 'FUNDS_LOCKED')).toBe('Fonds bloqués')
  })

  it("dégrade lisiblement un état INCONNU au lieu d'afficher la clé", () => {
    // `EXPIRED` arrive avec l'Epic 5 : ce chemin n'est pas hypothétique.
    expect(stateLabel(translate, 'EXPIRED')).toBe('EXPIRED')
    expect(stateLabel(translate, 'EXPIRED')).not.toContain('state.')
  })

  it("ne rend jamais « state.undefined » quand l'état est absent", () => {
    expect(stateLabel(translate, undefined)).toBe('')
    expect(stateLabel(translate, null)).toBe('')
  })
})

describe('roleLabel', () => {
  it('traduit un rôle connu', () => {
    expect(roleLabel(translate, 'BUYER')).toBe('Acheteur')
  })

  it('dégrade un rôle inconnu au lieu de rendre la clé', () => {
    expect(roleLabel(translate, 'ARBITRATOR')).toBe('ARBITRATOR')
  })
})

describe('eventLabel — le cas qui LEVAIT', () => {
  it('traduit un événement connu', () => {
    expect(eventLabel(translate, { event: 'PAY_FUNDS', labelKey: 'event.PAY_FUNDS' })).toBe(
      'Payer les fonds',
    )
  })

  it('ne lève PAS quand la clé est nulle — elle dégrade sur le nom de l’événement', () => {
    // Reproduction exacte du défaut : `EVENT_LABEL_KEYS[event] || null` puis `$t(null)`.
    const orphan = { event: 'SOME_FUTURE_EVENT', labelKey: null }
    expect(() => eventLabel(translate, orphan)).not.toThrow()
    expect(eventLabel(translate, orphan)).toBe('SOME FUTURE EVENT')
  })

  it('ne lève pas non plus sur une action dégénérée', () => {
    expect(() => eventLabel(translate, {})).not.toThrow()
    expect(() => eventLabel(translate, null)).not.toThrow()
  })
})
