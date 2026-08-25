import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import fr from '@/i18n/fr.json'
import en from '@/i18n/en.json'
import { aUser } from '@/test-support/factories'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import type { User } from '@/types/domain'

/**
 * AC4 — chaque route d'espace s'affiche DANS son shell, sans que la vue ait à le savoir.
 *
 * <p>C'est le test qui manquait quand `ClientShell` a été livré : le composant existait,
 * ses propres tests passaient, et aucune des sept routes de l'espace client ne le rendait.
 * Un shell testé isolément prouve qu'il fonctionne, pas qu'il est branché. Ici on monte
 * l'application entière avec le VRAI routeur et on regarde ce que voit l'utilisateur.
 */
const i18n = createI18n({ legacy: false, locale: 'fr', fallbackLocale: 'en', messages: { fr, en } })

// La révocation est le SEUL point du parcours de déconnexion qui sorte du processus. Elle
// est doublée ici pour que le test n'aille pas vers le réseau ; tout le reste — routeur,
// stores, IndexedDB, purge — est réel, parce que c'est justement le branchement de ces
// pièces les unes aux autres que ce fichier existe pour prouver.
vi.mock('@/api/auth', () => ({
  loginUser: vi.fn(),
  registerUser: vi.fn(),
  logoutUser: vi.fn(() => Promise.resolve()),
  verifyEmail: vi.fn(),
  resendVerification: vi.fn(),
}))

const LOGOUT = '[data-testid="logout-button"]'

/**
 * Le jeton tel qu'il est SUR L'APPAREIL, les deux substrats confondus.
 *
 * <p>Les deux sont interrogés parce que la Story 2.7 les rend commutables : lire le seul
 * `sessionStorage` laisserait passer un jeton oublié en `localStorage` par « rester
 * connecté », c'est-à-dire précisément le résidu que la purge doit emporter. La clé vient
 * de la production (`TOKEN_STORAGE_KEY`) et n'est pas recopiée — un littéral survivrait à
 * un renommage en laissant l'assertion verte sur une clé qui n'existe plus.
 */
function readToken(): string | null {
  return (
    sessionStorage.getItem(TOKEN_STORAGE_KEY) ?? localStorage.getItem(TOKEN_STORAGE_KEY) ?? null
  )
}

/**
 * ⚠️ CE HARNAIS ÉTAIT CASSÉ, ET IL RENDAIT DES TESTS VERTS SUR LE MAUVAIS ÉCRAN (T9).
 *
 * <p><b>Le défaut : le jeton survivait dans le stockage.</b> Le store est recréé à chaque
 * appel, mais il relit l'appareil à sa construction (`token: readCredential(...)`,
 * `stores/auth.ts:85`). Un `open(path, null)` consécutif à un `open(path, user)` arrivait
 * donc AUTHENTIFIÉ — et la garde du routeur, voyant une session valide, renvoyait `/auth`
 * vers l'accueil de l'espace correspondant. `open('/auth', null)` appelé après
 * `open('/admin', ADMIN)` rendait ainsi `/admin` en tant qu'ADMIN. Vider les substrats dans
 * le `beforeEach` ne suffit pas : un même test appelle `open()` deux fois. La remise à zéro
 * appartient à `open()` lui-même.
 *
 * <p>`applySession` est en outre ATTENDUE. Sa promesse flottante — `beginSession` touche
 * IndexedDB — pouvait auparavant se résoudre pendant le test suivant, sur une autre pinia
 * que la sienne.
 *
 * <p><b>Ce que la réparation a révélé.</b> Le cas `['/auth', null]` du test des landmarks
 * mesurait le tableau de bord client, et il passait : son oracle — « exactement un `<main>` »
 * — est vrai des deux côtés de la confusion. Réparé, il est passé au rouge, parce que
 * `AuthView` n'avait tout simplement PAS de `<main>` (corrigé dans la vue). Un test vert
 * sur un écran qu'il ne visait pas, pendant deux stories ; c'est le premier test capable de
 * distinguer les deux chemins — la présence du bouton de déconnexion — qui l'a mis au jour.
 *
 * <p><b>Ce que la mutation a corrigé dans MON PROPRE diagnostic.</b> J'avais d'abord
 * incriminé AUSSI la persistance de l'URL de jsdom d'un `open()` à l'autre, et ajouté un
 * `history.replaceState` pour la neutraliser. La mutation a dit non : remettre la fuite
 * d'URL ne faisait rougir aucun test, parce que le `push()` explicite l'emporte de toute
 * façon sur la position initiale de l'historique. La route détournée venait de la GARDE
 * réagissant au jeton fuité, jamais de l'URL. Le `replaceState` a donc été retiré — une
 * garde qu'aucune mutation ne falsifie est une preuve creuse en attente d'être citée, et
 * cette story en a déjà démasqué trois.
 */
