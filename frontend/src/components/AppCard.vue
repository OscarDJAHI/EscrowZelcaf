<script setup>
/**
 * Carte standard — la surface de base de toute la plateforme.
 *
 * <p>Bordée et SANS OMBRE au repos : la hiérarchie vient du contraste entre
 * `surface-page` et `surface-card`, jamais d'une élévation. `--shadow-floating` existe,
 * mais elle est réservée aux surfaces réellement flottantes (menus, toasts, modales).
 * Cette règle de `DESIGN.md` n'était appliquée nulle part avant cette story : la
 * Story 2.1 l'a relevée et reportée ici.
 *
 * <p>Rayon `lg` et padding 16 — carte, pas champ (`md`), pas pilule (`full`).
 */
defineProps({
  /** Rend la carte cliquable en la transformant en `<button>` plutôt qu'en `<div>`. */
  interactive: { type: Boolean, default: false },
})
</script>

<template>
  <component
    :is="interactive ? 'button' : 'div'"
    class="block rounded-lg border border-border bg-surface-card p-4 text-left"
    :class="interactive ? 'w-full transition hover:border-primary' : ''"
  >
    <slot />
  </component>
</template>
