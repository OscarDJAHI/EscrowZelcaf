<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import StateBadge from '@/components/StateBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import { describeFailure } from '@/utils/replayFailure'
import { describeAction, ownsEntry, resolveRealState } from '@/utils/frozenEntry'

/**
 * The first surface on which a definitively rejected queued action exists. A
 * frozen entry is excluded from `pendingCount`, so `OnlineBanner` stops
 * counting it, and the `escrow:sync` refetch wipes its optimistic display:
 * without this, the sole trace left of a lost dispute is a `console.error`.
 *
 * A pure projection of `frozenEntries` — no state of its own, no HTTP. Reading
 * the real state from the store rather than refetching it is not an
 * optimisation: the only load action available writes `escrowStore.error`,
 * which `TransactionDetailView.vue:125-127` renders in a `v-else-if` that
 * *replaces* the detail — a chatty notice would blank the page underneath the
 * user. Offline, a refetch could not run at all.
 *
 * The rules themselves live in `@/utils/frozenEntry`: `RecoveryView` must give
 * the same answers, and two copies of a criterion that cost Story 4.4 five
 * review passes would drift with each surface's own tests staying green.
 */

const auth = useAuthStore()
const escrow = useEscrowStore()
const offlineQueue = useOfflineQueueStore()
const { frozenEntries } = storeToRefs(offlineQueue)
const { transactions, currentDetail, transactionsFetchedAt, currentDetailFetchedAt } =
  storeToRefs(escrow)

/**
 * Only the current user's entries, and only while that user holds a session.
 * `ownsEntry` answers "is this theirs" (and refuses an entry with no
 * `meta.userId` to everybody); `isAuthenticated` answers "is a session open" and
 * is kept on top of it because `token` and `user` load from two independent
 * localStorage keys (`auth.js:18-19`) — should they ever diverge, a sessionless
 * page must show nothing rather than trust a leftover user object.
 */
const entries = computed(() => {
  if (!auth.isAuthenticated) return []
  return frozenEntries.value
    .filter((entry) => ownsEntry(entry, auth.user))
    .map((entry) => {
      const reason = describeFailure(entry.failure)
      const message = entry.failure?.message
      return {
        id: entry.id,
        action: describeAction(entry.meta),
        reason,
        // The server text is worth showing — it carries the specific detail a
        // code-derived label cannot know ("Uploaded file is empty") — but only
        // when it adds something `reason` did not already say. Blank-tested the
        // same way `describeFailure` does (`replayFailure.js:113`): a
        // whitespace-only message is not a detail, and raw truthiness would
        // render it as an empty red line under the reason.
        detail: typeof message === 'string' && message.trim() !== '' && message !== reason
          ? message
          : null,
        fileCount: entry.files?.length || 0,
        state: resolveRealState({
          entry,
          transactions: transactions.value,
          transactionsFetchedAt: transactionsFetchedAt.value,
          currentDetail: currentDetail.value,
          currentDetailFetchedAt: currentDetailFetchedAt.value,
        }),
      }
    })
})
</script>

<template>
  <div
    v-if="entries.length > 0"
    role="alert"
    class="w-full border-b border-red-200 bg-red-50 px-4 py-3 text-red-900"
  >
    <p class="text-xs font-semibold sm:text-sm">
      {{ entries.length }} action(s) could not be synced and were refused by the server.
    </p>

    <ul class="mt-2 space-y-2">
      <li v-for="entry in entries" :key="entry.id" class="text-xs sm:text-sm">
        <span class="font-medium">{{ entry.action }}</span>
        <span> — {{ entry.reason }}</span>

        <span v-if="entry.detail" class="block text-red-700">{{ entry.detail }}</span>

        <span v-if="entry.state.kind === 'badge'" class="mt-1 flex items-center gap-1.5">
          <span>Current state:</span>
          <StateBadge :state="entry.state.state" />
        </span>
        <span v-else-if="entry.state.kind === 'link'" class="mt-1 block">
          <RouterLink :to="`/escrow/${entry.state.id}`" class="font-medium underline">
            Check transaction #{{ entry.state.id }}
          </RouterLink>
        </span>
        <span v-else-if="entry.state.kind === 'never-created'" class="mt-1 block text-red-700">
          This transaction was never created.
        </span>

        <!-- Only OPEN_DISPUTE queues binaries (`escrow.js:155`); telling a
             SEND_EVENT its files are safe would promise data that never existed.
             This block says that the files are kept, and nothing more: the way
             out is below, and does not belong to it. -->
        <span v-if="entry.fileCount > 0" class="mt-1 block text-red-700">
          {{ entry.fileCount }} attached file(s) are still stored on this device.
        </span>

        <!-- Every frozen entry, with or without a file. The recovery screen is
             the only acknowledgement that exists — hence the only way this red
             band ever goes away — and only OPEN_DISPUTE ever queues binaries,
             so gating this on `fileCount` would condemn a frozen SEND_EVENT /
             CREATE_TRANSACTION to a permanent, undismissable `role="alert"`
             above every route. `fileCount` decides the *wording*, never the
             existence of the door. Nor does `NO_LINK_CODES`: it forbids the
             link to the transaction above, and "that transaction is gone" is no
             argument against recovering *your own* files or acknowledging the
             entry. -->
        <span class="mt-1 block">
          <RouterLink :to="`/recovery/${entry.id}`" class="font-medium underline">
            {{ entry.fileCount > 0 ? 'Recover files' : 'Review this entry' }}
          </RouterLink>
        </span>
      </li>
    </ul>
  </div>
</template>
