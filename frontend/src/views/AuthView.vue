<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { readPersistence, writePersistence } from '@/utils/credentialStorage'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

/**
 * Where to land once signed in. `redirect` comes off the URL, so it comes off
 * whoever wrote the link: without this guard `?redirect=//evil.example/x` turns
 * the sign-in screen into an open redirect, and a protocol-relative URL is
 * exactly the shape that slips past a naive "starts with /" check.
 *
 * Only a relative path is accepted, and anything else — an absolute URL, an
 * array (a repeated query parameter), a missing value — degrades to the
 * dashboard rather than being sanitised into something half-trusted.
 *
 * The backslash form is refused alongside `//` so that the code enforces what
 * this comment claims. `/\evil.example` passes a naive `startsWith('/')` and
 * fails `startsWith('//')`, yet browsers and several URL parsers treat `\` as
 * `/` — today vue-router happens to resolve it same-origin, so nothing escapes,
 * but that is an implementation detail of the consumer, not a property of the
 * guard. A guard whose safety depends on its caller is not a guard.
 *
 * Control characters are stripped BEFORE those tests for the same reason. Query
 * values reach us percent-decoded, so `?redirect=/%09/evil.example` arrives as
 * `/<TAB>/evil.example`: it passes `startsWith('/')`, fails `startsWith('//')`,
 * and is then read as `//evil.example` by every URL parser that follows the WHATWG
 * rule of discarding tabs and newlines. Removing them first means the string the
 * guard inspects is the string a parser will see.
 */
function safeRedirect(target: unknown): string {
  if (typeof target !== 'string') return '/'
  // The class below is spelled with `\u` ESCAPES and must stay that way. Written
  // with the literal bytes it matches — which is how it first shipped — the NUL
  // turns this file into a binary blob: `git diff` reports `- -` instead of a
  // patch, `grep -r` skips it without a word, and this story's own verification
  // greps over `frontend/src` then pass while blind to the sign-in screen. The
  // regex behaves identically either way; only the tooling can tell them apart,
  // and it is the tooling that will review the next change to this guard.
  // eslint-disable-next-line no-control-regex
  const cleaned = target.replace(/[\u0000-\u001f\u007f]/g, '')
  return cleaned.startsWith('/') && !cleaned.startsWith('//') && !cleaned.startsWith('/\\')
    ? cleaned
    : '/'
}

const mode = ref<'login' | 'register'>('login')

// `role` porte son union, et ce n'est pas cosmétique : `reactive` aurait inféré `string`,
// et la charge partait alors vers `register()` sans que rien ne vérifie que le rôle
// demandé est bien l'un des deux que l'inscription autorise. Le `<select>` du gabarit ne
// propose que ces deux valeurs — le type dit désormais la même chose que l'écran.
const form = reactive({
  email: '',
  password: '',
  firstName: '',
  lastName: '',
  role: 'BUYER' as 'BUYER' | 'SELLER',
  // Story 2.4 : raison sociale et consentement légal horodaté (FR-P27).
  companyName: '',
  consentAccepted: false,
})

/**
 * « Rester connecté » (Story 2.7, AC1) — hors de `form` délibérément.
 *
 * <p>`form` est la charge envoyée au serveur ; ceci est une préférence d'APPAREIL qui ne
 * quitte jamais le navigateur. Les mélanger finirait par l'expédier dans un corps de
 * requête, où elle n'a rien à faire.
 *
 * <p>Initialisée depuis la préférence déjà enregistrée : l'utilisateur d'un poste personnel
 * qui a coché la case une fois ne doit pas la re-cocher à chaque connexion, sinon l'option
 * ne tient pas la promesse qui justifie son existence.
 */
const rememberMe = ref(readPersistence() === 'local')

const submitting = ref(false)

function setMode(next: 'login' | 'register') {
  mode.value = next
  auth.error = null
}

async function handleSubmit() {
  submitting.value = true
  if (mode.value === 'login') {
    // AVANT `auth.login`, impérativement : c'est `login` qui écrit le jeton, et il l'écrit
    // dans le substrat que la préférence désigne AU MOMENT de l'écriture. Poser la
    // préférence après enverrait le jeton dans l'ancien substrat, puis déclarerait l'autre
    // actif — le jeton deviendrait illisible et la connexion échouerait sans erreur.
    writePersistence(rememberMe.value ? 'local' : 'session')
    const ok = await auth.login({ email: form.email, password: form.password })
    submitting.value = false
    // `replace`, not `push`: the sign-in screen has no business in the history of
    // a signed-in user, where Back would land them on it only to be bounced by the
    // route guard.
    if (ok) router.replace(safeRedirect(route.query.redirect))
    return
  }

  // INSCRIPTION (Story 2.4) : elle n'ouvre plus de session. Le serveur crée un compte non
  // vérifié et répond 202 sans corps — un succès ici veut dire « demande acceptée », pas
  // « connecté ». On mène donc vers la saisie du code, et non vers le tableau de bord.
  //
  // L'adresse passe par l'URL parce que cet écran-là sera rechargé : on quitte l'onglet
  // pour aller chercher le code dans sa boîte, et un navigateur mobile décharge volontiers
  // la page pendant ce temps. Le code, lui, ne voyage jamais dans une URL.
  const ok = await auth.register({ ...form })
  submitting.value = false
  if (ok) router.replace({ name: 'verify-email', query: { email: form.email } })
}
</script>

