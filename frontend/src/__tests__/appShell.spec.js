import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import fr from '@/i18n/fr.json'
import en from '@/i18n/en.json'

/**
 * AC4 — chaque route d'espace s'affiche DANS son shell, sans que la vue ait à le savoir.
 *
 * <p>C'est le test qui manquait quand `ClientShell` a été livré : le composant existait,
 * ses propres tests passaient, et aucune des sept routes de l'espace client ne le rendait.
 * Un shell testé isolément prouve qu'il fonctionne, pas qu'il est branché. Ici on monte
 * l'application entière avec le VRAI routeur et on regarde ce que voit l'utilisateur.
 */
const i18n = createI18n({ legacy: false, locale: 'fr', fallbackLocale: 'en', messages: { fr, en } })

async function open(path, user) {
  vi.resetModules()
  setActivePinia(createPinia())
  const [{ default: router }, { default: App }, { useAuthStore }] = await Promise.all([
    import('@/router'),
    import('@/App.vue'),
    import('@/stores/auth'),
  ])
  if (user) useAuthStore().applySession({ token: 'jeton', user })
  const wrapper = mount(App, { global: { plugins: [i18n, router] } })
  await router.push(path).catch(() => {})
  await router.isReady()
  await wrapper.vm.$nextTick()
  return wrapper
}

const BUYER = { id: 1, email: 'a@corp.example', role: 'BUYER' }
const ADMIN = { id: 2, email: 'b@corp.example', role: 'ADMIN' }

beforeEach(() => setActivePinia(createPinia()))

describe('Espace client', () => {
  // Toutes les routes de l'espace, et pas seulement le tableau de bord : c'est justement
  // sur les écrans secondaires qu'un shell oublié passe inaperçu.
  const ROUTES = ['/', '/transactions', '/wallet', '/support', '/profile']

  it.each(ROUTES)('%s s’affiche avec la navigation de son espace', async (path) => {
    const nav = (await open(path, BUYER)).find('nav')
    expect(nav.exists(), `${path} sans navigation`).toBe(true)
    expect(nav.findAll('a')).toHaveLength(5)
  })

  it('n’affiche qu’UN sélecteur de langue, pas deux', async () => {
    // Le shell porte le sien. Celui d'`App.vue` ne doit apparaître que là où il n'y a
    // pas de shell — sans quoi l'espace client en afficherait deux, superposés.
    //
    // On compte le `role="group"` du composant plutôt qu'un `data-testid` : c'est un
    // attribut que le sélecteur porte pour de vrai. Une première version visait un
    // `data-testid` inexistant — `findAll` renvoyait donc 0, et l'assertion « au plus un »
    // était satisfaite par le vide. Un compte EXACT ne peut pas être creux de cette façon.
    const selecteurs = (await open('/', BUYER)).findAll('[role="group"]')
    expect(selecteurs).toHaveLength(1)
  })
})

describe('Un seul landmark principal par écran', () => {
  // Le shell porte le `<main>` ; une vue qui en déclare un second imbrique deux landmarks,
  // ce qu'un lecteur d'écran ne sait pas interpréter, et empile deux gouttières. Corriger
  // la vue fautive ne suffisait pas — la suivante aurait refait la même chose. Cette garde
  // couvre TOUTES les routes, y compris celles qu'aucune story n'a encore écrites.
  it.each([
    ['/', BUYER],
    ['/transactions', BUYER],
    ['/wallet', BUYER],
    ['/support', BUYER],
    ['/profile', BUYER],
    ['/admin', ADMIN],
    ['/pas-une-route', BUYER],
    ['/auth', null],
    // Écran sans shell : personne ne lui pose de `<main>`, il doit donc porter le sien.
    // Il ne l'a pas fait — la carte s'étalait sur toute la largeur, collée au bord haut,
    // et aucun landmark n'était exposé. Ce cas passe au rouge si le gabarit disparaît.
    ['/verify-email', null],
  ])('%s ne rend qu’UN <main>', async (path, user) => {
    expect((await open(path, user)).findAll('main')).toHaveLength(1)
  })
})

describe('Espace back-office', () => {
  it('s’affiche avec le shell desktop et sa propre navigation', async () => {
    const wrapper = await open('/admin', ADMIN)
    expect(wrapper.find('[data-testid="desktop-only-warning"]').exists()).toBe(true)
    expect(wrapper.text()).toContain(fr.nav.kybQueue)
  })

  it('ne montre PAS la navigation de l’espace client', async () => {
    // Le cloisonnement porte aussi sur le chrome : afficher « Portefeuille » à un
    // opérateur lui promettrait une surface qui n'est pas la sienne.
    expect((await open('/admin', ADMIN)).text()).not.toContain(fr.nav.wallet)
  })
})

describe('Le refus ne porte AUCUN chrome (NFR-P9)', () => {
  it('un espace interdit et une adresse inconnue rendent le même chrome : aucun', async () => {
    // Envelopper le refus dans le shell de l'utilisateur révélerait son espace, et
    // distinguerait « interdit » d'« inexistant » par la seule navigation affichée.
    const interdit = await open('/admin', BUYER)
    const inexistant = await open('/pas-une-route', BUYER)
    expect(interdit.find('nav').exists()).toBe(false)
    expect(inexistant.find('nav').exists()).toBe(false)
    expect(interdit.text()).toContain(fr.access.deniedTitle)
    expect(inexistant.text()).toContain(fr.access.deniedTitle)
  })
})
