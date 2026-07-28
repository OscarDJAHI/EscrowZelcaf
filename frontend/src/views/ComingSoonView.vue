<script setup>
import AppCard from '@/components/AppCard.vue'

/**
 * Écran « à venir » — la contrepartie de l'interdiction des liens morts (AC2).
 *
 * <p>La navigation client expose cinq entrées dès maintenant, alors que Wallet, Support et
 * Profil relèvent d'epics ultérieurs. Deux mauvaises réponses étaient possibles : masquer
 * ces entrées (l'utilisateur ne saurait pas ce que la plateforme fera) ou les laisser
 * pointer dans le vide. Cet écran est la troisième : l'entrée existe, elle est honnête sur
 * son état, et elle ne ment pas en affichant une page vide qui ressemblerait à une panne.
 */
defineProps({
  /** Clé i18n du nom de la surface annoncée. */
  titleKey: { type: String, required: true },
})
</script>

<template>
  <main class="mx-auto w-full max-w-[720px] flex-1 p-4">
    <AppCard>
      <h1 class="text-heading">{{ $t(titleKey) }}</h1>
      <p class="mt-2 text-body text-text-muted">{{ $t('comingSoon.body') }}</p>
    </AppCard>
  </main>
</template>
