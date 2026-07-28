import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes = [
  {
    path: '/auth',
    name: 'auth',
    component: () => import('@/views/AuthView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    name: 'dashboard',
    component: () => import('@/views/DashboardView.vue'),
  },
  {
    path: '/escrow/:id',
    name: 'escrow-detail',
    component: () => import('@/views/TransactionDetailView.vue'),
    props: true,
  },
  // No `meta`: routes are protected by default (see the guard below), and this
  // one hands back the binaries of a frozen queue entry. Marking it public would
  // be the exact inversion of what it needs.
  {
    path: '/recovery/:entryId',
    name: 'recovery',
    component: () => import('@/views/RecoveryView.vue'),
    props: true,
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
  {
    path: '/:pathMatch(.*)*',
    redirect: '/',
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
  if (to.name === 'auth' && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
