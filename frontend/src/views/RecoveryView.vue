<script setup>
import { useI18n } from 'vue-i18n'
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import StateBadge from '@/components/StateBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import { describeFailure } from '@/utils/replayFailure'
import { describeAction, ownsEntry, resolveRealState } from '@/utils/frozenEntry'
import { saveBlob } from '@/utils/download'
import { formatBytes } from '@/utils/evidence'

const { t } = useI18n()

/**
 * Where a frozen entry finally becomes actionable: its files are handed back,
 * its reason and the transaction's real state are restated, and it can be
 * acknowledged — the only deletion path outside `flush()`, and the only way the
 * `role="alert"` band above every route ever goes away.
 *
 * Reads `frozenEntries`, never `queue`: an entry still waiting is not something
 * to recover, and removing it would cancel an action that can still succeed.
 *
 * Fetches no transaction state of its own, deliberately. A frozen entry is
 * typically consulted offline — that is the whole point of the epic — so a load
 * would fail exactly when the screen is needed; it would write
 * `escrowStore.error`, which `TransactionDetailView.vue:125-127` turns into a
 * `v-else-if` that *replaces* the page; and the freshness criterion would
 * degrade to a link anyway. The link is the honest answer: "I don't know, go and
 * see."
 *
 * That is a claim about *this* screen, not about the process: `hydrate()` calls
 * `queue.init()`, which replays any pending entry (`offlineQueue.js:108-110`
 * `flush()` → `apiClient.request`) exactly as `main.js:17` does at boot. Deep-
 * linking here can therefore emit the queue's own POSTs. That belongs to the
 * queue and is not this view reaching for the network — but the distinction is
 * worth stating rather than claiming a silence the code does not keep.
 */

const props = defineProps({
  entryId: { type: String, required: true },
})

const router = useRouter()
const auth = useAuthStore()
const escrow = useEscrowStore()
const queue = useOfflineQueueStore()
const { frozenEntries, hydrated } = storeToRefs(queue)
const { transactions, currentDetail, transactionsFetchedAt, currentDetailFetchedAt } =
  storeToRefs(escrow)

/**
 * Local, never in the store (convention of `TransactionDetailView`): all three
 * describe this screen's conversation with this user, not the queue's state.
 */
const confirming = ref(false)
const error = ref('')
const hydrationFailed = ref(false)
/** True between a successful delete and the navigation landing. See `acknowledge`. */
const leaving = ref(false)
/** Guards the in-flight delete against a second click. See `acknowledge`. */
const deleting = ref(false)

/**
 * `init()` swallows a storage failure — it logs and returns, leaving `hydrated`
 * false so a *later* call can retry (`offlineQueue.js:100-106`). But nothing
 * ever calls it again. Without the check below, an unavailable IndexedDB
 * (private browsing, storage blocked) would strand this page on "loading"
 * forever, and the only trace of the last copy of the user's files would be a
 * `console.error`: the epic's own failure mode, reproduced on the very screen
 * built to end it. A swallowed failure is not a loading state.
 *
 * The `await` delays no render — `onMounted` has already fired. What Story 4.4
 * forbade was holding the paint back, not awaiting an answer.
 */
async function hydrate() {
  error.value = ''
  hydrationFailed.value = false
  if (!queue.hydrated) {
    await queue.init()
    if (!queue.hydrated) hydrationFailed.value = true
  }
}

onMounted(hydrate)

/**
 * `/recovery/:entryId` is one route record, so walking from one entry to another
 * *reuses this component instance*: `onMounted` does not fire again and nothing
 * else would clear what follows. That is not a hypothetical — `App.vue` mounts
 * `SyncFailureNotice` above `RouterView` on every route, this screen included,
 * and every frozen entry there links to its own `/recovery/{id}`. So the other
 * entries' links are on screen while this one is open.
 *
 * Left unreset, `confirming` would carry the arming across: a user who arms the
 * delete on entry A, thinks better of it, and clicks entry B in the band would
 * land on B *already armed*, button reading "Yes, delete permanently" — and one
 * click would destroy B's only copy. The two-step guard would be defeated by a
 * URL change. `error` and `hydrationFailed` leak the same way, pinning A's
 * storage failure on B.
 */
watch(
  () => props.entryId,
  () => {
    confirming.value = false
    deleting.value = false
    leaving.value = false
    error.value = ''
    hydrationFailed.value = false
  },
)

