<script setup>
import { RouterView } from 'vue-router'
import OnlineBanner from '@/components/OnlineBanner.vue'
import SyncFailureNotice from '@/components/SyncFailureNotice.vue'
</script>

<template>
  <div class="flex min-h-screen flex-col bg-gray-50">
    <OnlineBanner />
    <!-- Outside the router on purpose: mounted in a view, the notice would
         vanish on the next navigation, and a definitive rejection must not be
         dismissible by accident. -->
    <SyncFailureNotice />
    <RouterView />
  </div>
</template>
