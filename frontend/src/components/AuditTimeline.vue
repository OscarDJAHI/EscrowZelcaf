<script setup>
import { useI18n } from 'vue-i18n'
import { computed } from 'vue'
import { STATE_COLORS } from '@/utils/stateMachine'

const { locale } = useI18n()

const props = defineProps({
  logs: { type: Array, default: () => [] },
})

const sortedLogs = computed(() =>
  [...props.logs].sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp)),
)

function formatDate(timestamp) {
  return new Date(timestamp).toLocaleString(locale.value)
}
</script>

<template>
  <ol class="space-y-4">
    <li v-if="sortedLogs.length === 0" class="text-sm text-gray-500">{{ $t('audit.empty') }}</li>
    <li v-for="log in sortedLogs" :key="log.id" class="flex gap-3">
      <span
        class="mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full"
        :class="STATE_COLORS[log.nextState]?.dot || 'bg-gray-400'"
      />
      <div class="flex-1 border-b border-gray-100 pb-3 last:border-none">
        <p class="text-sm font-medium text-gray-800">
          {{ $t(`state.${log.previousState || 'NONE'}`) }} →
          {{ $t(`state.${log.nextState}`) }}
        </p>
        <p class="text-xs text-gray-500">{{ $t('audit.by', { who: log.actionBy }) }} · {{ formatDate(log.timestamp) }}</p>
      </div>
    </li>
  </ol>
</template>
