import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { HOME_BY_SPACE, SPACES, resolveSpaceAccess, spaceForRole } from '@/router/spaces'

/**
 * Une seule application, trois espaces, une seule authentification (UX-DR20).
 *
 * <p>Chaque route protégée déclare `meta.space`. La garde compare cet espace à celui du
 * rôle et sert, en cas de refus, la MÊME réponse que pour une route inconnue — voir
 * `AccessDeniedView` et `spaces.js` pour la raison (NFR-P9).
 */
const routes = [
  {
    path: '/auth',
    name: 'auth',
    component: () => import('@/views/AuthView.vue'),
    meta: { public: true },
  },

  // --- Espace CLIENT ---------------------------------------------------------
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
    meta: { space: SPACES.CLIENT },
  },
  {
    path: '/transactions',
    name: 'transactions',
    component: () => import('@/views/ComingSoonView.vue'),
    props: { titleKey: 'nav.transactions' },
    meta: { space: SPACES.CLIENT },
  },
  {
    path: '/wallet',
    name: 'wallet',
    component: () => import('@/views/ComingSoonView.vue'),
    props: { titleKey: 'nav.wallet' },
    meta: { space: SPACES.CLIENT },
  },
  {
    path: '/support',
    name: 'support',
    component: () => import('@/views/ComingSoonView.vue'),
    props: { titleKey: 'nav.support' },
    meta: { space: SPACES.CLIENT },
  },
  {
    path: '/profile',
    name: 'profile',
    component: () => import('@/views/ComingSoonView.vue'),
    props: { titleKey: 'nav.profile' },
    meta: { space: SPACES.CLIENT },
  },
  {
    path: '/escrow/:id',
    name: 'escrow-detail',
    component: () => import('@/views/TransactionDetailView.vue'),
    props: true,
    meta: { space: SPACES.CLIENT },
  },
  // No `meta.public`: routes are protected by default (see the guard below), and this
  // one hands back the binaries of a frozen queue entry. Marking it public would
  // be the exact inversion of what it needs.
  {
    path: '/recovery/:entryId',
    name: 'recovery',
    component: () => import('@/views/RecoveryView.vue'),
    props: true,
    meta: { space: SPACES.CLIENT },
  },

  // --- Espace ARBITRAGE ------------------------------------------------------
  // Inatteignable tant que la Story 7-2 n'octroie pas le rôle ARBITRATOR (AD-21) :
  // la porte est livrée et gardée, c'est le porteur de la clé qui manque.
  {
    path: '/arbitration',
    name: 'arbitration-home',
    component: () => import('@/views/ArbitrationHomeView.vue'),
    meta: { space: SPACES.ARBITRATION },
  },

  // --- Espace BACK-OFFICE ----------------------------------------------------
  {
    path: '/admin',
    name: 'admin-home',
    component: () => import('@/views/AdminHomeView.vue'),
    meta: { space: SPACES.ADMIN },
  },

  // Galerie de composants — DÉVELOPPEMENT UNIQUEMENT.
  //
  // Déclarée dans un spread conditionnel sur `import.meta.env.DEV`, que Vite remplace par
  // `false` au build : le tableau est alors vide et l'import dynamique n'est jamais
  // atteint, donc son chunk n'est pas émis. Un import STATIQUE en tête de fichier aurait
  // embarqué la galerie dans le bundle malgré la condition — c'est le piège que la story
  // signale, et c'est pourquoi l'import reste dynamique.
  //
  // La condition n'est pas une preuve : `npm run verify:no-demo` cherche la sentinelle
  // ESCROW_COMPONENT_GALLERY_DEV_ONLY dans `dist/` et échoue si elle y est. Ce script
  // tourne en CI juste après le build.
  ...(import.meta.env.DEV
    ? [
        {
          path: '/_components',
          name: 'component-gallery',
          component: () => import('@/views/ComponentGalleryView.vue'),
          meta: { public: true },
        },
      ]
    : []),

  // Route inconnue : MÊME écran qu'un accès refusé.
  //
  // L'ancien catch-all redirigeait vers `/`. Cela révélait la différence entre « cette
  // adresse n'existe pas » (redirection) et « elle existe mais pas pour vous » (autre
  // chose) : exactement l'oracle d'énumération que NFR-P9 interdit et que la Story 1.10 a
  // fermé côté API. Les deux cas servent désormais le même écran.
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/AccessDeniedView.vue'),
    meta: { space: null },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()

  if (!to.meta.public && !auth.isAuthenticated) {
    // UX-DR32: a deep link opened without a session must come back to its target
    // once signed in. Omitted for the dashboard — `redirect=/` is where the sign-
    // in screen sends people anyway, and spelling it out only makes the URL
    // longer and the open-redirect guard's job less obvious.
    return to.fullPath === '/' ? { name: 'auth' } : { name: 'auth', query: { redirect: to.fullPath } }
  }

  // SESSION INCOHÉRENTE : jeton valide, profil sans rôle exploitable.
  //
  // Ce n'est ni une absence d'authentification, ni un refus d'accès — c'est un état que
  // le ledger de la Story 1.9 documente déjà (`escrow_user` illisible pendant que
  // `escrow_token` survit). Le traiter comme un refus condamnerait l'écran de
  // récupération, qui rend à l'utilisateur des fichiers n'existant NULLE PART ailleurs.
  // On renvoie donc vers la connexion, qui répare le profil, plutôt que vers une impasse.
  //
  // Aucun oracle : la décision ne dépend que de l'état de l'utilisateur, jamais de la
  // cible demandée — toutes les cibles donnent le même résultat.
  const space = spaceForRole(auth.user?.role)
  if (auth.isAuthenticated && space === null) {
    return to.name === 'auth' ? true : { name: 'auth', query: { redirect: to.fullPath } }
  }

  if (to.name === 'auth' && auth.isAuthenticated) {
    // Chacun chez soi : la connexion renvoie vers l'accueil de l'espace du RÔLE, et non
    // vers un tableau de bord client que tout le monde n'a pas vocation à voir.
    return { name: HOME_BY_SPACE[space] }
  }

  if (!to.meta.public && to.name !== 'not-found') {
    const { allowed } = resolveSpaceAccess(auth.user?.role, to.meta.space)
    if (!allowed) {
      // L'URL DEMANDÉE DOIT RESTER AFFICHÉE — d'où `pathMatch`.
      //
      // Un simple `{ name: 'not-found' }` semble équivalent, et ne l'est pas : le
      // catch-all vaut `/:pathMatch(.*)*`, et le résoudre sans paramètre produit `/`.
      // L'espace interdit renvoyait donc vers `/` pendant qu'une adresse inconnue, elle,
      // conservait la sienne. Même écran, deux URL : il suffisait de regarder la barre
      // d'adresse pour savoir laquelle des deux cibles existait. C'est l'oracle que la
      // Story 1.10 a fermé côté API, rouvert ici par la seule forme de la redirection.
      // `guards.spec.js` compare les deux chemins et repasse au rouge si on l'oublie.
      return {
        name: 'not-found',
        params: { pathMatch: to.path.slice(1).split('/') },
        query: to.query,
        hash: to.hash,
      }
    }
  }

  return true
})

export default router
