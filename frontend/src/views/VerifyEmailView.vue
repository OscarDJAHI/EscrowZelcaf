<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'

/**
 * Saisie du code à 6 chiffres (Story 2.4, AC2/AC3/AC4).
 *
 * <p><b>L'adresse vient de l'URL, pas d'un état en mémoire.</b> C'est l'écran le plus
 * susceptible d'être rechargé — on quitte l'onglet pour aller chercher le code, et un
 * navigateur mobile n'hésite pas à décharger la page pendant ce temps. Un état de store
 * aurait disparu et l'utilisateur se serait retrouvé devant un formulaire orphelin, sans
 * moyen de dire de quel compte il parle.
 *
 * <p><b>Aucun secret dans l'URL.</b> Seule l'adresse y figure, jamais le code : les URL
 * partent dans l'historique, les journaux de serveur et l'en-tête `Referer`.
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const email = computed(() => (typeof route.query.email === 'string' ? route.query.email : ''))

const code = ref('')
const submitting = ref(false)
const cooldown = ref(0)
let ticker = null

const CODE_LENGTH = 6

/**
 * Ne garde que les chiffres, et tronque à six.
 *
 * <p>Sert AUTANT au collage qu'à la frappe (UX-DR39). Un code arrive presque toujours
 * collé depuis un e-mail, où il traîne des espaces, une espace insécable, parfois un
 * tiret — « 123 456 » doit fonctionner. Filtrer au collage seulement laisserait passer les
 * mêmes caractères saisis au clavier.
 */
function normalize(raw) {
  return String(raw ?? '')
    .replace(/\D/g, '')
    .slice(0, CODE_LENGTH)
}

function onInput(event) {
  code.value = normalize(event.target.value)
}

function stopTicker() {
  if (ticker) {
    clearInterval(ticker)
    ticker = null
  }
}

// Un minuteur qui survit à la vue continuerait de décrémenter dans le vide et retiendrait
// le composant en mémoire.
onUnmounted(stopTicker)

function startCooldown(seconds) {
  cooldown.value = seconds
  stopTicker()
  if (seconds <= 0) return
  ticker = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) stopTicker()
  }, 1000)
}

const canSubmit = computed(() => code.value.length === CODE_LENGTH && !submitting.value)

async function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  const ok = await auth.verify({ email: email.value, code: code.value })
  submitting.value = false
  // La saisie est CONSERVÉE en cas d'échec (UX-DR28) : la vider obligerait à retaper six
  // chiffres pour une faute sur un seul, et c'est exactement le moment où l'utilisateur
  // est déjà agacé.
  if (ok) router.replace({ name: 'dashboard' })
}

async function resend() {
  const { ok, retryAfterSeconds } = await auth.resend({ email: email.value })
  // Le délai vient du serveur (en-tête `Retry-After`). Un compte à rebours démarré
  // localement se remettrait à zéro en rechargeant la page — donc ne limiterait rien.
  startCooldown(ok ? 0 : retryAfterSeconds)
}
</script>

<template>
  <!-- GABARIT PORTÉ PAR LA VUE, parce que cet écran n'a pas de shell.
       `App.vue` ne résout un shell que depuis `meta.space` ; cette route est publique et
       n'appartient à aucun espace, elle ne reçoit donc ni gouttière, ni largeur maximale,
       ni `<main>`. La carte s'étalait sur toute la largeur, collée au bord haut. Les deux
       autres écrans sans shell (`AccessDeniedView`, `ComponentGalleryView`) portent déjà
       leur propre `<main>` pour cette raison ; celui-ci l'avait oublié.

       Largeur alignée sur la carte d'`AuthView` (`max-w-md`) : on arrive ici directement
       depuis l'inscription, et une carte qui change de gabarit entre deux étapes du même
       parcours se lit comme un changement d'application. -->
  <main class="mx-auto flex w-full max-w-md flex-1 items-center px-4 py-10">
    <AppCard>
      <h1 class="text-heading">{{ $t('verify.title') }}</h1>
      <p class="mt-2 text-body text-text-muted">{{ $t('verify.intro', { email }) }}</p>

      <form class="mt-4" @submit.prevent="submit">
        <label class="block text-label" for="otp">{{ $t('verify.codeLabel') }}</label>
        <input
          id="otp"
          :value="code"
          class="mt-1 block min-h-[44px] w-full rounded-md border border-border px-3 text-body tracking-[0.4em] focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-focus-ring"
          inputmode="numeric"
          autocomplete="one-time-code"
          :aria-describedby="auth.error ? 'otp-error' : undefined"
          :aria-invalid="auth.error ? 'true' : undefined"
          data-testid="otp-input"
          @input="onInput"
        />

        <!-- Sous le champ, et lié par `aria-describedby` : un message placé ailleurs est lu
             hors contexte par un lecteur d'écran, quand il est lu. -->
        <p v-if="auth.error" id="otp-error" class="mt-1 text-caption text-danger" data-testid="otp-error">
          {{ $t('verify.invalid') }}
        </p>

        <div class="mt-4 flex flex-wrap items-center gap-3">
          <AppButton type="submit" label-key="verify.submit" :disabled="!canSubmit" />
          <AppButton
            variant="ghost"
            label-key="verify.resend"
            :disabled="cooldown > 0"
            data-testid="otp-resend"
            @click="resend"
          />
          <!-- Le décompte est un TEXTE À CÔTÉ du bouton, pas dans son libellé : `AppButton`
               n'interpole aucun paramètre, et lui en ajouter pour ce seul écran modifierait
               un composant partagé de la Story 2.2 au bénéfice d'un cas unique.
               `aria-live` l'annonce sans voler le focus. -->
          <p
            v-if="cooldown > 0"
            class="text-caption text-text-muted"
            aria-live="polite"
            data-testid="otp-cooldown"
          >
            {{ $t('verify.resendIn', { seconds: cooldown }) }}
          </p>
        </div>
      </form>
    </AppCard>
  </main>
</template>
