<script setup lang="ts">
import { stateLabel } from '@/i18n/labels'
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import type { PropType } from 'vue'
import { STATE_COLORS } from '@/utils/stateMachine'
import type { AuditLog, EscrowState } from '@/types/domain'

const { t, te } = useI18n()

const props = defineProps({
  currentState: { type: String, required: true },
  auditLogs: { type: Array as PropType<AuditLog[]>, default: () => [] },
})

// Reconstruct the actual sequence of states this transaction went through,
// derived from the audit trail (ordered chronologically). This correctly
// depicts branch scenarios (e.g. INITIATED -> FUNDS_LOCKED -> DISPUTED ->
// RELEASED) instead of forcing every transaction into the same 4-step path.
const path = computed(() => {
  const sortedLogs = [...props.auditLogs].sort(
    (a, b) => new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime(),
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

/**
 * Pastille d'un état, avec le même repli que partout ailleurs.
 *
 * <p>Le gabarit indexait `STATE_COLORS` directement. `path` est une liste de CHAÎNES —
 * elle mélange les états du journal d'audit et `currentState`, dont rien ne garantit
 * qu'ils sont au catalogue (`EXPIRED` arrive avec l'Epic 5, et c'est bien pour cela que
 * le `|| 'bg-gray-400'` existait déjà). Passer par une fonction rend ce repli explicite
 * au lieu de le confier à l'optionnel d'un accès indexé.
 */
function dotFor(state: string | null | undefined): string {
  return (state && STATE_COLORS[state as EscrowState]?.dot) || 'bg-gray-400'
}

/** Libellé d'état, avec dégradation lisible pour un état hors catalogue. */
function label(state: string | null | undefined) {
  return stateLabel({ t, te }, state)
}
</script>

<template>
  <div class="w-full overflow-x-auto pb-1">
    <ol class="flex min-w-max items-start">
      <li v-for="(state, index) in path" :key="`${state}-${index}`" class="flex items-start">
        <div class="flex w-20 flex-col items-center text-center">
          <div
            class="flex h-9 w-9 shrink-0 items-center justify-center rounded-full text-xs font-bold text-white ring-4 ring-white"
            :class="[
              dotFor(state),
              index === path.length - 1 ? 'scale-110' : '',
            ]"
          >
            {{ index + 1 }}
          </div>
          <span class="mt-1 text-[11px] font-medium leading-tight text-gray-600">
            {{ label(state) }}
          </span>
        </div>
        <div
          v-if="index < path.length - 1"
          class="mt-4 h-0.5 w-8 shrink-0 sm:w-12"
          :class="dotFor(path[index + 1])"
        />
      </li>
    </ol>
  </div>
</template>
