<script setup lang="ts">
import AppCard from '@/components/AppCard.vue'

/**
 * Écran « à venir » — la contrepartie de l'interdiction des liens morts (AC2).
 *
 * <p>La navigation client expose cinq entrées dès maintenant, alors que Wallet, Support et
 * Profil relèvent d'epics ultérieurs. Deux mauvaises réponses étaient possibles : masquer
 * ces entrées (l'utilisateur ne saurait pas ce que la plateforme fera) ou les laisser
 * pointer dans le vide. Cet écran est la troisième : l'entrée existe, elle est honnête sur
 * son état, et elle ne ment pas en affichant une page vide qui ressemblerait à une panne.
 *
 * <p><b>Aucun `&lt;main&gt;` ici, ni largeur, ni gouttière.</b> Cette vue s'affiche DANS le
 * `&lt;main&gt;` du shell de son espace, que `App.vue` résout depuis `meta.space`. En porter
 * un second imbriquait deux landmarks — ce qu'un lecteur d'écran ne sait pas interpréter —
 * et empilait deux gouttières. La règle vaut pour toute vue routée dans un espace ;
 * `AccessDeniedView` est l'exception qui la confirme : le refus est servi SANS shell, donc
 * il porte sa propre enveloppe.
 */
defineProps({
  /** Clé i18n du nom de la surface annoncée. */
  titleKey: { type: String, required: true },
})
</script>

<template>
  <AppCard>
    <h1 class="text-heading">{{ $t(titleKey) }}</h1>
    <p class="mt-2 text-body text-text-muted">{{ $t('comingSoon.body') }}</p>
  </AppCard>
</template>