/**
 * The entry, or nothing — and "not yours" is indistinguishable from "does not
 * exist", by the same screen and the same sentence. The queue is device-global
 * and this route is reachable by id, so telling the two apart would teach B that
 * A's entry exists; the ids are timestamp-prefixed and enumerable. Story 4.4
 * closed this leak for a transaction id and an email — here it is the *binaries*
 * that would be handed over.
 */
const entry = computed(() => {
  if (!auth.isAuthenticated) return null
  return (
    frozenEntries.value.find((e) => e.id === props.entryId && ownsEntry(e, auth.user)) || null
  )
})

const reason = computed(() => (entry.value ? describeFailure(entry.value.failure) : ''))

/**
 * The server's own text, kept only when it says something the code-derived label
 * did not. Blank-tested like `describeFailure` (`replayFailure.js:113`): a
 * whitespace-only message is not a detail, and raw truthiness would render it as
 * an empty line.
 */
const detail = computed(() => {
  const message = entry.value?.failure?.message
  return typeof message === 'string' && message.trim() !== '' && message !== reason.value
    ? message
    : null
})

const realState = computed(() =>
  entry.value
    ? resolveRealState({
        entry: entry.value,
        transactions: transactions.value,
        transactionsFetchedAt: transactionsFetchedAt.value,
        currentDetail: currentDetail.value,
        currentDetailFetchedAt: currentDetailFetchedAt.value,
      })
    : { kind: 'none' },
)

/**
 * Only OPEN_DISPUTE ever queues binaries (`escrow.js:180`), so this is empty for
 * a frozen SEND_EVENT / CREATE_TRANSACTION — which reach this screen all the
 * same, for the acknowledgement. Nothing about files is rendered for them: an
 * empty "your files" section would promise data that never existed.
 *
 * The fallback name feeds the display *and* `a.download`, from one expression:
 * two of them would drift and the user would save `undefined` off a line reading
 * something else. A queued `File` normally carries its name through structured
 * clone; a bare `Blob` does not.
 */
const files = computed(() =>
  (entry.value?.files || []).map((file, index) => ({
    file,
    key: `${file.name ?? 'file'}-${file.size ?? 0}-${index}`,
    name: file.name || `attachment-${index + 1}`,
    type: file.type || 'unknown',
    size: formatBytes(file.size),
  })),
)

function download(item) {
  saveBlob(item.file, item.name)
}

/**
 * Two steps, and both of them disarmable. This entry holds the *only* copy of
 * its binaries and this is the single destructive gesture in all of Epic 4: a
 * lone button in the middle of a recovery screen would be a trap. The second
 * step names the loss rather than asking "are you sure?", which informs nobody.
 *
 * A one-way arming would not be a protection but a delay — the destructive
 * gesture would still be one stray click away ten minutes later — hence Cancel,
 * and hence the disarm on failure below.
 */
async function acknowledge() {
  if (!confirming.value) {
    confirming.value = true
    return
  }
  error.value = ''
  try {
    await queue.removeFromQueue(props.entryId)
    router.push('/')
  } catch (err) {
    // Shown, never swallowed, and the entry stays listed and recoverable.
    // `flush()` does the opposite (`:238-245`: drop it from memory when IDB
    // refuses) — right for a queue that empties itself, wrong here: the row
    // would vanish while the files stayed on disk, which is precisely the silent
    // loss this epic exists to prevent, inverted.
    confirming.value = false
    error.value = err?.message
      ? t('recovery.deleteFailedWithReason', { reason: err.message })
      : t('recovery.deleteFailed')
  }
}
</script>

