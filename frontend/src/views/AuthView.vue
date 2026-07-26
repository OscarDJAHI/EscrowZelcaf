<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

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
 */
function safeRedirect(target) {
  return typeof target === 'string' && target.startsWith('/') && !target.startsWith('//')
    ? target
    : '/'
}

const mode = ref('login')

const form = reactive({
  email: '',
  password: '',
  firstName: '',
  lastName: '',
  role: 'BUYER',
})

const submitting = ref(false)

function setMode(next) {
  mode.value = next
  auth.error = null
}

async function handleSubmit() {
  submitting.value = true
  const ok =
    mode.value === 'login'
      ? await auth.login({ email: form.email, password: form.password })
      : await auth.register({ ...form })
  submitting.value = false
  // `replace`, not `push`: the sign-in screen has no business in the history of
  // a signed-in user, where Back would land them on it only to be bounced by the
  // route guard.
  if (ok) router.replace(safeRedirect(route.query.redirect))
}
</script>

<template>
  <div class="flex flex-1 items-center justify-center bg-gradient-to-b from-brand-50 to-white px-4 py-10">
    <div class="w-full max-w-md rounded-2xl bg-white p-8 shadow-lg">
      <div class="mb-6 text-center">
        <div
          class="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-600 text-xl font-bold text-white"
        >
          Z
        </div>
        <h1 class="text-2xl font-bold text-gray-900">ZLECAf Escrow</h1>
        <p class="mt-1 text-sm text-gray-500">Secure B2B trade across Africa</p>
      </div>

      <div class="mb-6 flex rounded-lg bg-gray-100 p-1 text-sm font-medium">
        <button
          type="button"
          class="flex-1 rounded-md py-2 transition"
          :class="mode === 'login' ? 'bg-white text-brand-700 shadow' : 'text-gray-500'"
          @click="setMode('login')"
        >
          Login
        </button>
        <button
          type="button"
          class="flex-1 rounded-md py-2 transition"
          :class="mode === 'register' ? 'bg-white text-brand-700 shadow' : 'text-gray-500'"
          @click="setMode('register')"
        >
          Register
        </button>
      </div>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <template v-if="mode === 'register'">
          <div class="grid grid-cols-2 gap-3">
            <div>
              <label class="block text-sm font-medium text-gray-700" for="firstName">First name</label>
              <input
                id="firstName"
                v-model="form.firstName"
                required
                class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
              />
            </div>
            <div>
              <label class="block text-sm font-medium text-gray-700" for="lastName">Last name</label>
              <input
                id="lastName"
                v-model="form.lastName"
                required
                class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
              />
            </div>
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700" for="role">Role</label>
            <select
              id="role"
              v-model="form.role"
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            >
              <option value="BUYER">Buyer</option>
              <option value="SELLER">Seller</option>
            </select>
          </div>
        </template>

        <div>
          <label class="block text-sm font-medium text-gray-700" for="email">Email</label>
          <input
            id="email"
            v-model="form.email"
            type="email"
            required
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>
        <div>
          <label class="block text-sm font-medium text-gray-700" for="password">Password</label>
          <input
            id="password"
            v-model="form.password"
            type="password"
            required
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>

        <p v-if="auth.error" class="text-sm text-red-600">{{ auth.error }}</p>

        <button
          type="submit"
          :disabled="submitting"
          class="w-full rounded-lg bg-brand-600 px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-brand-700 disabled:opacity-60"
        >
          {{ submitting ? 'Please wait…' : mode === 'login' ? 'Log in' : 'Create account' }}
        </button>
      </form>
    </div>
  </div>
</template>
