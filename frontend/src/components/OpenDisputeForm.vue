<script setup>
import { computed, ref } from 'vue'
import { useEscrowStore } from '@/stores/escrow'
import { formatBytes, validateFile } from '@/utils/evidence'

const props = defineProps({
  transactionId: { type: [String, Number], required: true },
})

const emit = defineEmits(['opened', 'cancel'])

const escrowStore = useEscrowStore()

const selectedFiles = ref([])
const comment = ref('')
const validationError = ref('')
const serverError = ref('')
// The escrow store has no `uploading` flag, so track submission locally.
const submitting = ref(false)

const canSubmit = computed(
  () =>
    selectedFiles.value.length > 0 &&
    comment.value.trim().length >= 10 &&
    !validationError.value &&
    !submitting.value,
)

function onFilesSelected(event) {
  serverError.value = ''
  validationError.value = ''
  const files = Array.from(event.target.files || [])
  selectedFiles.value = files
  for (const file of files) {
    const message = validateFile(file)
    if (message) {
      validationError.value = message
      break
    }
  }
}

async function handleSubmit() {
  serverError.value = ''
  if (!canSubmit.value) return
  submitting.value = true
  try {
    await escrowStore.openDispute(props.transactionId, {
      files: selectedFiles.value,
      comment: comment.value.trim(),
    })
    emit('opened')
  } catch (err) {
    // Keep the form intact so the user can retry; show the server message.
    serverError.value = err.response?.data?.message || 'Unable to open the dispute. Please try again.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <form class="space-y-4" @submit.prevent="handleSubmit">
    <div>
      <label class="block text-sm font-medium text-gray-700" for="dispute-files">
        Evidence files (JPG, PNG or PDF, up to 10 MB each)
      </label>
      <input
        id="dispute-files"
        type="file"
        accept=".jpg,.jpeg,.png,.pdf"
        multiple
        class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
        @change="onFilesSelected"
      />
    </div>

    <ul v-if="selectedFiles.length" class="space-y-1">
      <li
        v-for="(file, index) in selectedFiles"
        :key="`${file.name}-${file.size}-${index}`"
        class="text-xs text-gray-500"
      >
        {{ file.name }} · {{ file.type || 'unknown' }} · {{ formatBytes(file.size) }}
      </li>
    </ul>

    <div>
      <label class="block text-sm font-medium text-gray-700" for="dispute-comment">
        Comment (required)
      </label>
      <textarea
        id="dispute-comment"
        v-model="comment"
        rows="3"
        placeholder="Explain why you are opening this dispute (at least 10 characters)"
        class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
      />
    </div>

    <p class="text-xs text-gray-500">
      Opening a dispute requires at least one file and a comment of 10 characters or more.
    </p>

    <p v-if="validationError || serverError" class="text-sm text-red-600">
      {{ validationError || serverError }}
    </p>

    <div class="flex justify-end gap-3">
      <button
        type="button"
        class="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
        @click="emit('cancel')"
      >
        Cancel
      </button>
      <button
        type="submit"
        :disabled="!canSubmit"
        class="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white hover:bg-red-700 disabled:opacity-60"
      >
        {{ submitting ? 'Opening…' : 'Open dispute' }}
      </button>
    </div>
  </form>
</template>
