<script setup>
import { computed } from 'vue'

/**
 * Squelette de chargement, calqué sur la mise en page de la surface qu'il remplace.
 *
 * <p><b>Pourquoi calqué et non générique.</b> Un squelette dont la forme diffère de la
 * surface finale provoque un saut de mise en page à l'arrivée des données : le contenu
 * bouge sous le doigt de l'utilisateur, qui peut cliquer sur autre chose que ce qu'il
 * visait. Un squelette est une promesse de forme.
 *
 * <p><b>Règle appliquée ici (DESIGN.md) :</b> jamais de spinner plein écran après la
 * première peinture. Toute attente porte une couleur et un libellé — d'où `labelKey`,
 * lu par les technologies d'assistance, qui n'ont rien à faire d'une animation.
 */
const props = defineProps({
  variant: {
    type: String,
    default: 'line',
    validator: (v) => ['line', 'card', 'wallet'].includes(v),
  },
  /** Nombre de squelettes à empiler (liste). */
  count: { type: Number, default: 1 },
  /** Clé i18n annonçant l'attente aux lecteurs d'écran. */
  labelKey: { type: String, default: 'common.pleaseWait' },
})

/** Classes en toutes lettres : l'extraction Tailwind est statique. */
const SHAPES = {
  line: 'h-4 rounded-md',
  card: 'h-28 rounded-lg',
  wallet: 'h-36 rounded-lg',
}

const shape = computed(() => SHAPES[props.variant] ?? SHAPES.line)
const items = computed(() => Array.from({ length: Math.max(1, props.count) }, (unused, i) => i))
</script>

<template>
  <div role="status" :aria-label="$t(labelKey)" class="flex flex-col gap-3">
    <div
      v-for="item in items"
      :key="item"
      class="animate-pulse bg-neutral-surface"
      :class="shape"
    />
  </div>
</template>
