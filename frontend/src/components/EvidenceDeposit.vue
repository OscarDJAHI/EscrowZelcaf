<script setup>
import { computed, ref } from 'vue'
import { useEvidenceStore } from '@/stores/evidence'
import { formatBytes, validateFile } from '@/utils/evidence'

const props = defineProps({
  transactionId: { type: [String, Number], required: true },
})

const emit = defineEmits(['uploaded'])

const evidenceStore = useEvidenceStore()

const fileInput = ref(null)
const selectedFiles = ref([])
const comment = ref('')
const validationError = ref('')
const serverError = ref('')

const uploading = computed(() => evidenceStore.uploading)

const canSubmit = computed(
  () => selectedFiles.value.length > 0 && !validationError.value && !uploading.value,
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

function resetForm() {
  selectedFiles.value = []
  comment.value = ''
  validationError.value = ''
  if (fileInput.value) fileInput.value.value = ''
}

async function handleSubmit() {
  serverError.value = ''
  if (!canSubmit.value) return
  try {
    // clientCapturedAt is intentionally omitted: for an online deposit the
    // browser has no true capture time, and sending the upload time would
    // record misleading audit metadata. Deferred capture belongs to Epic 4.
    await evidenceStore.uploadEvidence(props.transactionId, {
      files: selectedFiles.value,
      comment: comment.value.trim() || undefined,
    })
    resetForm()
    emit('uploaded')
  } catch (err) {
    serverError.value = err.response?.data?.message || 'Upload failed. Please try again.'
  }
}
</script>

<template>
  <form class="space-y-4" @submit.prevent="handleSubmit">
    <div>
      <label class="block text-sm font-medium text-gray-700" for="evidence-files">
        Files (JPG, PNG or PDF, up to 10 MB each)
      </label>
      <input
        id="evidence-files"
        ref="fileInput"
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
      <label class="block text-sm font-medium text-gray-700" for="evidence-comment">
        Comment (optional)
      </label>
      <textarea
        id="evidence-comment"
        v-model="comment"
        rows="2"
        placeholder="Add context for this evidence"
        class="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-brand-500 focus:outline-none focus:ring-1 focus:ring-brand-500"
      />
    </div>

    <p v-if="validationError || serverError" class="text-sm text-red-600">
      {{ validationError || serverError }}
    </p>

    <div class="flex justify-end">
      <button
        type="submit"
        :disabled="!canSubmit"
        class="rounded-lg bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-60"
      >
        {{ uploading ? 'Uploading…' : 'Upload evidence' }}
      </button>
    </div>
  </form>
</template>
