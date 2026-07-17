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
    return { name: 'auth' }
  }
  if (to.name === 'auth' && auth.isAuthenticated) {
    return { name: 'dashboard' }
  }
  return true
})

export default router
