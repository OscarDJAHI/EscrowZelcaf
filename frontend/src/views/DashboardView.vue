<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import TransactionCard from '@/components/TransactionCard.vue'
import NewTransactionModal from '@/components/NewTransactionModal.vue'

const auth = useAuthStore()
const escrowStore = useEscrowStore()

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
    createError.value = err.response?.data?.message || 'Failed to create transaction.'
  } finally {
    creating.value = false
  }
}

function logout() {
  auth.logout()
  window.location.href = '/auth'
}
</script>

<template>
  <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
    <div class="mb-6 flex items-start justify-between gap-3">
      <div>
        <h1 class="text-xl font-bold text-gray-900">My transactions</h1>
        <p class="text-sm text-gray-500">
          {{ auth.user?.email }} ·
          <span class="font-medium">{{ auth.role }}</span>
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button
          v-if="isBuyer"
          class="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white shadow hover:bg-brand-700"
          @click="showModal = true"
        >
          + New transaction
        </button>
        <button
          class="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-600 hover:bg-gray-50"
          @click="logout"
        >
          Log out
        </button>
      </div>
    </div>

    <div v-if="escrowStore.loading" class="py-16 text-center text-sm text-gray-400">
      Loading transactions…
    </div>
    <div v-else-if="escrowStore.error" class="rounded-lg bg-red-50 p-4 text-sm text-red-700">
      {{ escrowStore.error }}
    </div>
    <div
      v-else-if="escrowStore.transactions.length === 0"
      class="rounded-xl border border-dashed border-gray-300 py-16 text-center text-sm text-gray-400"
    >
      No transactions yet.
      <span v-if="isBuyer">Create your first one to get started.</span>
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
