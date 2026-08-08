<script setup lang="ts">
import { roleLabel } from '@/i18n/labels'
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { endSession } from '@/stores/session'
import TransactionCard from '@/components/TransactionCard.vue'
import NewTransactionModal from '@/components/NewTransactionModal.vue'

const { t, te } = useI18n()

const auth = useAuthStore()
const escrowStore = useEscrowStore()
const router = useRouter()

const showModal = ref(false)
const creating = ref(false)
const createError = ref('')

const isBuyer = computed(() => auth.role === 'BUYER')

function refresh() {
  escrowStore.loadTransactions()
}

onMounted(() => {
  refresh()
  window.addEventListener('escrow:sync', refresh)
})
onBeforeUnmount(() => {
  window.removeEventListener('escrow:sync', refresh)
})

async function handleCreate(payload) {
  creating.value = true
  createError.value = ''
  try {
    await escrowStore.createNewTransaction(payload)
    showModal.value = false
  } catch (err) {
    createError.value = err.response?.data?.message || t('dashboard.createFailed')
  } finally {
    creating.value = false
  }
}

async function logout() {
  // Plus de rechargement complet (Story 1.9) : il n'a jamais rien purgé d'IndexedDB
  // ni du cache de lecture — c'est `endSession` qui le fait, explicitement — et une
  // navigation de routeur rend le parcours testable.
  //
  // On ATTEND `endSession` avant de naviguer, mais plus pour la raison écrite ici
  // jusqu'ici. La revue 1.6 exigeait l'attente parce que `window.location.href`
  // avortait la révocation en vol ; `router.replace` est une navigation SPA qui
  // n'avorte rien, et `logoutUser` porte de toute façon `keepalive`. Ce qui reste
  // vrai : `endSession` purge TOUT le local — mémoire, IndexedDB, cache de lecture,
  // marqueur — AVANT d'attendre le réseau, donc l'appareil est déjà propre quand on
  // arrive ici. Ce qui reste faux : `logoutUser` est un `fetch` sans timeout ni
  // AbortController, si bien qu'un portail captif retient cette navigation aussi
  // longtemps qu'il retient la requête. Borner cette attente touche une décision de
  // la revue 1.6 et sort du périmètre de la 1.9 : c'est au ledger de reports.
  await endSession({ reason: 'logout' })
  try {
    await router.replace({ name: 'auth' })
  } catch (err) {
    // Toutes les routes sont des chunks paresseux : `replace` rejette si celui
    // d'`AuthView` ne se charge pas (déploiement qui invalide le nom haché,
    // précache évincé hors ligne). La session vient d'être détruite — rester ici
    // laisserait l'utilisateur sur un tableau de bord vide sans jeton et sans
    // issue, et le rejet s'échapperait d'un gestionnaire de clic `async`. Un
    // rechargement complet est le filet : il repart du serveur et la garde de
    // routeur renverra sur `/auth`.
    console.error('[dashboard] navigation vers la connexion impossible, rechargement', err)
    window.location.assign('/auth')
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
    <div class="mb-6 flex items-start justify-between gap-3">
      <div>
        <h1 class="text-xl font-bold text-gray-900">{{ $t('dashboard.myTransactions') }}</h1>
        <p class="text-sm text-gray-500">
          {{ auth.user?.email }} ·
          <span class="font-medium">{{ roleLabel({ t, te }, auth.role) }}</span>
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button
          v-if="isBuyer"
          class="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover"
          @click="showModal = true"
        >
          {{ $t('dashboard.newTransaction') }}
        </button>
        <button
          class="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-600 hover:bg-gray-50"
          @click="logout"
        >
          {{ $t('common.logout') }}
        </button>
      </div>
    </div>

    <div v-if="escrowStore.loading" class="py-16 text-center text-sm text-gray-400">
      {{ $t('dashboard.loading') }}
    </div>
    <div v-else-if="escrowStore.error" class="rounded-lg bg-red-50 p-4 text-sm text-red-700">
      {{ escrowStore.error }}
    </div>
    <div
      v-else-if="escrowStore.transactions.length === 0"
      class="rounded-xl border border-dashed border-gray-300 py-16 text-center text-sm text-gray-400"
    >
      {{ $t('dashboard.empty') }}
      <span v-if="isBuyer">{{ $t('dashboard.createFirst') }}</span>
    </div>
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <TransactionCard v-for="tx in escrowStore.transactions" :key="tx.id" :transaction="tx" />
    </div>

    <NewTransactionModal
      v-if="showModal"
      :submitting="creating"
      :error-message="createError"
      @close="showModal = false"
      @submit="handleCreate"
    />
  </div>
</template>
