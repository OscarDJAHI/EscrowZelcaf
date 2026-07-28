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
 *
 * <p><b>`type="button"` en mode interactif, explicitement.</b> Un `<button>` sans `type`
 * vaut `submit` : placée dans un formulaire, une carte cliquable le soumettrait au premier
 * clic. Sur un produit qui manipule de l'argent, c'est une action déclenchée par erreur —
 * et aucun écran ne l'utilise encore, donc rien ne l'aurait révélé avant le premier usage.
 */
defineProps({
  /** Rend la carte cliquable en la transformant en `<button>` plutôt qu'en `<div>`. */
  interactive: { type: Boolean, default: false },
})
</script>

<template>
  <component
    :is="interactive ? 'button' : 'div'"
    :type="interactive ? 'button' : undefined"
    class="block rounded-lg border border-border bg-surface-card p-4 text-left"
    :class="interactive ? 'w-full transition hover:border-primary' : ''"
  >
    <slot />
  </component>
</template>
