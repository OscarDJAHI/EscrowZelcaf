<script setup>
import { computed } from 'vue'
import { STATE_COLORS } from '@/utils/stateMachine'

const props = defineProps({
  currentState: { type: String, required: true },
  auditLogs: { type: Array, default: () => [] },
})

// Reconstruct the actual sequence of states this transaction went through,
// derived from the audit trail (ordered chronologically). This correctly
// depicts branch scenarios (e.g. INITIATED -> FUNDS_LOCKED -> DISPUTED ->
// RELEASED) instead of forcing every transaction into the same 4-step path.
const path = computed(() => {
  const sortedLogs = [...props.auditLogs].sort(
    (a, b) => new Date(a.timestamp) - new Date(b.timestamp),
  )

  const states = ['INITIATED']
  for (const log of sortedLogs) {
    if (log.nextState && states[states.length - 1] !== log.nextState) {
      states.push(log.nextState)
    }
  }
  if (states[states.length - 1] !== props.currentState) {
    states.push(props.currentState)
  }
  return states
})
</script>

<template>
  <div class="w-full overflow-x-auto pb-1">
    <ol class="flex min-w-max items-start">
      <li v-for="(state, index) in path" :key="`${state}-${index}`" class="flex items-start">
        <div class="flex w-20 flex-col items-center text-center">
          <div
            class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ring-4 ring-white"
            :class="[
              STATE_COLORS[state]?.dot || 'bg-gray-400',
              index === path.length - 1 ? 'scale-110 shadow-md' : '',
            ]"
          >
            {{ index + 1 }}
          </div>
          <span class="mt-1 text-[11px] font-medium leading-tight text-gray-600">
            {{ state.replaceAll('_', ' ') }}
          </span>
        </div>
        <div
          v-if="index < path.length - 1"
          class="mt-4 h-0.5 w-8 shrink-0 sm:w-12"
          :class="STATE_COLORS[path[index + 1]]?.dot || 'bg-gray-300'"
        />
      </li>
    </ol>
  </div>
</template>
