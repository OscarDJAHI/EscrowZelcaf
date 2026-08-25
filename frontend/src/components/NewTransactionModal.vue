<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { reactive, ref } from 'vue'

const { t } = useI18n()

// Pas de `const props =` : les deux props ne sont lues que par le gabarit, où
// elles sont dans la portée sans liaison. Nommer le résultat laissait une
// variable morte que rien ne signalait.
defineProps({
  submitting: { type: Boolean, default: false },
  errorMessage: { type: String, default: '' },
})

const emit = defineEmits(['close', 'submit'])

const currencies = ['USD', 'EUR', 'KES', 'ZAR', 'NGN']

const form = reactive({
  sellerEmail: '',
  amount: '',
  currency: 'USD',
  description: '',
})

const localError = ref('')

function handleSubmit() {
  localError.value = ''
  const amount = Number(form.amount)
  if (!form.sellerEmail || !amount || amount <= 0) {
    localError.value = t('newTransaction.invalid')
    return
  }
  emit('submit', {
    sellerEmail: form.sellerEmail.trim(),
    amount,
    currency: form.currency,
    description: form.description.trim(),
  })
}
</script>

<template>
  <div
    class="fixed inset-0 z-50 flex items-end justify-center bg-black/40 sm:items-center sm:p-4"
    @click.self="emit('close')"
  >
    <div class="w-full max-w-md rounded-t-2xl bg-white p-6 shadow-floating sm:rounded-2xl">
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-lg font-semibold text-gray-900">{{ $t('newTransaction.title') }}</h2>
        <button
          type="button"
          class="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
          :aria-label="$t('common.close')"
          @click="emit('close')"
        >
          ✕
        </button>
      </div>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div>
          <label class="block text-sm font-medium text-gray-700" for="sellerEmail">{{ $t('newTransaction.sellerEmail') }}</label>
          <input
            id="sellerEmail"
            v-model="form.sellerEmail"
            type="email"
            required
            :placeholder="$t('newTransaction.sellerEmailPlaceholder')"
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
          />
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium text-gray-700" for="amount">{{ $t('newTransaction.amount') }}</label>
            <input
              id="amount"
              v-model="form.amount"
              type="number"
              min="0"
              step="0.01"
              required
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700" for="currency">{{ $t('newTransaction.currency') }}</label>
            <select
              id="currency"
              v-model="form.currency"
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
            >
              <option v-for="currency in currencies" :key="currency" :value="currency">
                {{ currency }}
              </option>
            </select>
          </div>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700" for="description">{{ $t('newTransaction.description') }}</label>
          <textarea
            id="description"
            v-model="form.description"
            rows="3"
            :placeholder="$t('newTransaction.descriptionPlaceholder')"
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-focus-ring focus:outline-none focus:ring-1 focus:ring-focus-ring"
          />
        </div>

        <p v-if="localError || errorMessage" class="text-sm text-red-600">
          {{ localError || errorMessage }}
        </p>

        <div class="flex gap-3 pt-2">
          <button
            type="button"
            class="flex-1 rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
            @click="emit('close')"
          >
            {{ $t('common.cancel') }}
          </button>
          <button
            type="submit"
            :disabled="submitting"
            class="flex-1 rounded-lg bg-primary px-4 py-2 text-sm font-medium text-white hover:bg-primary-hover disabled:opacity-60"
          >
            {{ submitting ? $t('common.creating') : $t('common.create') }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
