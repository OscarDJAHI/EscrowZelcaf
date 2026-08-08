<script setup lang="ts">
import AppCard from '@/components/AppCard.vue'

/**
 * Accueil de la console d'arbitrage.
 *
 * <p>⚠️ Cet espace est INATTEIGNABLE à la livraison de la Story 2.3 : il se route sur le
 * rôle `ARBITRATOR`, qui n'existe pas encore dans l'énumération serveur — son octroi
 * appartient à la Story 7-2 (AD-21), et l'y ajouter sans ce mécanisme audité serait
 * exactement ce que la Story 1.1 a fermé. Le shell et son guard sont néanmoins livrés et
 * testés avec un rôle simulé : ce qui manque est le porteur, pas la porte.
 */
</script>

<template>
  <AppCard>
    <h1 class="text-heading">{{ $t('space.arbitration') }}</h1>
    <p class="mt-2 text-body text-text-muted">{{ $t('comingSoon.body') }}</p>
  </AppCard>
</template>
