<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { stateLabel } from '@/i18n/labels'
import { STATE_COLORS } from '@/utils/stateMachine'
import type { EscrowState } from '@/types/domain'

const props = defineProps({
  // `String` et NON `PropType<EscrowState>`, après un aller-retour instructif : restreindre
  // au catalogue faisait échouer `StateBadge.spec.ts`, qui passe `EXPIRED` exprès. Ce test
  // a raison — la dégradation vers la famille neutre pour un état encore inconnu EST le
  // contrat du composant (`EXPIRED` arrive avec l'Epic 5). Un type qui interdit l'entrée
  // que le composant est fait pour absorber décrit un autre composant.
  state: { type: String, required: true },
})

const { t, te } = useI18n()

const classes = computed(
  () =>
    STATE_COLORS[props.state as EscrowState]?.badge || 'bg-neutral-surface text-neutral-state',
)
// Libellé i18n et non `state.replaceAll('_',' ')` : cette dernière forme affichait la
// chaîne MACHINE (`FUNDS LOCKED`) à l'utilisateur, quelle que soit la langue.
const label = computed(() => stateLabel({ t, te }, props.state))
</script>

<template>
  <span
    class="inline-flex shrink-0 items-center rounded-full px-2.5 py-1 text-xs font-semibold"
    :class="classes"
  >
    {{ label }}
  </span>
</template>
