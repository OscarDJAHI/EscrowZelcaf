<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from '@/components/AppButton.vue'
import AppCard from '@/components/AppCard.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import StateBadge from '@/components/StateBadge.vue'
import WalletCard from '@/components/WalletCard.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { ALL_STATES } from '@/utils/stateMachine'

/**
 * Page de démonstration interne — revue visuelle de la bibliothèque, sans dépendre
 * d'aucun écran métier.
 *
 * <p><b>Sentinelle de build.</b> L'attribut `data-gallery` du gabarit porte un marqueur
 * unique. Il est dans le GABARIT et non dans un commentaire, parce que la minification
 * supprime les commentaires : une première version de ce contrôle cherchait une chaîne
 * qui ne pouvait jamais exister dans le bundle, et passait donc toujours. Le contrôle
 * vérifie aussi qu'aucun fichier émis ne porte le nom de ce composant.
 *
 * <p>Une galerie livrée en production ne serait pas seulement du poids mort : elle
 * exposerait une surface non prévue par le modèle de menace.
 */
const { locale } = useI18n()

const VARIANTS = ['primary', 'danger', 'secondary', 'ghost']
const pending = ref(false)
</script>

<template>
  <main
    data-gallery="ESCROW_COMPONENT_GALLERY_DEV_ONLY"
    class="mx-auto flex w-full max-w-3xl flex-col gap-6 p-4"
  >
    <header class="flex items-center justify-between">
      <h1 class="text-display">{{ $t('demo.title') }}</h1>
      <LanguageSwitcher />
    </header>
    <p class="text-caption">{{ locale }}</p>

    <AppCard>
      <h2 class="text-heading">AppButton</h2>
      <!-- La règle « un seul primaire par surface » est ÉNONCÉE ici parce que c'est la
           seule chose qu'une bibliothèque ne peut pas imposer techniquement. -->
      <p class="text-caption">{{ $t('demo.onePrimary') }}</p>
      <div class="mt-3 flex flex-wrap gap-3">
        <AppButton v-for="v in VARIANTS" :key="v" :variant="v" label-key="common.create" />
      </div>
      <div class="mt-3 flex flex-wrap gap-3">
        <AppButton label-key="common.create" :amount="12500.5" currency="USD" />
        <AppButton label-key="common.create" disabled />
        <AppButton label-key="common.create" :pending="pending" @click="pending = !pending" />
      </div>
    </AppCard>

    <AppCard>
      <h2 class="text-heading">StateBadge</h2>
      <div class="mt-3 flex flex-wrap gap-2">
        <StateBadge v-for="s in ALL_STATES" :key="s" :state="s" />
        <StateBadge state="ETAT_INCONNU" />
      </div>
    </AppCard>

    <div class="flex flex-col gap-3">
      <h2 class="text-heading">WalletCard</h2>
      <WalletCard :balance="12500.5" currency="USD" />
      <WalletCard :balance="12500.5" currency="USD" offline updated-at="2026-07-28T08:00:00.000Z" />
    </div>

    <AppCard>
      <h2 class="text-heading">AppSkeleton</h2>
      <p class="text-caption">{{ $t('demo.noSpinner') }}</p>
      <div class="mt-3 flex flex-col gap-4">
        <AppSkeleton variant="line" :count="3" />
        <AppSkeleton variant="card" />
        <AppSkeleton variant="wallet" />
      </div>
    </AppCard>
  </main>
</template>