<template>
  <div class="mx-auto w-full max-w-3xl flex-1 px-4 py-6">
    <button class="mb-4 text-sm text-primary hover:underline" @click="router.push('/')">
      {{ $t('common.back') }}
    </button>

    <!-- Storage refused, and `init()` has already given up quietly: say so, and
         offer the retry nothing else would ever perform. -->
    <div
      v-if="hydrationFailed"
      class="rounded-2xl border border-red-200 bg-red-50 p-5 text-sm text-red-800 shadow-sm"
    >
      <p class="font-semibold">{{ $t('recovery.storageUnreadable') }}</p>
      <p class="mt-1">
        {{ $t('recovery.storageBlocked') }}
      </p>
      <button
        class="mt-3 rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white shadow hover:bg-red-700"
        @click="hydrate"
      >
        {{ $t('common.tryAgain') }}
      </button>
    </div>

    <!-- Never "nothing to recover" while the queue is still unread: the entry may
         well exist. Reachable on a deep link / reload — `main.js:17` calls
         `init()` without awaiting it. -->
    <div v-else-if="!hydrated" class="py-16 text-center text-sm text-gray-400">
      {{ $t('recovery.loading') }}
    </div>

    <!-- Absent, or someone else's: the same screen and the same words for both.
         See `entry`. -->
    <div
      v-else-if="!entry"
      class="rounded-xl border border-dashed border-gray-300 py-16 text-center text-sm text-gray-400"
    >
      <p>{{ $t('recovery.nothingToRecover') }}</p>
      <p class="mt-1">{{ $t('recovery.entryGone') }}</p>
      <RouterLink to="/" class="mt-3 inline-block font-medium text-primary hover:underline">
        {{ $t('common.backPlain') }}
      </RouterLink>
    </div>

    <template v-else>
      <div class="rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <h1 class="text-xl font-bold text-gray-900">
          {{ files.length > 0 ? $t('recovery.recoverFiles') : $t('recovery.reviewEntry') }}
        </h1>
        <p class="mt-1 text-sm text-gray-500">
          {{ describeAction(entry.meta) }} {{ $t('recovery.refusedSuffix') }}
        </p>

        <div class="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-800">
          <p>{{ reason }}</p>
          <p v-if="detail" class="mt-1 text-red-700">{{ detail }}</p>
        </div>

        <div class="mt-4 text-sm text-gray-600">
          <span v-if="realState.kind === 'badge'" class="flex items-center gap-1.5">
            <span>{{ $t('common.currentState') }}</span>
            <StateBadge :state="realState.state" />
          </span>
          <!-- A link, not a badge: nothing loaded here has provably seen the
               rejection, and this screen will not fetch to find out. -->
          <RouterLink
            v-else-if="realState.kind === 'link'"
            :to="`/escrow/${realState.id}`"
            class="font-medium text-primary underline"
          >
            {{ $t('sync.checkTransaction', { id: realState.id }) }}
          </RouterLink>
          <span v-else-if="realState.kind === 'never-created'">
            {{ $t('sync.neverCreated') }}
          </span>
        </div>
      </div>

      <!-- Only where there are files. A frozen SEND_EVENT has none, ever. -->
      <div v-if="files.length > 0" class="mt-6 rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 class="mb-1 text-sm font-semibold text-gray-900">
          {{ $t('recovery.filesKept') }}
        </h2>
        <p class="mb-4 text-xs text-gray-500">
          {{ $t('recovery.neverSent') }}
        </p>
        <ul class="space-y-2">
          <li
            v-for="item in files"
            :key="item.key"
            class="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-gray-100 px-3 py-2"
          >
            <span class="text-xs text-gray-500">
              {{ item.name }} · {{ item.type }} · {{ item.size }}
            </span>
            <button
              type="button"
              class="rounded-lg bg-primary px-3 py-1.5 text-xs font-semibold text-white shadow hover:bg-primary-hover"
              @click="download(item)"
            >
              {{ $t('common.download') }}
            </button>
          </li>
        </ul>
      </div>

      <div class="mt-6 rounded-2xl border border-gray-200 bg-white p-5 shadow-sm">
        <h2 class="text-sm font-semibold text-gray-900">{{ $t('recovery.doneWithEntry') }}</h2>
        <p class="mt-1 text-xs text-gray-500">
          {{ $t('recovery.acknowledgeExplain') }}
        </p>

        <!-- The second step names what is lost. "Are you sure?" informs nobody. -->
        <p v-if="confirming" class="mt-3 rounded-lg bg-red-50 p-3 text-sm text-red-800">
          <template v-if="files.length > 0">
            {{ $t('recovery.deleteWarningWithFiles', { count: files.length }) }}
          </template>
          <template v-else>
            {{ $t('recovery.deleteWarningPlain') }}
          </template>
        </p>

        <div class="mt-3 flex flex-wrap gap-2">
          <button
            type="button"
            class="rounded-lg bg-red-600 px-4 py-2 text-sm font-semibold text-white shadow hover:bg-red-700"
            @click="acknowledge"
          >
            {{ confirming ? $t('recovery.confirmDelete') : $t('recovery.acknowledgeAndDelete') }}
          </button>
          <!-- The disarm. Without it the arming would outlive the user's
               attention and the two-step would be a delay, not a protection. -->
          <button
            v-if="confirming"
            type="button"
            class="rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-600 hover:bg-gray-50"
            @click="confirming = false"
          >
            {{ $t('common.cancel') }}
          </button>
        </div>

        <p v-if="error" class="mt-3 text-sm text-red-600">{{ error }}</p>
      </div>
    </template>
  </div>
</template>
