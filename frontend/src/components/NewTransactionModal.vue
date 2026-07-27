<script setup>
import { reactive, ref } from 'vue'

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
    localError.value = 'Please provide a valid seller email and a positive amount.'
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
    <div class="w-full max-w-md rounded-t-2xl bg-white p-6 shadow-xl sm:rounded-2xl">
      <div class="mb-4 flex items-center justify-between">
        <h2 class="text-lg font-semibold text-gray-900">New escrow transaction</h2>
        <button
          type="button"
          class="rounded p-1 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
          aria-label="Close"
          @click="emit('close')"
        >
          ✕
        </button>
      </div>

      <form class="space-y-4" @submit.prevent="handleSubmit">
        <div>
          <label class="block text-sm font-medium text-gray-700" for="sellerEmail">Seller email</label>
          <input
            id="sellerEmail"
            v-model="form.sellerEmail"
            type="email"
            required
            placeholder="seller@company.com"
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
          />
        </div>

        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-sm font-medium text-gray-700" for="amount">Amount</label>
            <input
              id="amount"
              v-model="form.amount"
              type="number"
              min="0"
              step="0.01"
              required
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            />
          </div>
          <div>
            <label class="block text-sm font-medium text-gray-700" for="currency">Currency</label>
            <select
              id="currency"
              v-model="form.currency"
              class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
            >
              <option v-for="currency in currencies" :key="currency" :value="currency">
                {{ currency }}
              </option>
            </select>
          </div>
        </div>

        <div>
          <label class="block text-sm font-medium text-gray-700" for="description">Description</label>
          <textarea
            id="description"
            v-model="form.description"
            rows="3"
            placeholder="Goods / service details"
            class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
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
            Cancel
          </button>
          <button
            type="submit"
            :disabled="submitting"
            class="flex-1 rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-60"
          >
            {{ submitting ? 'Creating…' : 'Create' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>
