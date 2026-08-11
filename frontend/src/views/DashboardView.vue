<script setup lang="ts">
import { roleLabel } from '@/i18n/labels'
import { useI18n } from 'vue-i18n'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { REVOCATION_WAIT_SECONDS, endSession } from '@/stores/session'
import TransactionCard from '@/components/TransactionCard.vue'
import NewTransactionModal from '@/components/NewTransactionModal.vue'
import { apiErrorMessage } from '@/utils/apiError'
import type { CreateTransactionPayload } from '@/api/escrow'

const { t, te } = useI18n()

const auth = useAuthStore()
const escrowStore = useEscrowStore()
const router = useRouter()

const showModal = ref(false)
const creating = ref(false)
const createError = ref('')

/**
 * La déconnexion est en cours — et elle est DITE (UX-DR26).
 *
 * <p>L'attente est désormais bornée (`REVOCATION_WAIT_SECONDS`), mais une attente bornée
 * reste une attente : entre le clic et la navigation, les stores sont déjà remis à zéro et
 * l'écran se vide. Sans état visible, l'utilisateur voit un tableau de bord qui perd son
 * contenu et un bouton qui ne réagit plus, sans savoir si son geste a été pris en compte.
 * Le libellé porte le motif ET le délai annoncé, comme UX-DR26 l'exige.
 */
const signingOut = ref(false)

const isBuyer = computed(() => auth.role === 'BUYER')

function refresh() {
  escrowStore.loadTransactions()
}

onMounted(() => {
  refresh()
  window.addEventListener('escrow:sync', refresh)
})
onBeforeUnmount(() => {
  window.removeEventListener('escrow:sync', refresh)
})

async function handleCreate(payload: CreateTransactionPayload) {
  creating.value = true
  createError.value = ''
  try {
    await escrowStore.createNewTransaction(payload)
    showModal.value = false
  } catch (err) {
    createError.value = apiErrorMessage(err) || t('dashboard.createFailed')
  } finally {
    creating.value = false
  }
}

async function logout() {
  // Plus de rechargement complet (Story 1.9) : il n'a jamais rien purgé d'IndexedDB
  // ni du cache de lecture — c'est `endSession` qui le fait, explicitement — et une
  // navigation de routeur rend le parcours testable.
  //
  // POURQUOI ON ATTEND ENCORE `endSession` (Story 2.7, AC4). La justification écrite ici
  // jusqu'à la 2.7 était PÉRIMÉE et elle est supprimée : elle disait que la navigation
  // avortait la requête en vol, ce qui était vrai de `window.location.href` et faux de
  // `router.replace`, une navigation SPA qui n'avorte rien. La raison qui reste est
  // double, et elle tient. D'abord `endSession` purge TOUT le local — mémoire, IndexedDB,
  // cache de lecture, marqueur — AVANT d'attendre le réseau : ne pas l'attendre ferait
  // naviguer sur un appareil qui n'est pas encore propre. Ensuite l'attente est désormais
  // BORNÉE (`REVOCATION_WAIT_SECONDS`, `stores/session.ts`) : ce qui a motivé ce report au
  // ledger — un portail captif retenant la navigation aussi longtemps qu'il retient la
  // requête — n'existe plus. `keepalive` mène la révocation à terme après la navigation ;
  // c'est lui qui rend l'abandon de l'attente acceptable.
  //
  // UNE SEULE GARDE CONTRE LE SECOND CLIC, et c'est `:disabled` sur le bouton — pas une
  // condition de plus ici. La version précédente en portait deux, et la passe de mutation
  // a montré qu'AUCUNE mutation d'une seule ligne ne les distinguait : chacune rattrapait
  // le cas de l'autre, si bien que la condition du gestionnaire était du code que la suite
  // ne pouvait pas falsifier. Une garde improuvable en double d'une garde prouvée n'est pas
  // de la défense en profondeur, c'est une preuve creuse en attente d'être citée.
  signingOut.value = true
  try {
    await endSession({ reason: 'logout' })
    try {
      await router.replace({ name: 'auth' })
    } catch (err) {
      // Toutes les routes sont des chunks paresseux : `replace` rejette si celui
      // d'`AuthView` ne se charge pas (déploiement qui invalide le nom haché,
      // précache évincé hors ligne). La session vient d'être détruite — rester ici
      // laisserait l'utilisateur sur un tableau de bord vide sans jeton et sans
      // issue, et le rejet s'échapperait d'un gestionnaire de clic `async`. Un
      // rechargement complet est le filet : il repart du serveur et la garde de
      // routeur renverra sur `/auth`.
      console.error('[dashboard] navigation vers la connexion impossible, rechargement', err)
      window.location.assign('/auth')
    }
  } finally {
    // Dans un `finally` et non après la navigation : sur le chemin du filet ci-dessus, la
    // vue n'est PAS démontée — un rechargement complet met du temps à venir, et un bouton
    // resté figé sur « déconnexion en cours » y mentirait indéfiniment.
    signingOut.value = false
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
    <div class="mb-6 flex items-start justify-between gap-3">
      <div>
        <h1 class="text-xl font-bold text-gray-900">{{ $t('dashboard.myTransactions') }}</h1>
        <p class="text-sm text-gray-500">
          {{ auth.user?.email }} ·
          <span class="font-medium">{{ roleLabel({ t, te }, auth.role) }}</span>
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <button
          v-if="isBuyer"
          class="rounded-lg bg-primary px-4 py-2 text-sm font-semibold text-white hover:bg-primary-hover"
          @click="showModal = true"
        >
          {{ $t('dashboard.newTransaction') }}
        </button>
        <button
          data-testid="logout-button"
          class="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-600 hover:bg-gray-50 disabled:opacity-60"
          :disabled="signingOut"
          :aria-busy="signingOut"
          @click="logout"
        >
          {{
            signingOut
              ? $t('common.loggingOut', { seconds: REVOCATION_WAIT_SECONDS })
              : $t('common.logout')
          }}
        </button>
      </div>
    </div>

    <div v-if="escrowStore.loading" class="py-16 text-center text-sm text-gray-400">
      {{ $t('dashboard.loading') }}
    </div>
    <div v-else-if="escrowStore.error" class="rounded-lg bg-red-50 p-4 text-sm text-red-700">
      {{ escrowStore.error }}
    </div>
    <div
      v-else-if="escrowStore.transactions.length === 0"
      class="rounded-xl border border-dashed border-gray-300 py-16 text-center text-sm text-gray-400"
    >
      {{ $t('dashboard.empty') }}
      <span v-if="isBuyer">{{ $t('dashboard.createFirst') }}</span>
    </div>
    <div v-else class="grid grid-cols-1 gap-4 sm:grid-cols-2">
      <TransactionCard v-for="tx in escrowStore.transactions" :key="tx.id" :transaction="tx" />
    </div>

    <NewTransactionModal
      v-if="showModal"
      :submitting="creating"
      :error-message="createError"
      @close="showModal = false"
      @submit="handleCreate"
    />
  </div>
</template>
