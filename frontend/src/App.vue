<script setup>
import { RouterView } from 'vue-router'
import OnlineBanner from '@/components/OnlineBanner.vue'
import SyncFailureNotice from '@/components/SyncFailureNotice.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
</script>

<template>
  <div class="flex min-h-screen flex-col bg-surface-page">
    <OnlineBanner />
    <!-- Outside the router on purpose: mounted in a view, the notice would
         vanish on the next navigation, and a definitive rejection must not be
         dismissible by accident. -->
    <SyncFailureNotice />
    <!-- Hors du routeur pour la même raison : la langue se change depuis N'IMPORTE
         quel écran, y compris celui d'authentification, où l'utilisateur n'a encore
         aucun profil dans lequel enregistrer une préférence. -->
    <div class="flex justify-end px-4 pt-3">
      <LanguageSwitcher />
    </div>
    <RouterView />
  </div>
</template>
