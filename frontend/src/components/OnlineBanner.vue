<script setup>
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useOfflineQueueStore } from '@/stores/offlineQueue'

const offlineQueue = useOfflineQueueStore()
const { isOnline, pendingCount, flushing } = storeToRefs(offlineQueue)

const visible = computed(() => !isOnline.value || pendingCount.value > 0)
</script>

<template>
  <div
    v-if="visible"
    class="w-full px-4 py-2 text-center text-xs font-medium sm:text-sm"
    :class="isOnline ? 'bg-amber-100 text-amber-800' : 'bg-red-100 text-red-800'"
  >
    <span v-if="!isOnline">
      {{ $t('offline.banner') }}
      <span v-if="pendingCount > 0"> {{ $t('offline.queuedCount', { count: pendingCount }) }}</span>.
    </span>
    <span v-else-if="flushing">{{ $t('offline.syncing', { count: pendingCount }) }}</span>
    <span v-else>{{ $t('offline.waiting', { count: pendingCount }) }}</span>
  </div>
</template>
