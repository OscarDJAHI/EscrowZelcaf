<script setup>
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useEvidenceStore } from '@/stores/evidence'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import StateBadge from '@/components/StateBadge.vue'
import StepperEscrow from '@/components/StepperEscrow.vue'
import AuditTimeline from '@/components/AuditTimeline.vue'
import EvidenceDeposit from '@/components/EvidenceDeposit.vue'
import EvidenceList from '@/components/EvidenceList.vue'
import OpenDisputeForm from '@/components/OpenDisputeForm.vue'
import { canOpenDispute, getAllowedEventsForTransaction } from '@/utils/stateMachine'

const { t, locale } = useI18n()

const DEPOSIT_STATES = ['FUNDS_LOCKED', 'SHIPPED', 'DISPUTED']

const props = defineProps({
  id: { type: String, required: true },
})

// L'identifiant de transaction arrive par la prop `id` (route en mode `props: true`),
// jamais par `useRoute()` : la liaison était morte, et son import avec elle.
const router = useRouter()
const auth = useAuthStore()
const escrowStore = useEscrowStore()
const evidenceStore = useEvidenceStore()
const offlineQueue = useOfflineQueueStore()

const sendingEvent = ref(null)
const actionError = ref('')
const showDisputeForm = ref(false)

const transaction = computed(() => escrowStore.currentDetail?.transaction || null)
const auditLogs = computed(() => escrowStore.currentDetail?.auditLogs || [])
const allowedEvents = computed(() => getAllowedEventsForTransaction(transaction.value, auth.user))
const canDeposit = computed(() => DEPOSIT_STATES.includes(transaction.value?.state))
const canOpen = computed(() => canOpenDispute(transaction.value, auth.user))

const counterparty = computed(() => {
  if (!transaction.value || !auth.user) return ''
  return auth.user.email === transaction.value.buyerEmail
    ? transaction.value.sellerEmail
    : transaction.value.buyerEmail
})

const formattedAmount = computed(() => {
  if (!transaction.value) return ''
  try {
    // Langue de l'APPLICATION et non du navigateur : `Intl` appelé avec `undefined` suit
    // la locale du poste, si bien que basculer l'interface en FR ne changeait ni les
    // montants ni les dates (AC3, constat de revue).
    return new Intl.NumberFormat(locale.value, {
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
  return 'bg-primary hover:bg-primary-hover'
}

function load() {
  return escrowStore.loadTransactionDetail(props.id)
}

function loadEvidence() {
  return evidenceStore.loadEvidence(props.id)
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
    actionError.value = err.response?.data?.message || t('transaction.actionFailed')
  } finally {
    sendingEvent.value = null
  }
}

function onDisputeOpened() {
  // Reload the full detail (transaction + audit history) AND the evidence: the
  // store already flipped the transaction to DISPUTED, but the audit timeline
  // and the attachment(s) filed with the opening only appear after a reload.
  // Offline there is nothing to reload — and the failed fetch would set
  // `escrowStore.error`, replacing the optimistic detail with an error panel.
  showDisputeForm.value = false
  if (offlineQueue.isOnline) {
    load()
    loadEvidence()
  }
}

function onSync() {
  load()
  loadEvidence()
}

onMounted(() => {
  load()
  loadEvidence()
  window.addEventListener('escrow:sync', onSync)
})
onBeforeUnmount(() => {
  window.removeEventListener('escrow:sync', onSync)
})
</script>

<template>
  <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
    <button class="mb-4 text-sm text-primary hover:underline" @click="router.push('/')">
      {{ $t('transaction.back') }}
    </button>

    <div v-if="escrowStore.loading && !transaction" class="py-16 text-center text-sm text-gray-400">
      {{ $t('transaction.loading') }}
    </div>
    <div v-else-if="escrowStore.error" class="rounded-lg bg-red-50 p-4 text-sm text-red-700">
      {{ escrowStore.error }}
    </div>

    <template v-else-if="transaction">
      <div class="rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div>
            <p class="text-xs uppercase tracking-wide text-gray-400">{{ $t('transaction.reference', { id: transaction.id }) }}</p>
            <h1 class="text-2xl font-bold text-gray-900">{{ formattedAmount }}</h1>
            <p class="text-sm text-gray-500">{{ $t('transaction.counterparty', { email: counterparty }) }}</p>
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
          {{ $t('transaction.queuedEvent', { event: transaction._queuedEvent }) }}
        </div>

        <div
          v-if="transaction._queuedDispute"
          class="mt-4 rounded-lg bg-orange-50 px-3 py-2 text-sm text-orange-700"
        >
          {{ $t('transaction.queuedDispute') }}
        </div>

        <div
          v-if="allowedEvents.length || canOpen"
          class="mt-6 flex flex-wrap gap-3 border-t border-gray-100 pt-4"
        >
          <button
            v-for="action in allowedEvents"
            :key="action.event"
            :disabled="sendingEvent === action.event"
            class="rounded-lg px-4 py-2 text-sm font-semibold text-white shadow disabled:opacity-60"
            :class="buttonClasses(action.event)"
            @click="trigger(action.event)"
          >
            {{ sendingEvent === action.event ? $t('common.sending') : $t(action.labelKey) }}
          </button>
          <button
            v-if="canOpen"
            type="button"
            class="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white shadow hover:bg-red-700"
            @click="showDisputeForm = !showDisputeForm"
          >
            {{ $t('transaction.openDispute') }}
          </button>
        </div>
        <p v-else class="mt-6 border-t border-gray-100 pt-4 text-sm text-gray-400">
          {{ $t('transaction.noActions') }}
        </p>

        <div v-if="canOpen && showDisputeForm" class="mt-4 rounded-xl border border-red-100 bg-red-50/40 p-4">
          <OpenDisputeForm
            :transaction-id="id"
            @opened="onDisputeOpened"
            @cancel="showDisputeForm = false"
          />
        </div>

        <p v-if="actionError" class="mt-3 text-sm text-red-600">{{ actionError }}</p>
      </div>

      <div class="mt-6 rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 class="mb-4 text-sm font-semibold text-gray-900">{{ $t('transaction.auditHistory') }}</h2>
        <AuditTimeline :logs="auditLogs" />
      </div>

      <div class="mt-6 rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 class="mb-4 text-sm font-semibold text-gray-900">{{ $t('transaction.evidence') }}</h2>
        <div v-if="canDeposit" class="mb-6 border-b border-gray-100 pb-6">
          <EvidenceDeposit :transaction-id="id" @uploaded="loadEvidence" />
        </div>
        <EvidenceList :transaction-id="id" />
      </div>
    </template>
  </div>
</template>
