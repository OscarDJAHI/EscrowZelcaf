<script setup lang="ts">
import { computed } from 'vue'
import type { PropType } from 'vue'
import { useI18n } from 'vue-i18n'
import { translateOrHumanize } from '@/i18n/labels'

/**
 * Les quatre variantes, en TYPE et non en constante.
 *
 * <p>La première version déclarait un `const VARIANT_NAMES` que le validateur lisait.
 * `defineProps()` est hissé hors de `setup()` par le compilateur de SFC : il ne peut donc
 * référencer aucune variable locale, et la compilation échouait. `vue-tsc` ne le voit pas
 * — seuls le build et la suite l'attrapent. Un `type` s'efface entièrement, il n'est pas
 * une variable, et la liste littérale reste dans le validateur où le compilateur l'admet.
 */
type Variant = 'primary' | 'danger' | 'secondary' | 'ghost'

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
  // `PropType` plutôt qu'un `defineProps<{...}>()` typé : la déclaration à l'exécution
  // porte le `validator`, qui avertit en développement quand une variante inconnue est
  // passée. Le passer en type seul aurait ÉCHANGÉ une garde d'exécution contre une garde
  // de compilation, alors que les deux se cumulent ici.
  variant: {
    type: String as PropType<Variant>,
    default: 'primary',
    validator: (v: unknown) => ['primary', 'danger', 'secondary', 'ghost'].includes(v as string),
  },
  /** Montant à faire figurer dans le libellé (confirmations financières). */
  amount: { type: Number, default: null },
  currency: { type: String, default: 'USD' },
  disabled: { type: Boolean, default: false },
  /** Action en cours : désactive ET remplace le libellé. Jamais un spinner muet. */
  pending: { type: Boolean, default: false },
  pendingLabelKey: { type: String, default: 'common.pleaseWait' },
  /**
   * Paramètres d'interpolation du libellé actif (Story 2.7).
   *
   * <p>Le MÊME objet sert au libellé et à son remplaçant d'attente : les deux décrivent la
   * même action, et un bouton dont l'attente parle d'autre chose que son intitulé serait
   * illisible. Les paramètres inutiles à l'un sont ignorés par vue-i18n.
   *
   * <p>Défaut `{}` — la 2.7 avait d'abord écrit `null` pour préserver un appel à un seul
   * argument chez les autres boutons ; la mutation de T10 a montré que rien ne dépendait de
   * cette distinction, et la complication a été retirée plutôt que gardée.
   */
  labelParams: { type: Object as PropType<Record<string, unknown>>, default: () => ({}) },
  type: {
    type: String as PropType<'button' | 'submit' | 'reset'>,
    default: 'button',
  },
})

const emit = defineEmits<{ click: [event: MouseEvent] }>()

const { t, te, locale } = useI18n()

/**
 * Classes écrites en TOUTES LETTRES et jamais composées : Tailwind extrait par analyse
 * statique et ne verrait pas une classe construite à l'exécution — la feuille de style
 * sortirait sans elle et le rendu serait muet, sans erreur.
 */
const VARIANTS: Record<Variant, string> = {
  primary: 'bg-primary text-primary-foreground hover:bg-primary-hover',
  // `hover:bg-danger` sur un fond déjà `bg-danger` ne changeait RIEN : le bouton le plus
  // dangereux de l'interface ne réagissait pas au pointeur. `DESIGN.md` définit
  // `primary-hover` mais AUCUN `danger-hover` — plutôt qu'inventer un token que le
  // contrat ne porte pas, le survol passe par l'opacité, qui n'engage aucune couleur.
  danger: 'bg-danger text-primary-foreground hover:opacity-90',
  secondary: 'border border-brand-navy text-brand-navy hover:bg-surface-page',
  ghost: 'text-brand-navy hover:bg-surface-page',
}

const classes = computed(() => [
  // 44 px : cible tactile minimale de DESIGN.md, sur TOUTES les variantes — l'application
  // est utilisée d'abord au pouce, sur mobile.
  'inline-flex min-h-[44px] items-center justify-center gap-2 rounded-md px-4 text-label',
  'font-medium transition disabled:opacity-60',
  // ANNEAU DE FOCUS (Story 2.7). Il manquait — sur les 28 composants, seuls `ClientShell`
  // et `VerifyEmailView` en portaient un, et `style.css` définit `--color-focus-ring` sans
  // aucune règle globale qui l'applique. Le bouton PARTAGÉ de la plateforme était donc
  // atteignable au clavier sans que le focus se voie, ce qui revient, pour qui n'utilise
  // pas de souris, à ne pas savoir où l'on est. Posé ici plutôt que sur le bouton de
  // déconnexion qui l'a révélé : le défaut n'était pas le sien, il était celui du gabarit.
  'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring',
  VARIANTS[props.variant] ?? VARIANTS.primary,
])

const formattedAmount = computed(() => {
  // `Number.isFinite` et non un simple test de nullité : `Intl.format(NaN)` ne LÈVE pas,
  // il rend « $NaN ». Le `try/catch` ci-dessous gardait donc contre une exception qui ne
  // vient jamais, pendant que le texte partait à l'écran (constat de revue).
  if (!Number.isFinite(props.amount)) return null
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
  translateOrHumanize(
    { t, te },
    props.pending ? props.pendingLabelKey : props.labelKey,
    props.labelParams,
  ),
)

function onClick(event: MouseEvent) {
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
