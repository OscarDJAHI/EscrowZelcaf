<script setup lang="ts">
import { roleLabel } from '@/i18n/labels'
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import TransactionCard from '@/components/TransactionCard.vue'
import NewTransactionModal from '@/components/NewTransactionModal.vue'
import { apiErrorMessage } from '@/utils/apiError'
import type { CreateTransactionPayload } from '@/api/escrow'

const { t, te } = useI18n()

const auth = useAuthStore()
const escrowStore = useEscrowStore()

const showModal = ref(false)
const creating = ref(false)
const createError = ref('')

// La DÉCONNEXION n'est plus ici (Story 2.7, décision D-C) : elle vit dans
// `components/LogoutButton.vue`, placé par les deux shells. Elle n'appartenait pas à cet
// écran — les rôles servis par `DesktopShell` n'y accèdent jamais et se retrouvaient sans
// aucune sortie. Ne pas la réintroduire ici : elle y serait rendue DEUX fois pour l'espace
// client, et une seule des deux serait couverte.
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

async function handleCreate(payload: CreateTransactionPayload) {
  creating.value = true
  createError.value = ''
  try {
    await escrowStore.createNewTransaction(payload)
    showModal.value = false
  } catch (err) {
    createError.value = apiErrorMessage(err) || t('dashboard.createFailed')
  } finally {
    creating.value = false
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
