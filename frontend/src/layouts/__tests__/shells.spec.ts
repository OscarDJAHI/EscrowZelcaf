import { beforeEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { createRouter, createWebHistory } from 'vue-router'
import ClientShell from '@/layouts/ClientShell.vue'
import DesktopShell from '@/layouts/DesktopShell.vue'
import AccessDeniedView from '@/views/AccessDeniedView.vue'
import fr from '@/i18n/fr.json'
import en from '@/i18n/en.json'

/**
 * AC4 — tests de rendu des shells des trois espaces.
 *
 * <p>Le routeur est réel et non simulé : `RouterLink` exige de résoudre ses cibles, et un
 * stub accepterait n'importe quel nom de route. Une entrée de navigation pointant vers une
 * route inexistante passerait alors les tests et casserait à l'écran — c'est précisément
 * le lien mort que l'AC interdit.
 */
const i18n = createI18n({ legacy: false, locale: 'fr', fallbackLocale: 'en', messages: { fr, en } })

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'dashboard', component: { template: '<div />' } },
    { path: '/transactions', name: 'transactions', component: { template: '<div />' } },
    { path: '/wallet', name: 'wallet', component: { template: '<div />' } },
    { path: '/support', name: 'support', component: { template: '<div />' } },
    { path: '/profile', name: 'profile', component: { template: '<div />' } },
    { path: '/arbitration', name: 'arbitration-home', component: { template: '<div />' } },
    { path: '/admin', name: 'admin-home', component: { template: '<div />' } },
    { path: '/auth', name: 'auth', component: { template: '<div />' } },
  ],
})

const mountShell = (component, props = {}) =>
  mount(component, {
    props,
    slots: { default: '<p data-testid="contenu">contenu de la page</p>' },
    global: { plugins: [i18n, router] },
  })

beforeEach(async () => {
  setActivePinia(createPinia())
  router.push('/')
  await router.isReady()
})

describe('ClientShell — espace client, mobile-first', () => {
  it('rend le contenu qu’on lui confie', () => {
    expect(mountShell(ClientShell).find('[data-testid="contenu"]').exists()).toBe(true)
  })

  it('expose les CINQ entrées de navigation, y compris celles à venir', () => {
    // Le compte est une exigence, pas un détail : la navigation dit ce que la plateforme
    // fait. En retirer une parce que son écran n'est pas prêt reviendrait à masquer une
    // fonction promise — l'AC impose au contraire un écran « à venir ».
    const liens = mountShell(ClientShell).findAll('nav a')
    expect(liens).toHaveLength(5)
  })

  it('chaque entrée résout vers une route réelle — aucun lien mort', () => {
    for (const lien of mountShell(ClientShell).findAll('nav a')) {
      const href = lien.attributes('href')
      expect(href, lien.text()).toBeTruthy()
      expect(router.resolve(href).matched.length, href).toBeGreaterThan(0)
    }
  })

  it('n’affiche AUCUN texte en dur : tout passe par le catalogue i18n', () => {
    // `nav.transactions` existe en FR et en EN ; si une clé manquait, vue-i18n rendrait la
    // clé elle-même — c'est ce que cette assertion attrape.
    const texte = mountShell(ClientShell).text()
    expect(texte).not.toMatch(/nav\./)
    expect(texte).toContain(fr.nav.transactions)
  })

  it('annonce la cloche de notifications comme INACTIVE plutôt que muette', () => {
    // Un bouton qui ne réagit pas et ne dit pas pourquoi se lit comme une panne. L'Epic 8
    // livrera le contenu ; d'ici là l'emplacement est réservé et l'état est déclaré.
    const cloche = mountShell(ClientShell).find('[data-testid="notifications-slot"]')
    expect(cloche.exists()).toBe(true)
    expect(cloche.attributes('aria-disabled')).toBe('true')
    expect(cloche.attributes('aria-label')).toBe(fr.nav.notifications)
  })

  it('donne à chaque cible tactile la hauteur minimale de 44 px', () => {
    // DESIGN.md fixe ce seuil ; en dessous, on rate le lien un doigt sur deux sur mobile.
    for (const lien of mountShell(ClientShell).findAll('nav a')) {
      expect(lien.classes().join(' '), lien.text()).toContain('min-h-[44px]')
    }
  })

  it('applique la gouttière tranchée en 2.3 : 16 px, puis 24 px sur grand écran', () => {
    // La règle est écrite dans `style.css` ; sans assertion, elle reste une intention.
    // `lg:p-6` vaut `--space-gutter-desktop` par la correspondance documentée là-bas.
    const classes = mountShell(ClientShell).find('main').classes().join(' ')
    expect(classes).toContain('p-4')
    expect(classes).toContain('lg:p-6')
  })

  it('rend chaque lien focusable au clavier avec un anneau VISIBLE', () => {
    // Une navigation atteignable au clavier mais dont le focus ne se voit pas équivaut,
    // pour qui n'utilise pas la souris, à une navigation inutilisable.
    for (const lien of mountShell(ClientShell).findAll('nav a')) {
      expect(lien.classes().join(' ')).toContain('focus-visible:outline-focus-ring')
    }
  })
})

describe('DesktopShell — arbitrage et back-office', () => {
  it('rend le contenu qu’on lui confie', () => {
    expect(mountShell(DesktopShell, { titleKey: 'nav.disputeQueue', navKeys: ['nav.decisions'] }).find('[data-testid="contenu"]').exists()).toBe(true)
  })

  it('avertit sous 768 px au lieu de laisser croire à un affichage complet', () => {
    // Ces deux consoles sont conçues pour un grand écran. Plutôt que de les tasser en
    // silence sur un téléphone, on le DIT — un opérateur doit savoir qu'il ne voit pas
    // tout, sans quoi il décidera sur une vue tronquée.
    const shell = mountShell(DesktopShell, { titleKey: 'nav.disputeQueue', navKeys: ['nav.decisions'] })
    const banniere = shell.find('[data-testid="desktop-only-warning"]')
    expect(banniere.exists()).toBe(true)
    expect(banniere.classes().join(' ')).toContain('md:hidden')
    expect(banniere.text()).toBe(fr.shell.desktopOnly)
  })
})

describe('AccessDeniedView — la réponse unique (NFR-P9)', () => {
  const mountDenied = () =>
    mount(AccessDeniedView, { global: { plugins: [i18n, router, createPinia()] } })

  it('ne nomme NI l’espace visé, NI le rôle, NI le motif du refus', () => {
    // Le fond de l'exigence : le texte doit être le même pour « cela n'existe pas » et
    // « cela existe mais pas pour vous ». Nommer l'espace refusé, fût-ce pour être utile,
    // rendrait les deux cas distinguables et rouvrirait l'oracle d'énumération.
    const texte = mountDenied().text().toLowerCase()
    for (const fuite of ['admin', 'arbitr', 'back-office', 'rôle', 'role', 'interdit', 'permission']) {
      expect(texte, `le refus ne doit pas contenir « ${fuite} »`).not.toContain(fuite)
    }
  })

  it('offre un retour vers chez soi', () => {
    expect(mountDenied().find('button').exists()).toBe(true)
  })
})
