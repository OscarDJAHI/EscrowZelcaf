import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import OnlineBanner from '@/components/OnlineBanner.vue'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import { createEscrowI18n } from '@/i18n'
import { OFFLINE_CLASSES, SYNCING_CLASSES } from '@/utils/stateMachine'
import { aQueueEntry } from '@/test-support/factories'

/**
 * Ce fichier existe parce que la revue a constaté qu'AUCUN test ne montait ce composant,
 * alors qu'il porte une distinction qui avait déjà été perdue une fois.
 *
 * <p>En remplaçant le ternaire d'origine par une classe unique, la Story 2.1 avait fait
 * disparaître l'écart visuel entre « vous êtes hors ligne » (rien ne part) et « en ligne,
 * la file se vide » (tout part, patientez). Rien ne l'a signalé : le bandeau n'avait pas
 * de test, et la couleur n'est asservie par aucune autre suite.
 */

function mountBanner({ isOnline = true, pendingCount = 0, flushing = false }, locale = 'en') {
  setActivePinia(createPinia())
  const queue = useOfflineQueueStore()
  queue.isOnline = isOnline
  queue.queue = Array.from({ length: pendingCount }, (unused: unknown, i: number) =>
    aQueueEntry({ id: `e${i}` }),
  )
  queue.flushing = flushing
  return mount(OnlineBanner, { global: { plugins: [createEscrowI18n(locale)] } })
}

beforeEach(() => setActivePinia(createPinia()))

describe('OnlineBanner — quand le bandeau se montre', () => {
  it('reste invisible en ligne et sans rien en attente', () => {
    expect(mountBanner({ isOnline: true, pendingCount: 0 }).find('div').exists()).toBe(false)
  })

  it('apparaît dès que la connexion est perdue, même sans file', () => {
    expect(mountBanner({ isOnline: false, pendingCount: 0 }).text()).not.toBe('')
  })

  it('apparaît en ligne s’il reste des éléments à synchroniser', () => {
    expect(mountBanner({ isOnline: true, pendingCount: 2 }).text()).not.toBe('')
  })
})

describe('OnlineBanner — la distinction que la Story 2.1 avait perdue', () => {
  it('hors ligne et « synchronisation en cours » ne portent PAS la même couleur', () => {
    // L'assertion qui aurait attrapé la régression. Elle ne nomme aucune classe : elle
    // dit seulement que les deux états restent distinguables à l'œil.
    const offline = mountBanner({ isOnline: false, pendingCount: 1 }).find('div').classes()
    const syncing = mountBanner({ isOnline: true, pendingCount: 1, flushing: true })
      .find('div')
      .classes()

    expect(offline.join(' ')).not.toBe(syncing.join(' '))
  })

  it('l’attente subie porte la famille sémantique « offline »', () => {
    const classes = mountBanner({ isOnline: false, pendingCount: 1 }).find('div').classes()
    expect(classes.join(' ')).toContain(OFFLINE_CLASSES.badge.split(' ')[0])
  })

  it('l’avancement porte la famille « info », jamais une couleur choisie par l’écran', () => {
    const classes = mountBanner({ isOnline: true, pendingCount: 1 }).find('div').classes()
    expect(classes.join(' ')).toContain(SYNCING_CLASSES.badge.split(' ')[0])
  })

  it('ne code en dur aucune couleur : les classes viennent du mapping central', () => {
    // Interdit le retour de `bg-red-100` / `bg-amber-100`, qui vivaient ici avant les
    // tokens et redéfinissaient une couleur d'état au niveau de l'écran (AC2).
    for (const scenario of [{ isOnline: false }, { isOnline: true, pendingCount: 1 }]) {
      const classes = mountBanner({ pendingCount: 1, ...scenario }).find('div').classes().join(' ')
      expect(classes).not.toMatch(/\bbg-(red|amber|blue|green|gray)-\d{2,3}\b/)
    }
  })
})

describe('OnlineBanner — textes traduits et compteurs', () => {
  it('rend le compteur d’éléments en attente', () => {
    expect(mountBanner({ isOnline: false, pendingCount: 3 }).text()).toContain('3')
  })

  it('distingue « en cours de synchronisation » de « en attente »', () => {
    const syncing = mountBanner({ isOnline: true, pendingCount: 2, flushing: true }).text()
    const waiting = mountBanner({ isOnline: true, pendingCount: 2, flushing: false }).text()
    expect(syncing).not.toBe(waiting)
  })

  it('bascule en français sans laisser une seule clé brute', () => {
    for (const scenario of [
      { isOnline: false, pendingCount: 1 },
      { isOnline: true, pendingCount: 1, flushing: true },
      { isOnline: true, pendingCount: 1 },
    ]) {
      const text = mountBanner(scenario, 'fr').text()
      expect(text, `clé brute rendue pour ${JSON.stringify(scenario)}`).not.toMatch(/offline\./)
    }
  })
})
