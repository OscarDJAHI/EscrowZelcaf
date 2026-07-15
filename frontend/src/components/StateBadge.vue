<script setup>
import { computed } from 'vue'
import { STATE_COLORS } from '@/utils/stateMachine'

const props = defineProps({
  state: { type: String, required: true },
})

const classes = computed(() => STATE_COLORS[props.state]?.badge || 'bg-gray-100 text-gray-700')
const label = computed(() => props.state.replaceAll('_', ' '))
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center rounded-full px-2.5 py-1 text-xs font-semibold"
    :class="classes"
  >
    {{ label }}
  </span>
</template>
