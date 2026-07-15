<script setup>
import { RouterView } from 'vue-router'
import OnlineBanner from '@/components/OnlineBanner.vue'
</script>

<template>
  <div class="flex min-h-screen flex-col bg-gray-50">
    <OnlineBanner />
    <RouterView />
  </div>
</template>
