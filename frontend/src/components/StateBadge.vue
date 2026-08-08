<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { stateLabel } from '@/i18n/labels'
import { STATE_COLORS } from '@/utils/stateMachine'

const props = defineProps({
  state: { type: String, required: true },
})

const { t, te } = useI18n()

const classes = computed(() => STATE_COLORS[props.state]?.badge || 'bg-neutral-surface text-neutral-state')
// Libellé i18n et non `state.replaceAll('_',' ')` : cette dernière forme affichait la
// chaîne MACHINE (`FUNDS LOCKED`) à l'utilisateur, quelle que soit la langue.
const label = computed(() => stateLabel({ t, te }, props.state))
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center rounded-full px-2.5 py-1 text-xs font-semibold"
    :class="classes"
  >
    {{ label }}
  </span>
</template>
