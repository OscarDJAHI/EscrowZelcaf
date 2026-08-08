<script setup lang="ts">
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'

/**
 * Shell des surfaces DESKTOP-FIRST : console d'arbitrage et back-office.
 *
 * <p>Ces deux espaces sont des postes de travail — files de dossiers, tableaux larges,
 * décisions motivées. Les servir en mobile serait leur promettre un usage qu'ils ne
 * tiennent pas, d'où le message explicite sous 768 px plutôt qu'une mise en page tassée
 * qui laisserait croire que tout est là.
 *
 * <p>Le message est rendu ET la surface reste accessible en dessous : on informe, on
 * n'interdit pas — un opérateur qui doit vérifier une information depuis son téléphone
 * n'est pas bloqué, il est prévenu.
 */
defineProps({
  titleKey: { type: String, required: true },
  navKeys: { type: Array, default: () => [] },
})
</script>

<template>
  <div class="flex min-h-screen flex-col">
    <p
      class="bg-warning-surface px-4 py-2 text-caption text-warning md:hidden"
      data-testid="desktop-only-warning"
    >
      {{ $t('shell.desktopOnly') }}
    </p>

    <div class="flex flex-1 flex-col md:flex-row">
      <nav
        class="border-border bg-surface-card md:w-60 md:border-r"
        :aria-label="$t(titleKey)"
      >
        <p class="p-4 text-label font-semibold">{{ $t(titleKey) }}</p>
        <ul class="flex flex-wrap gap-1 px-2 pb-2 md:flex-col">
          <li v-for="key in navKeys" :key="key">
            <span
              class="flex min-h-[44px] items-center rounded-md px-3 text-label text-text-muted"
            >
              {{ $t(key) }}
            </span>
          </li>
        </ul>
        <div class="p-4"><LanguageSwitcher /></div>
      </nav>

      <!-- 1280 px : largeur maximale des tableaux, DESIGN.md. -->
      <main class="mx-auto w-full max-w-[1280px] flex-1 p-4 lg:p-6">
        <slot />
      </main>
    </div>
  </div>
</template>