<template>
  <div class="flex flex-1 items-center justify-center bg-surface-page px-4 py-10">
    <div class="w-full max-w-md rounded-2xl bg-white p-8">
      <div class="mb-6 text-center">
        <div
          class="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-primary text-xl font-bold text-white"
        >
          Z
        </div>
        <h1 class="text-2xl font-bold text-gray-900">{{ $t('auth.brand') }}</h1>
        <p class="mt-1 text-sm text-gray-500">{{ $t('auth.tagline') }}</p>
      </div>

      <div class="mb-6 flex rounded-lg bg-gray-100 p-1 text-sm font-medium">
        <button
          type="button"
          class="flex-1 rounded-md py-2 transition"
          :class="mode === 'login' ? 'bg-white text-primary' : 'text-gray-500'"
          @click="setMode('login')"
        >
          {{ $t('auth.tabLogin') }}
        </button>
        <button
          type="button"
          class="flex-1 rounded-md py-2 transition"
          :class="mode === 'register' ? 'bg-white text-primary' : 'text-gray-500'"
          @click="setMode('register')"
        >
          {{ $t('auth.tabRegister') }}
        </button>
      </div>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <template v-if="mode === 'register'">
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700" for="firstName">{{ $t('auth.firstName') }}</label>
              <input
                id="firstName"
                v-model="form.firstName"
                required
                class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700" for="lastName">{{ $t('auth.lastName') }}</label>
              <input
                id="lastName"
                v-model="form.lastName"
                required
                class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
              />
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700" for="companyName">{{ $t('auth.companyName') }}</label>
            <input
              id="companyName"
              v-model="form.companyName"
              required
              data-testid="company-name"
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700" for="role">{{ $t('auth.role') }}</label>
            <select
              id="role"
              v-model="form.role"
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
            >
              <option value="BUYER">{{ $t('auth.roleBuyer') }}</option>
              <option value="SELLER">{{ $t('auth.roleSeller') }}</option>
            </select>
          </div>
        </template>

        <div>
          <label class="block text-sm font-medium text-gray-700" for="email">{{ $t('auth.email') }}</label>
          <input
            id="email"
            v-model="form.email"
            type="email"
            required
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700" for="password">{{ $t('auth.password') }}</label>
          <input
            id="password"
            v-model="form.password"
            type="password"
            required
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
          />
        </div>

        <!-- Le consentement se PROUVE, il ne se présume pas (FR-P27) : la case part
             décochée et `required` empêche l'envoi tant qu'elle l'est. Le serveur revérifie
             — une garde côté client seule ne prouve rien de ce qui est persisté. -->
        <label v-if="mode === 'register'" class="flex items-start gap-2 text-sm text-gray-700">
          <input
            v-model="form.consentAccepted"
            type="checkbox"
            required
            data-testid="consent"
            class="mt-1 min-h-[16px] min-w-[16px]"
          />
          <span>{{ $t('auth.consent') }}</span>
        </label>

        <!-- Story 2.7 (AC1) : DÉCOCHÉE par défaut, et c'est la décision D4 elle-même.
             Décochée, le jeton vit en sessionStorage et meurt avec l'onglet — ce qui ferme
             le mode de défaillance dominant de l'appareil partagé. Cochée, il passe en
             localStorage pour un appareil personnel. La case ne s'affiche qu'en connexion :
             l'inscription n'ouvre pas de session (c'est `verify` qui le fait, Story 2.4),
             donc l'offrir là promettrait un choix sans effet.
             `min-h-[44px]` : cible tactile de UX-DR38, sur le libellé entier. -->
        <label
          v-if="mode === 'login'"
          class="flex min-h-[44px] items-center gap-2 text-sm text-gray-700"
        >
          <input
            v-model="rememberMe"
            type="checkbox"
            data-testid="remember-me"
            class="min-h-[16px] min-w-[16px]"
          />
          <span>{{ $t('auth.rememberMe') }}</span>
        </label>

        <p v-if="auth.error" class="text-sm text-red-600">{{ auth.error }}</p>

        <button
          type="submit"
          :disabled="submitting"
          class="w-full rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-primary-hover disabled:opacity-60"
        >
          {{
            submitting
              ? $t('common.pleaseWait')
              : mode === 'login'
                ? $t('auth.tabLogin')
                : $t('auth.tabRegister')
          }}
        </button>
      </form>
    </div>
  </div>
</template>