async function open(path: string, user: User | null) {
  vi.resetModules()
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
  const [{ default: router }, { default: App }, { useAuthStore }] = await Promise.all([
    import('@/router'),
    import('@/App.vue'),
    import('@/stores/auth'),
  ])
  if (user) await useAuthStore().applySession({ token: 'jeton', user })
  const wrapper = mount(App, { global: { plugins: [i18n, router] } })
  await router.push(path).catch(() => {})
  await router.isReady()
  await wrapper.vm.$nextTick()
  return wrapper
}

const BUYER = aUser({ id: 1, email: 'a@corp.example', role: 'BUYER' })
const ADMIN = aUser({ id: 2, email: 'b@corp.example', role: 'ADMIN' })

beforeEach(() => {
  // ⚠️ ISOLATION — DÉFAUT DU HARNAIS TROUVÉ EN T9, ANTÉRIEUR À CETTE STORY.
  //
  // Les deux substrats sont vidés entre les tests. Sans cela, l'`applySession` d'un test
  // précédent laisse le jeton SUR L'APPAREIL, et le store recréé par le `createPinia()`
  // ci-dessous le RELIT à sa construction (`token: readCredential(TOKEN_STORAGE_KEY)`,
  // `stores/auth.ts:85`). `open(path, null)` — censé représenter un visiteur anonyme —
  // arrivait donc authentifié, et la garde du routeur le renvoyait vers son espace.
  //
  // Les deux cas `null` de ce fichier ne testaient par conséquent PAS la route demandée :
  // `['/auth', null]` et `['/verify-email', null]` mesuraient le tableau de bord client.
  // Ils passaient parce que leur oracle — « exactement un `<main>` » — est vrai des deux
  // côtés de la confusion. Un test vert sur le mauvais écran ; c'est le premier test qui
  // distingue vraiment les deux chemins qui l'a révélé.
  localStorage.clear()
  sessionStorage.clear()
  setActivePinia(createPinia())
})

/**
 * LE HARNAIS SE PROUVE LUI-MÊME (T9).
 *
 * <p>`open()` est load-bearing pour les 26 tests de ce fichier : s'il ouvre une autre route
 * que celle qu'on lui demande, tous mesurent le mauvais écran et le disent en vert. C'est
 * arrivé, et pendant deux stories.
 *
 * <p>Ce test existe parce que la passe de mutation a montré que la réparation ne l'était
 * pas : remettre la fuite d'URL ne faisait rougir AUCUN des 26 tests, le vidage du stockage
 * suffisant à masquer ses effets visibles. Une correction que rien ne détecte est une
 * correction en sursis — la prochaine simplification du helper la retirera sans bruit.
 */
describe('Le harnais ouvre la route demandée, et non celle d’avant', () => {
  it('un utilisateur ne survit pas à l’ouverture suivante', async () => {
    // Le store est recréé à chaque `open()`, mais il relit l'appareil à sa construction :
    // sans la purge dans `open()`, ce visiteur anonyme arrive authentifié, et la garde du
    // routeur le renvoie vers l'espace de l'utilisateur précédent.
    await open('/', BUYER)
    await open('/auth', null)
    const { useAuthStore } = await import('@/stores/auth')

    // Appariée : la première ouverture avait bien posé une session, sinon ce test serait
    // vrai par le vide et ne garderait rien du tout.
    expect(useAuthStore().isAuthenticated).toBe(false)
  })

  it('l’ouverture précédente avait POURTANT posé une vraie session', async () => {
    // La moitié positive du test ci-dessus, séparée pour qu'aucune des deux ne puisse être
    // satisfaite par un harnais qui n'authentifierait jamais personne.
    await open('/', BUYER)
    const { useAuthStore } = await import('@/stores/auth')
    expect(useAuthStore().isAuthenticated).toBe(true)
  })
})

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

