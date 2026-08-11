<script setup lang="ts">
import { RouterLink } from 'vue-router'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import LogoutButton from '@/components/LogoutButton.vue'

/**
 * Shell de l'espace CLIENT — mobile-first, comme le prescrit `DESIGN.md`.
 *
 * <p>Cinq entrées, jamais moins : la navigation dit ce que la plateforme fait, y compris
 * pour les surfaces qu'un epic ultérieur livrera. Celles-ci mènent à un écran « à venir »
 * plutôt qu'à un lien mort — l'AC l'exige nommément.
 *
 * <p><b>Bascule responsive.</b> Onglets bas sous 768 px, où le pouce atteint le bas de
 * l'écran plus facilement que le haut ; barre latérale à partir de 1024 px. Entre les
 * deux, la navigation reste en haut et le contenu se limite à 720 px — au-delà, une ligne
 * de texte devient pénible à suivre.
 *
 * <p><b>Cloche de notifications.</b> Son emplacement est réservé et ANNONCÉ comme inactif
 * (`aria-disabled`), parce que l'Epic 8 livrera son contenu. Un bouton muet qui ne dit pas
 * qu'il est muet est pire qu'un emplacement vide : on clique, rien ne se passe, et on
 * croit à une panne.
 */
const NAV = [
  { name: 'dashboard', labelKey: 'nav.home' },
  { name: 'transactions', labelKey: 'nav.transactions' },
  { name: 'wallet', labelKey: 'nav.wallet' },
  { name: 'support', labelKey: 'nav.support' },
  { name: 'profile', labelKey: 'nav.profile' },
]

// 44 px de cible tactile et anneau de focus VISIBLE : sans le second, la navigation au
// clavier existe mais ne se voit pas, ce qui revient à ne pas exister.
const LINK =
  'flex min-h-[44px] flex-1 items-center justify-center rounded-md px-3 text-label ' +
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring ' +
  'lg:justify-start'
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <header class="flex items-center justify-between border-b border-border bg-surface-card px-4 py-2">
      <p class="text-label font-semibold">{{ $t('auth.brand') }}</p>
      <div class="flex items-center gap-2">
        <!-- Emplacement réservé : l'Epic 8 livrera la cloche active. -->
        <span
          class="flex min-h-[44px] min-w-[44px] items-center justify-center rounded-md text-text-muted"
          :aria-label="$t('nav.notifications')"
          aria-disabled="true"
          data-testid="notifications-slot"
        >
          •
        </span>
        <LanguageSwitcher />
        <!-- Dans le shell et non dans un écran : la déconnexion doit rester atteignable
             depuis les cinq surfaces de l'espace, pas seulement depuis l'accueil. -->
        <LogoutButton />
      </div>
    </header>

    <div class="flex flex-1 flex-col lg:flex-row">
      <nav
        class="order-last border-t border-border bg-surface-card lg:order-first lg:w-60 lg:border-r lg:border-t-0"
        :aria-label="$t('nav.primary')"
      >
        <ul class="flex lg:flex-col lg:gap-1 lg:p-2">
          <li v-for="entry in NAV" :key="entry.name" class="flex flex-1">
            <RouterLink :to="{ name: entry.name }" :class="LINK">{{ $t(entry.labelKey) }}</RouterLink>
          </li>
        </ul>
      </nav>

      <!-- 720 px pour les formulaires et détails, 960 px pour les listes financières. -->
      <main class="mx-auto w-full max-w-[720px] flex-1 p-4 lg:p-6 xl:max-w-[960px]">
        <slot />
      </main>
    </div>
  </div>
</template>
