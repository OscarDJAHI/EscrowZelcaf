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
      You're offline. Actions will be saved and synced automatically once you reconnect
      <span v-if="pendingCount > 0"> ({{ pendingCount }} queued)</span>.
    </span>
    <span v-else-if="flushing">Syncing {{ pendingCount }} queued action(s)…</span>
    <span v-else>{{ pendingCount }} action(s) queued, waiting to sync.</span>
  </div>
</template>
