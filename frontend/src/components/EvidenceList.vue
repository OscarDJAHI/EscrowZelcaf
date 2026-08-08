<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { computed, ref } from 'vue'
import type { PropType } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useEvidenceStore } from '@/stores/evidence'
import { formatBytes, uploaderLabel } from '@/utils/evidence'
import { apiErrorMessage } from '@/utils/apiError'
import type { EvidenceItem } from '@/types/domain'

const { t, locale } = useI18n()

const props = defineProps({
  transactionId: { type: [String, Number] as PropType<string | number>, required: true },
})

const auth = useAuthStore()
const evidenceStore = useEvidenceStore()

const downloadingId = ref<number | null>(null)
const downloadError = ref('')
const withdrawingId = ref<number | null>(null)
const withdrawError = ref('')

const items = computed(() => evidenceStore.items)

/** Withdraw is only offered on the current user's own active evidence. */
function canWithdraw(item: EvidenceItem) {
  return item.status === 'ACTIVE' && item.uploadedByUserId === auth.user?.id
}

function deposedBy(item: EvidenceItem) {
  return uploaderLabel(item, auth.user?.id)
}

/** Extracts the server message even when the failed response body is a Blob
 * (the download uses responseType: 'blob', so error envelopes arrive as Blobs). */
async function errorMessage(err: unknown, fallback: string): Promise<string> {
  const data = (err as { response?: { data?: unknown } })?.response?.data
  if (data instanceof Blob) {
    try {
      const parsed: unknown = JSON.parse(await data.text())
      const message = (parsed as { message?: unknown })?.message
      return typeof message === 'string' ? message : fallback
    } catch {
      return fallback
    }
  }
  // Hors du cas Blob, la lecture d'enveloppe est celle de tout le reste de
  // l'application : une seule implémentation, pas une deuxième qui dériverait.
  return apiErrorMessage(err) || fallback
}

function formatTimestamp(value: string | null | undefined): string {
  if (!value) return ''
  try {
    return new Date(value).toLocaleString(locale.value)
  } catch {
    return value
  }
}

function statusClasses(status: string) {
  return status === 'WITHDRAWN'
    ? 'bg-gray-100 text-gray-600'
    : 'bg-green-100 text-green-700'
}

function statusLabel(status: string) {
  return status === 'WITHDRAWN' ? t('evidence.statusWithdrawn') : t('evidence.statusActive')
}

async function download(item: EvidenceItem) {
  downloadError.value = ''
  downloadingId.value = item.id
  try {
    await evidenceStore.downloadFile(props.transactionId, item)
  } catch (err) {
    downloadError.value = await errorMessage(err, t('evidence.downloadFailed'))
  } finally {
    downloadingId.value = null
  }
}

async function withdraw(item: EvidenceItem) {
  withdrawError.value = ''
  withdrawingId.value = item.id
  try {
    await evidenceStore.withdrawEvidence(props.transactionId, item.id)
  } catch (err) {
    withdrawError.value = await errorMessage(err, t('evidence.withdrawFailed'))
  } finally {
    withdrawingId.value = null
  }
}
</script>

<template>
  <div>
    <p v-if="evidenceStore.error" class="text-sm text-red-600">{{ evidenceStore.error }}</p>
    <p v-if="downloadError" class="mb-3 text-sm text-red-600">{{ downloadError }}</p>
    <p v-if="withdrawError" class="mb-3 text-sm text-red-600">{{ withdrawError }}</p>

    <p
      v-if="!evidenceStore.loading && items.length === 0"
      class="text-sm text-gray-400"
    >
      {{ $t('evidence.empty') }}
    </p>

    <ul v-else class="space-y-3">
      <li
        v-for="item in items"
        :key="item.id"
        class="rounded-xl border border-gray-200 p-4"
      >
        <div class="flex flex-wrap items-start justify-between gap-3">
          <div class="min-w-0">
            <p class="text-sm font-semibold text-gray-900">
              {{ deposedBy(item) }}
              <span class="ml-2 font-normal text-gray-400">{{ formatTimestamp(item.createdAt) }}</span>
            </p>
            <p class="mt-1 truncate text-sm text-gray-700">{{ item.originalFilename }}</p>
            <p class="mt-0.5 text-xs text-gray-500">
              {{ item.mimeType }} · {{ formatBytes(item.sizeBytes) }}
            </p>
            <p v-if="item.comment" class="mt-2 text-sm text-gray-600">{{ item.comment }}</p>
          </div>
          <span
            class="inline-flex shrink-0 items-center rounded-full px-2.5 py-1 text-xs font-semibold"
            :class="statusClasses(item.status)"
          >
            {{ statusLabel(item.status) }}
          </span>
        </div>

        <div class="mt-3 flex justify-end gap-2">
          <button
            v-if="canWithdraw(item)"
            type="button"
            :disabled="withdrawingId === item.id"
            class="rounded-lg border border-red-300 px-3 py-1.5 text-sm font-medium text-red-700 hover:bg-red-50 disabled:opacity-60"
            @click="withdraw(item)"
          >
            {{ withdrawingId === item.id ? t('evidence.withdrawing') : t('evidence.withdraw') }}
          </button>
          <button
            type="button"
            :disabled="downloadingId === item.id"
            class="rounded-lg border border-gray-300 px-3 py-1.5 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-60"
            @click="download(item)"
          >
            {{ downloadingId === item.id ? t('common.downloading') : t('common.download') }}
          </button>
        </div>
      </li>
    </ul>
  </div>
</template>
