<script setup>
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import StateBadge from './StateBadge.vue'

const props = defineProps({
  transaction: { type: Object, required: true },
})

const auth = useAuthStore()

const isBuyerSide = computed(() => auth.user?.email === props.transaction.buyerEmail)

const counterparty = computed(() => {
  if (props.transaction._queuedOffline) return props.transaction.sellerEmail
  return isBuyerSide.value ? props.transaction.sellerEmail : props.transaction.buyerEmail
})

const counterpartyRoleLabel = computed(() =>
  props.transaction._queuedOffline || isBuyerSide.value ? 'Seller' : 'Buyer',
)

const formattedAmount = computed(() => {
  try {
    return new Intl.NumberFormat(undefined, {
      style: 'currency',
      currency: props.transaction.currency,
    }).format(props.transaction.amount)
  } catch {
    return `${props.transaction.amount} ${props.transaction.currency}`
  }
})
</script>

<template>
  <component
    :is="transaction._queuedOffline ? 'div' : 'router-link'"
    :to="transaction._queuedOffline ? undefined : `/escrow/${transaction.id}`"
    class="block rounded-xl border border-gray-200 bg-white p-4 shadow-sm transition"
    :class="transaction._queuedOffline ? 'opacity-70' : 'hover:border-brand-300 hover:shadow-md'"
  >
    <div class="flex items-start justify-between gap-2">
      <div class="min-w-0">
        <p class="text-xs uppercase tracking-wide text-gray-400">{{ counterpartyRoleLabel }}</p>
        <p class="truncate font-medium text-gray-900">{{ counterparty || '—' }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <StateBadge v-if="!transaction._queuedOffline" :state="transaction.state" />
        <span
          v-else
          class="inline-flex shrink-0 items-center rounded-full bg-orange-100 px-2.5 py-1 text-xs font-semibold text-orange-700"
        >
          Queued offline
        </span>
        <!-- The badge above shows the optimistic DISPUTED: say it isn't confirmed yet. -->
        <span
          v-if="transaction._queuedDispute"
          class="inline-flex shrink-0 items-center rounded-full bg-orange-100 px-2.5 py-1 text-xs font-semibold text-orange-700"
        >
          Pending sync
        </span>
      </div>
    </div>
    <div class="mt-3 flex items-end justify-between gap-2">
      <p class="text-xl font-semibold text-gray-900">{{ formattedAmount }}</p>
      <p v-if="transaction.description" class="max-w-[55%] truncate text-right text-sm text-gray-500">
        {{ transaction.description }}
      </p>
    </div>
  </component>
</template>
