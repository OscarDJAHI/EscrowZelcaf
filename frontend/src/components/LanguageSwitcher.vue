<script setup>
import { useI18n } from 'vue-i18n'
import { SUPPORTED_LOCALES, applyLocale } from '@/i18n'

/**
 * Sélecteur de langue « EN / FR ».
 *
 * <p>Libellés TEXTE et jamais de drapeaux : `DESIGN.md` l'interdit explicitement.
 * Un drapeau désigne un pays, pas une langue — le français n'appartient pas plus à
 * la France qu'aux corridors ouest-africains que cette plateforme sert.
 */
// `locale` vient de l'instance i18n RÉELLEMENT injectée dans ce composant — le
// singleton applicatif en production, une instance neuve sous test. Le sélecteur
// n'a donc aucune connaissance de l'instance globale.
const { locale } = useI18n()

function select(code) {
  applyLocale(locale, code)
}
</script>

<template>
  <div class="inline-flex items-center gap-1" role="group" :aria-label="$t('language.label')">
    <button
      v-for="code in SUPPORTED_LOCALES"
      :key="code"
      type="button"
      class="rounded-md px-2 py-1 text-label font-medium transition"
      :class="
        locale === code
          ? 'bg-surface-card text-primary shadow-floating'
          : 'text-text-muted hover:text-text-body'
      "
      :aria-pressed="locale === code"
      @click="select(code)"
    >
      {{ $t(`language.${code}`) }}
    </button>
  </div>
</template>