/**
 * LE BOUTON DE DÉCONNEXION EST BRANCHÉ (Story 2.7, T9 — AC3/AC7, décision D-C).
 *
 * <p>C'est la moitié que `shells.spec.ts` ne peut pas prouver. Là-bas, chaque shell est
 * monté à la main et l'on constate qu'il porte un bouton ; ici, l'application ENTIÈRE est
 * montée avec le vrai routeur, et l'on constate que l'utilisateur le rencontre — sur ses
 * routes, dans son espace, et pas ailleurs. *« Un shell testé isolément prouve qu'il
 * fonctionne, pas qu'il est branché »* : l'en-tête de ce fichier porte déjà cette phrase,
 * écrite le jour où `ClientShell` existait, passait ses tests, et n'était rendu par aucune
 * des sept routes de son espace.
 */
describe('La déconnexion est atteignable depuis chaque espace', () => {
  it.each(['/', '/transactions', '/wallet', '/support', '/profile'])(
    '%s porte la sortie — pas seulement le tableau de bord',
    async (path) => {
      // Le bouton vivait DANS le tableau de bord. Les quatre autres surfaces de l'espace
      // client n'en avaient donc aucune : l'utilisateur devait revenir à l'accueil pour
      // se déconnecter. Chaque route est éprouvée, car c'est sur les écrans secondaires
      // qu'une sortie manquante passe inaperçue.
      expect((await open(path, BUYER)).findAll(LOGOUT), path).toHaveLength(1)
    },
  )

  it('l’espace back-office en porte une — c’est le défaut que D-C a trouvé', async () => {
    // ADMIN et ARBITRATOR n'avaient AUCUN moyen de se déconnecter par l'interface : leur
    // shell ne portait pas le bouton, et ils n'atteignent jamais le tableau de bord client.
    expect((await open('/admin', ADMIN)).findAll(LOGOUT)).toHaveLength(1)
  })

  it('les écrans SANS shell n’en portent pas — la sortie suit le chrome, pas la page', async () => {
    // Appariée aux positives ci-dessus, et elle porte sa propre exigence. Un bouton de
    // déconnexion sur l'écran de connexion serait absurde ; sur l'écran de refus, il
    // ajouterait au chrome vide une information — « vous êtes connecté » — que NFR-P9
    // interdit d'y lire. Comptes exacts : `findAll` rend 0 pour un sélecteur inexistant,
    // donc une assertion « aucun » est vraie par construction si le testid est mal écrit.
    // C'est le compte de 1 des tests précédents, sur le MÊME sélecteur, qui la sauve.
    expect((await open('/auth', null)).findAll(LOGOUT)).toHaveLength(0)
    expect((await open('/admin', BUYER)).findAll(LOGOUT)).toHaveLength(0)
  })
})

describe('Le clic termine RÉELLEMENT la session, de bout en bout', () => {
  it('purge l’appareil et ramène sur la connexion, routeur et stores réels', async () => {
    // LE test de câblage. Tout ce qui précède prouve que le bouton est là ; celui-ci prouve
    // qu'il fait ce qu'il annonce quand il est branché à la vraie application — le seul
    // montage où `LogoutButton`, `App.vue`, la résolution du shell par `meta.space`, la
    // garde du routeur et `endSession` se rencontrent.
    const wrapper = await open('/', BUYER)
    const { useAuthStore } = await import('@/stores/auth')
    const { default: router } = await import('@/router')
    const auth = useAuthStore()

    // Appariée d'entrée : la session existe VRAIMENT avant le clic. Sans ce temps, un
    // montage qui n'aurait jamais authentifié personne satisferait toutes les assertions
    // finales par le vide — le chemin déconnecté testé en croyant tester la déconnexion.
    expect(auth.isAuthenticated).toBe(true)
    expect(readToken()).not.toBeNull()

    await wrapper.find(LOGOUT).trigger('click')
    await vi.waitFor(() => expect(router.currentRoute.value.name).toBe('auth'))

    expect(auth.isAuthenticated).toBe(false)
    expect(auth.user).toBeNull()
    // L'appareil, pas seulement la mémoire : c'est la distinction que la Story 1.9 a payé
    // trois passes de revue, et que `auth.logout()` — l'appel INTERDIT ici — ne tient pas.
    expect(readToken()).toBeNull()
  })
})
