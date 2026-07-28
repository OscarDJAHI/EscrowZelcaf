import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '@/stores/auth'

/**
 * AC1 et AC4 — guards, redirections par rôle, et réponse uniforme.
 *
 * <p>Le routeur est réimporté à chaque test (`vi.resetModules`) : c'est un singleton qui
 * conserve sa route courante, et un test qui hériterait de la navigation du précédent
 * prouverait autre chose que ce qu'il annonce. Ce dépôt a un antécédent d'isolation
 * défaillante — un test de session qui ne passait que si deux autres suites l'avaient
 * précédé.
 */

async function navigate(user, path) {
  vi.resetModules()
  setActivePinia(createPinia())
  const { default: router } = await import('@/router')
  if (user) useAuthStore().applySession({ token: 'jeton', user })
  await router.push(path).catch(() => {})
  await router.isReady()
  return router.currentRoute.value
}

const BUYER = { id: 1, email: 'a@corp.example', role: 'BUYER' }
const ADMIN = { id: 2, email: 'b@corp.example', role: 'ADMIN' }
const ARBITRE = { id: 3, email: 'c@corp.example', role: 'ARBITRATOR' }

beforeEach(() => setActivePinia(createPinia()))

describe('Redirection par rôle à la connexion', () => {
  it('un acheteur qui ouvre /auth atterrit sur son tableau de bord', async () => {
    expect((await navigate(BUYER, '/auth')).name).toBe('dashboard')
  })

  it('un administrateur atterrit dans le back-office, PAS sur le tableau de bord client', async () => {
    // Avant cette story, toute connexion menait à `dashboard`. Un opérateur voyait donc
    // d'abord un écran qui n'est pas le sien.
    expect((await navigate(ADMIN, '/auth')).name).toBe('admin-home')
  })

  it('un arbitre atterrit sur la console d’arbitrage', async () => {
    // ⚠️ Rôle simulé : `Role.java` ne l'émet pas encore (octroi porté par la Story 7-2,
    // AD-21). Le guard est livré et prouvé ; l'espace reste inatteignable en pratique.
    expect((await navigate(ARBITRE, '/auth')).name).toBe('arbitration-home')
  })
})

describe('Cloisonnement des espaces', () => {
  it('un acheteur ne peut pas entrer dans le back-office', async () => {
    expect((await navigate(BUYER, '/admin')).name).toBe('not-found')
  })

  it('un acheteur ne peut pas entrer dans la console d’arbitrage', async () => {
    expect((await navigate(BUYER, '/arbitration')).name).toBe('not-found')
  })

  it('un administrateur ne peut pas entrer dans l’espace client', async () => {
    // Le cloisonnement va dans les DEUX sens : un privilège plus élevé n'est pas un
    // passe-partout, et un opérateur n'a rien à faire dans les transactions d'un client.
    expect((await navigate(ADMIN, '/')).name).toBe('not-found')
  })

  it('chacun accède au sien', async () => {
    expect((await navigate(BUYER, '/')).name).toBe('dashboard')
    expect((await navigate(ADMIN, '/admin')).name).toBe('admin-home')
    expect((await navigate(ARBITRE, '/arbitration')).name).toBe('arbitration-home')
  })
})

describe('Réponse UNIFORME — aucun oracle d’énumération (NFR-P9)', () => {
  it('un espace interdit et une adresse inexistante rendent la MÊME route', async () => {
    // L'assertion qui porte l'exigence. Si un jour l'un redirige et l'autre affiche, la
    // simple observation du comportement révèle lequel des deux existe.
    const interdit = await navigate(BUYER, '/admin')
    const inexistant = await navigate(BUYER, '/adresse-qui-nexiste-pas')
    expect(interdit.name).toBe(inexistant.name)
  })

  it('l’URL demandée reste affichée dans les deux cas — pas de redirection révélatrice', async () => {
    // Rediriger vers `/` sur une adresse inconnue, comme le faisait le catch-all d'avant,
    // suffisait à distinguer les deux cas sans même lire la page.
    expect((await navigate(BUYER, '/admin')).path).toBe('/admin')
    expect((await navigate(BUYER, '/nimporte-quoi')).path).toBe('/nimporte-quoi')
  })

  it('conserve aussi la chaîne de requête et le fragment', async () => {
    // Le refus les recopie (`query: to.query, hash: to.hash`) et rien ne le relisait. Les
    // perdre distinguerait à nouveau les deux cas : une adresse inconnue garde les siens,
    // un espace refusé les aurait effacés — même écran, URL amputée d'un côté seulement.
    const interdit = await navigate(BUYER, '/admin?onglet=kyb#section')
    const inexistant = await navigate(BUYER, '/inconnu?onglet=kyb#section')
    expect(interdit.query).toEqual({ onglet: 'kyb' })
    expect(interdit.hash).toBe('#section')
    expect(interdit.fullPath.replace('/admin', '')).toBe(inexistant.fullPath.replace('/inconnu', ''))
  })
})

describe('Session incohérente — jeton valide, profil sans rôle', () => {
  it('renvoie vers la connexion plutôt que vers une impasse', async () => {
    // État documenté au ledger de la Story 1.9 : `escrow_user` illisible pendant que
    // `escrow_token` survit. Le traiter comme un refus condamnerait l'écran de
    // récupération, seul endroit d'où l'utilisateur récupère des fichiers qui n'existent
    // nulle part ailleurs.
    const sansRole = { id: 9, email: 'd@corp.example' }
    expect((await navigate(sansRole, '/recovery/entry-1')).name).toBe('auth')
  })

  it('conserve la cible pour y revenir après ré-authentification', async () => {
    const sansRole = { id: 9, email: 'd@corp.example' }
    const route = await navigate(sansRole, '/recovery/entry-1')
    expect(route.query.redirect).toBe('/recovery/entry-1')
  })

  // Un rôle emprunté à la chaîne de prototypes est une session incohérente comme une
  // autre, et doit donc rendre la connexion ATTEIGNABLE. Avant correctif, `spaceForRole`
  // rendait ici une fonction au lieu de `null` : la branche ci-dessus était sautée et
  // `/auth` finissait lui-même sur l'écran de refus. L'utilisateur ne pouvait plus rien
  // réparer — ni sa session, ni l'accès à ses fichiers de récupération.
  it.each(['constructor', '__proto__', 'toString'])(
    'un rôle « %s » n’enferme pas l’utilisateur hors de la connexion',
    async (role) => {
      const route = await navigate({ id: 8, email: 'e@corp.example', role }, '/recovery/entry-1')
      expect(route.name).toBe('auth')
      expect(route.query.redirect).toBe('/recovery/entry-1')
    },
  )

  // `it.each` et non une boucle : chaque navigation réinitialise les modules, un état
  // GLOBAL, donc elles ne peuvent pas s'exécuter en parallèle. Un cas par test dit cela
  // explicitement, et nomme celui qui échoue au lieu d'arrêter la boucle au premier.
  it.each(['/', '/admin', '/arbitration', '/inexistant'])(
    'donne la même réponse pour %s — aucun oracle',
    async (path) => {
      expect((await navigate({ id: 9, email: 'd@corp.example' }, path)).name).toBe('auth')
    },
  )
})

describe('Sans session', () => {
  it.each(['/', '/admin', '/arbitration', '/wallet'])('protège %s', async (path) => {
    expect((await navigate(null, path)).name).toBe('auth')
  })
})
