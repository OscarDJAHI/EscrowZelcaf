<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { translateOrHumanize } from '@/i18n/labels'

/**
 * Bouton de la plateforme — quatre variantes, toutes construites sur les tokens de la
 * Story 2.1. Aucune couleur brute, aucune ombre : un bouton n'est pas une surface
 * flottante, et `--shadow-floating` est réservée aux menus, toasts et modales.
 *
 * <p><b>Un seul primaire par surface.</b> La variante `primary` désigne l'action que
 * l'écran attend ; en afficher deux revient à n'en désigner aucune. La règle est
 * démontrée dans la page de composants.
 *
 * <p><b>Libellé porteur de montant.</b> Pour une confirmation financière, l'utilisateur
 * doit lire la somme qu'il engage AVANT de cliquer, pas la découvrir après. Le montant
 * est formaté dans la langue ACTIVE de l'application et rendu en chiffres tabulaires.
 */
const props = defineProps({
  /** Clé i18n du libellé. Jamais une chaîne : les littéraux sont interdits par la garde. */
  labelKey: { type: String, required: true },
  variant: {
    type: String,
    default: 'primary',
    validator: (v) => ['primary', 'danger', 'secondary', 'ghost'].includes(v),
  },
  /** Montant à faire figurer dans le libellé (confirmations financières). */
  amount: { type: Number, default: null },
  currency: { type: String, default: 'USD' },
  disabled: { type: Boolean, default: false },
  /** Action en cours : désactive ET remplace le libellé. Jamais un spinner muet. */
  pending: { type: Boolean, default: false },
  pendingLabelKey: { type: String, default: 'common.pleaseWait' },
  type: { type: String, default: 'button' },
})

const emit = defineEmits(['click'])

const { t, te, locale } = useI18n()

/**
 * Classes écrites en TOUTES LETTRES et jamais composées : Tailwind extrait par analyse
 * statique et ne verrait pas une classe construite à l'exécution — la feuille de style
 * sortirait sans elle et le rendu serait muet, sans erreur.
 */
const VARIANTS = {
  primary: 'bg-primary text-primary-foreground hover:bg-primary-hover',
  danger: 'bg-danger text-primary-foreground hover:bg-danger',
  secondary: 'border border-brand-navy text-brand-navy hover:bg-surface-page',
  ghost: 'text-brand-navy hover:bg-surface-page',
}

const classes = computed(() => [
  // 44 px : cible tactile minimale de DESIGN.md, sur TOUTES les variantes — l'application
  // est utilisée d'abord au pouce, sur mobile.
  'inline-flex min-h-[44px] items-center justify-center gap-2 rounded-md px-4 text-label',
  'font-medium transition disabled:opacity-60',
  VARIANTS[props.variant] ?? VARIANTS.primary,
])

const formattedAmount = computed(() => {
  if (props.amount === null || props.amount === undefined) return null
  try {
    // Langue de l'APPLICATION : `undefined` suivrait celle du navigateur, et basculer
    // l'interface en français ne changerait alors rien au montant (leçon de la 2.1).
    return new Intl.NumberFormat(locale.value, {
      style: 'currency',
      currency: props.currency,
    }).format(props.amount)
  } catch {
    return `${props.amount} ${props.currency}`
  }
})

const label = computed(() =>
  props.pending
    ? translateOrHumanize({ t, te }, props.pendingLabelKey)
    : translateOrHumanize({ t, te }, props.labelKey),
)

function onClick(event) {
  if (props.disabled || props.pending) return
  emit('click', event)
}
</script>

<template>
  <button :type="type" :class="classes" :disabled="disabled || pending" @click="onClick">
    <span>{{ label }}</span>
    <span v-if="formattedAmount && !pending" class="tabular-amount">— {{ formattedAmount }}</span>
  </button>
</template>
