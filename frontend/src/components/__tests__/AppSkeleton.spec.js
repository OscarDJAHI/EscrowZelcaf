import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AppSkeleton from '@/components/AppSkeleton.vue'
import { createEscrowI18n } from '@/i18n'

/** AC4 — squelettes de chargement. */

function render(props = {}, locale = 'en') {
  return mount(AppSkeleton, { props, global: { plugins: [createEscrowI18n(locale)] } })
}

describe('AppSkeleton', () => {
  it('propose trois formes distinctes, calquées sur les surfaces cibles', () => {
    const shapes = ['line', 'card', 'wallet'].map((variant) =>
      render({ variant }).findAll('div')[1].classes().join(' '),
    )
    expect(new Set(shapes).size).toBe(3)
  })

  it('empile autant d’éléments que demandé', () => {
    expect(render({ count: 3 }).findAll('.animate-pulse')).toHaveLength(3)
  })

  it('rend au moins un élément même sur un compte absurde', () => {
    for (const count of [0, -5]) {
      expect(render({ count }).findAll('.animate-pulse').length).toBeGreaterThanOrEqual(1)
    }
  })

  it('ANNONCE l’attente aux technologies d’assistance', () => {
    // Règle de DESIGN.md : toute attente a une couleur ET un libellé. Une animation
    // muette ne dit rien à qui n'a pas accès à l'image.
    const wrapper = render({}, 'fr')
    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.attributes('aria-label')).toBe('Veuillez patienter…')
  })

  it('traduit son libellé dans les deux langues', () => {
    expect(render({}, 'en').attributes('aria-label')).toBe('Please wait…')
  })

  it('n’emploie aucune couleur hors tokens', () => {
    const classes = render().findAll('div')[1].classes().join(' ')
    expect(classes).toContain('bg-neutral-surface')
    expect(classes).not.toMatch(/\bbg-(gray|slate|zinc)-\d{2,3}\b/)
  })
})
