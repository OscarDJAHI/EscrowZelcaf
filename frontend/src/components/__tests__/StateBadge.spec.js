import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import StateBadge from '@/components/StateBadge.vue'
import { createEscrowI18n } from '@/i18n'

/**
 * Ce fichier existe parce que son ABSENCE a laissé passer une régression.
 *
 * <p>`StateBadge` est le composant d'état le plus réutilisé du dépôt — `TransactionCard`,
 * `TransactionDetailView`, `RecoveryView` et `SyncFailureNotice` le montent tous. Il
 * n'avait pourtant aucun test. Quand son câblage vers `stateLabel` a été effacé par
 * inadvertance (un `git checkout --` de restauration après une expérience de mutation,
 * qui a emporté du travail non commité du même fichier), la suite est restée VERTE et
 * `state.EXPIRED` serait parti en production sur chaque liste de transactions.
 *
 * <p>La leçon tient en une ligne : un composant sans test peut perdre son correctif sans
 * que rien ne l'annonce.
 */

function render(state, locale = 'fr') {
  return mount(StateBadge, {
    props: { state },
    global: { plugins: [createEscrowI18n(locale)] },
  }).text()
}

describe('StateBadge — libellé traduit, jamais une clé', () => {
  it('traduit un état du catalogue', () => {
    expect(render('FUNDS_LOCKED')).toBe('Fonds bloqués')
    expect(render('FUNDS_LOCKED', 'en')).toBe('Funds locked')
  })

  it("dégrade lisiblement un état HORS catalogue au lieu d'afficher la clé", () => {
    // `EXPIRED` arrive avec l'Epic 5 (AD-19/AD-22) : ce chemin n'a rien d'hypothétique.
    expect(render('EXPIRED')).toBe('EXPIRED')
  })

  it("ne rend JAMAIS le préfixe `state.` à l'utilisateur", () => {
    // L'assertion qui aurait attrapé la régression : elle ne dépend d'aucun état précis.
    for (const state of ['INITIATED', 'EXPIRED', 'WHATEVER_COMES_NEXT']) {
      expect(render(state), `clé brute rendue pour ${state}`).not.toContain('state.')
    }
  })

  it('porte la classe de la famille sémantique de l’état', () => {
    // INITIATED est une ATTENTE (warning) depuis la Story 2.1 — corrigé depuis le gris
    // du POC, qui le rendait indistinguable d'une transaction annulée.
    const wrapper = mount(StateBadge, {
      props: { state: 'INITIATED' },
      global: { plugins: [createEscrowI18n('en')] },
    })
    expect(wrapper.classes().join(' ')).toContain('warning')
  })

  it('retombe sur une classe neutre pour un état inconnu, sans casser le rendu', () => {
    const wrapper = mount(StateBadge, {
      props: { state: 'EXPIRED' },
      global: { plugins: [createEscrowI18n('en')] },
    })
    expect(wrapper.classes().join(' ')).toContain('neutral')
  })
})
