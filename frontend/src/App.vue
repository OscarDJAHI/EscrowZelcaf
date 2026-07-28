<script setup>
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import OnlineBanner from '@/components/OnlineBanner.vue'
import SyncFailureNotice from '@/components/SyncFailureNotice.vue'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import ClientShell from '@/layouts/ClientShell.vue'
import DesktopShell from '@/layouts/DesktopShell.vue'
import { DESKTOP_NAV, SPACES } from '@/router/spaces'

/**
 * Le shell se déduit de `meta.space`, ici, et JAMAIS écran par écran.
 *
 * <p>La tentation inverse — chaque vue importe son shell — se paie au deuxième écran :
 * une vue qui oublie l'import s'affiche sans navigation, donc sans issue, et rien ne le
 * signale puisque la page se rend parfaitement. Le défaut était déjà là : `ClientShell`
 * existait, ses tests passaient, et aucune des sept routes de l'espace client ne le
 * rendait. Résoudre le shell depuis la route rend l'oubli structurellement impossible.
 *
 * <p><b>Pas de shell pour `auth` ni pour le refus.</b> Pour la connexion c'est évident.
 * Pour `not-found`, c'est une exigence : envelopper un refus dans le shell d'un espace
 * dirait, par le seul chrome affiché, à quel espace appartient l'utilisateur — et le
 * distinguerait d'une adresse simplement inexistante. Même écran, même chrome, aucun
 * indice (NFR-P9).
 */
const route = useRoute()

const shell = computed(() => {
  if (route.meta.space === SPACES.CLIENT) return ClientShell
  return DESKTOP_NAV[route.meta.space] ? DesktopShell : null
})

const shellProps = computed(() => DESKTOP_NAV[route.meta.space] ?? {})
</script>

<template>
  <div class="flex min-h-screen flex-col bg-surface-page">
    <OnlineBanner />
    <!-- Outside the router on purpose: mounted in a view, the notice would
         vanish on the next navigation, and a definitive rejection must not be
         dismissible by accident. -->
    <SyncFailureNotice />

    <component :is="shell" v-if="shell" v-bind="shellProps">
      <RouterView />
    </component>

    <template v-else>
      <!-- Hors du routeur pour la même raison : la langue se change depuis N'IMPORTE
           quel écran, y compris celui d'authentification, où l'utilisateur n'a encore
           aucun profil dans lequel enregistrer une préférence. Les shells portent déjà
           leur propre sélecteur ; le répéter ici l'afficherait deux fois. -->
      <div class="flex justify-end px-4 pt-3">
        <LanguageSwitcher />
      </div>
      <RouterView />
    </template>
  </div>
</template>
