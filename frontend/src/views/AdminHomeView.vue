<script setup>
import AppCard from '@/components/AppCard.vue'

/** Accueil du back-office opérateur. Les surfaces réelles arrivent avec l'Epic 7.
 *  La navigation de l'espace est portée par `DESKTOP_NAV` et rendue par `App.vue`. */
</script>

<template>
  <AppCard>
    <h1 class="text-heading">{{ $t('space.admin') }}</h1>
    <p class="mt-2 text-body text-text-muted">{{ $t('comingSoon.body') }}</p>
  </AppCard>
</template>
