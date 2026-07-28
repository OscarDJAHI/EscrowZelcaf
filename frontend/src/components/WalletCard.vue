<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import AppButton from '@/components/AppButton.vue'

/**
 * Carte wallet — composant de PRÉSENTATION, piloté uniquement par ses props.
 *
 * <p><b>Aucun store, aucun appel réseau, aucune dépendance au backend wallet.</b> Ce
 * n'est pas une commodité : le wallet relève d'AD-13 et de l'Epic 4, dont l'entrée est
 * verrouillée par la livraison de l'observabilité (AD-16). Brancher ce composant sur un
 * store aujourd'hui préempterait une décision d'architecture ouverte et créerait une
 * dépendance qu'il faudrait défaire.
 *
 * <p><b>Variante hors-ligne.</b> Le solde affiché est alors la DERNIÈRE VALEUR CONNUE,
 * horodatée, et les actions sont désactivées : proposer un dépôt sur un solde périmé
 * serait mentir sur ce que l'application peut faire.
 */
const props = defineProps({
  balance: { type: Number, default: null },
  currency: { type: String, default: 'USD' },
  /** Horodatage de la dernière valeur connue — requis en mode hors-ligne. */
  updatedAt: { type: String, default: null },
  offline: { type: Boolean, default: false },
  canDeposit: { type: Boolean, default: true },
  canWithdraw: { type: Boolean, default: true },
})

defineEmits(['deposit', 'withdraw'])

const { locale } = useI18n()

const formattedBalance = computed(() => {
  if (props.balance === null || props.balance === undefined) return null
  try {
    // Langue de l'APPLICATION, jamais celle du navigateur (leçon de la Story 2.1).
    return new Intl.NumberFormat(locale.value, {
      style: 'currency',
      currency: props.currency,
    }).format(props.balance)
  } catch {
    return `${props.balance} ${props.currency}`
  }
})

const formattedUpdatedAt = computed(() => {
  if (!props.updatedAt) return null
  try {
    return new Date(props.updatedAt).toLocaleString(locale.value)
  } catch {
    return null
  }
})

/** Hors ligne, aucune action n'est proposée, même si l'appelant les autorise. */
const depositEnabled = computed(() => props.canDeposit && !props.offline)
const withdrawEnabled = computed(() => props.canWithdraw && !props.offline)
</script>

<template>
  <section class="rounded-lg bg-surface-inverse p-4 text-text-on-inverse">
    <p class="text-caption">{{ $t('wallet.title') }}</p>

    <p v-if="formattedBalance" class="tabular-amount text-amount-hero">{{ formattedBalance }}</p>
    <p v-else class="text-amount-hero">—</p>

    <!-- Mention imposée par DESIGN.md : elle dit d'où viennent les fonds, ce qui est le
         cœur de la promesse de confiance de la plateforme. -->
    <p class="text-caption">{{ $t('wallet.segregated') }}</p>

    <p v-if="offline && formattedUpdatedAt" class="mt-2 text-caption">
      {{ $t('wallet.lastKnown', { at: formattedUpdatedAt }) }}
    </p>

    <div class="mt-4 flex gap-3">
      <AppButton
        label-key="wallet.deposit"
        variant="primary"
        :disabled="!depositEnabled"
        @click="$emit('deposit')"
      />
      <AppButton
        label-key="wallet.withdraw"
        variant="secondary"
        :disabled="!withdrawEnabled"
        @click="$emit('withdraw')"
      />
    </div>
  </section>
</template>
