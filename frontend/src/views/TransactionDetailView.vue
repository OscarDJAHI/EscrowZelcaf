<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import StateBadge from '@/components/StateBadge.vue'
import StepperEscrow from '@/components/StepperEscrow.vue'
import AuditTimeline from '@/components/AuditTimeline.vue'
import { getAllowedEventsForTransaction } from '@/utils/stateMachine'

const props = defineProps({
  id: { type: String, required: true },
})

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const escrowStore = useEscrowStore()
const offlineQueue = useOfflineQueueStore()

const sendingEvent = ref(null)
const actionError = ref('')

const transaction = computed(() => escrowStore.currentDetail?.transaction || null)
const auditLogs = computed(() => escrowStore.currentDetail?.auditLogs || [])
const allowedEvents = computed(() => getAllowedEventsForTransaction(transaction.value, auth.user))

const counterparty = computed(() => {
  if (!transaction.value || !auth.user) return ''
  return auth.user.email === transaction.value.buyerEmail
    ? transaction.value.sellerEmail
    : transaction.value.buyerEmail
})

const formattedAmount = computed(() => {
  if (!transaction.value) return ''
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: transaction.value.currency,
    }).format(transaction.value.amount)
  } catch {
    return `${transaction.value.amount} ${transaction.value.currency}`
  }
})

function buttonClasses(event) {
  if (event === 'OPEN_DISPUTE') return 'bg-red-600 hover:bg-red-700'
  if (event === 'RESOLVE_REFUND') return 'bg-purple-600 hover:bg-purple-700'
  if (event === 'RESOLVE_RELEASE' || event === 'DELIVERY_CONFIRMED') return 'bg-green-600 hover:bg-green-700'
  return 'bg-brand-600 hover:bg-brand-700'
}

function load() {
  return escrowStore.loadTransactionDetail(props.id)
}

async function trigger(event) {
  actionError.value = ''
  sendingEvent.value = event
  try {
    await escrowStore.sendTransactionEvent(props.id, event)
    if (offlineQueue.isOnline) {
      await load()
    }
  } catch (err) {
    actionError.value = err.response?.data?.message || 'Action failed. Please try again.'
  } finally {
    sendingEvent.value = null
  }
}

onMounted(() => {
  load()
  window.addEventListener('escrow:sync', load)
})
onBeforeUnmount(() => {
  window.removeEventListener('escrow:sync', load)
})
</script>

<template>
  <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
    <button class="mb-4 text-sm text-brand-700 hover:underline" @click="router.push('/')">
      ← Back to dashboard
    </button>

    <div v-if="escrowStore.loading && !transaction" class="py-16 text-center text-sm text-gray-400">
      Loading transaction…
    </div>
    <div v-else-if="escrowStore.error" class="rounded-lg bg-red-50 p-4 text-sm text-red-700">
      {{ escrowStore.error }}
    </div>

    <template v-else-if="transaction">
      <div class="rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p class="text-xs uppercase tracking-wide text-gray-400">Transaction #{{ transaction.id }}</p>
            <h1 class="text-2xl font-bold text-gray-900">{{ formattedAmount }}</h1>
            <p class="text-sm text-gray-500">Counterparty: {{ counterparty }}</p>
          </div>
          <StateBadge :state="transaction.state" />
        </div>

        <p v-if="transaction.description" class="mt-3 text-sm text-gray-600">
          {{ transaction.description }}
        </p>

        <div class="mt-6">
          <StepperEscrow :current-state="transaction.state" :audit-logs="auditLogs" />
        </div>

        <div
          v-if="transaction._queuedEvent"
          class="mt-4 rounded-lg bg-orange-50 px-3 py-2 text-sm text-orange-700"
        >
          "{{ transaction._queuedEvent }}" is queued offline and will be sent automatically once
          you're back online.
        </div>

        <div v-if="allowedEvents.length" class="mt-6 flex flex-wrap gap-3 border-t border-gray-100 pt-4">
          <button
            v-for="action in allowedEvents"
            :key="action.event"
            :disabled="sendingEvent === action.event"
            class="rounded-lg px-4 py-2 text-sm font-semibold text-white shadow disabled:opacity-60"
            :class="buttonClasses(action.event)"
            @click="trigger(action.event)"
          >
            {{ sendingEvent === action.event ? 'Sending…' : action.label }}
          </button>
        </div>
        <p v-else class="mt-6 border-t border-gray-100 pt-4 text-sm text-gray-400">
          No actions available for your role at this stage.
        </p>

        <p v-if="actionError" class="mt-3 text-sm text-red-600">{{ actionError }}</p>
      </div>

      <div class="mt-6 rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 class="mb-4 text-sm font-semibold text-gray-900">Audit history</h2>
        <AuditTimeline :logs="auditLogs" />
      </div>
    </template>
  </div>
</template>
