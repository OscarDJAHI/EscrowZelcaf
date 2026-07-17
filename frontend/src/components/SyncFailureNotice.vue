<script setup>
import { computed } from 'vue'
import { RouterLink } from 'vue-router'
import { storeToRefs } from 'pinia'
import StateBadge from '@/components/StateBadge.vue'
import { useAuthStore } from '@/stores/auth'
import { useEscrowStore } from '@/stores/escrow'
import { useOfflineQueueStore } from '@/stores/offlineQueue'
import { describeFailure } from '@/utils/replayFailure'

/**
 * The only surface on which a definitively rejected queued action exists. A
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
 */

/** What was refused, said in the user's terms. Only these three reach the queue. */
const ACTION_LABELS = {
  CREATE_TRANSACTION: 'Creating a transaction',
  SEND_EVENT: 'Updating a transaction',
  OPEN_DISPUTE: 'Opening a dispute',
}

/**
 * Codes whose label already says the transaction is gone or was never yours.
 * Offering to open it would walk the user straight into
 * `TransactionDetailView`'s error panel — the notice contradicting itself.
 */
const NO_LINK_CODES = new Set(['TRANSACTION_NOT_FOUND', 'NOT_A_PARTY', 'RESOURCE_NOT_FOUND'])

const auth = useAuthStore()
const escrow = useEscrowStore()
const offlineQueue = useOfflineQueueStore()
const { frozenEntries } = storeToRefs(offlineQueue)
const { transactions, currentDetail, transactionsFetchedAt, currentDetailFetchedAt } =
  storeToRefs(escrow)

/** True if a row carries any optimistic marker — matched by shape, so a marker added later is caught too. */
function isOptimistic(row) {
  return Object.keys(row || {}).some((key) => key.startsWith('_queued'))
}

/**
 * A source may be badged only if it has provably seen the rejection, i.e. it
 * was issued to the server after the freeze was recorded (`escrow.js` stamps
 * at issue, `offlineQueue.js:198-201` stamps the freeze, same client clock).
 * No fixed precedence between the list and the detail: `escrow:sync` only
 * refreshes *mounted* views, so either one can be the stale one depending on
 * where the user stands. Absence of an optimistic marker is required on top —
 * a fresh row may have been re-dirtied by a later enqueue — but never suffices
 * on its own: `escrow.js:104-106` marks only `currentDetail` for a SEND_EVENT,
 * so no `transactions` row ever carries a marker for one.
 *
 * `>=`, not `>`: the freeze stamps `failure.at` and then dispatches
 * `escrow:sync` (`offlineQueue.js:201,252`), whose listener issues the refetch
 * a single `await` later — the two stamps routinely land in the same
 * millisecond, and a strict `>` would reject the very reload the notice is
 * waiting for, on its first render. Equality is safe: the replay had already
 * been refused when `at` was stamped, so a load issued that same millisecond
 * still went out after the server's verdict.
 */
function trustworthy(row, fetchedAt, failureAt) {
  if (!row || !fetchedAt || !failureAt) return null
  if (fetchedAt < failureAt) return null
  if (isOptimistic(row)) return null
  // The store is fed by the API, which always sends a state — but this renders
  // above `RouterView`, so throwing here would blank every route, not one.
  if (typeof row.state !== 'string' || !row.state) return null
  return { state: row.state, fetchedAt }
}

/**
 * What to show as the real state of a frozen entry's transaction:
 * `{kind:'never-created'}`, `{kind:'badge', state}`, `{kind:'link', id}` or
 * `{kind:'none'}`. Never a guess — an unknown state degrades to a link, and a
 * link the reason forbids degrades to nothing at all.
 */
function realState(entry) {
  const id = entry.meta?.transactionId
  // Keyed on the type, not merely on a missing id: a CREATE_TRANSACTION never
  // carries one, but a malformed SEND_EVENT/OPEN_DISPUTE without an id must not
  // be told "this transaction was never created" about a transaction that
  // exists. Unknown target, nothing truthful to say: say nothing.
  if (id == null) {
    return entry.meta?.type === 'CREATE_TRANSACTION' ? { kind: 'never-created' } : { kind: 'none' }
  }

  // Checked before any badge, not only as a fallback: the reason already says
  // the transaction is gone or was never yours, so whatever a row still claims
  // about it, showing that state would have the notice contradict its own
  // sentence one line down.
  if (NO_LINK_CODES.has(entry.failure?.code)) return { kind: 'none' }

  const failureAt = entry.failure?.at
  const detail = currentDetail.value?.transaction
  const candidates = [
    trustworthy(
      detail && String(detail.id) === String(id) ? detail : null,
      currentDetailFetchedAt.value,
      failureAt,
    ),
    trustworthy(
      // Same reason as the `state` type guard below: the API contract says a
      // list, but this renders above `RouterView`, so a payload that is not one
      // must degrade to a link rather than blank every route.
      (Array.isArray(transactions.value) ? transactions.value : []).find(
        (t) => String(t.id) === String(id),
      ),
      transactionsFetchedAt.value,
      failureAt,
    ),
  ].filter(Boolean)

  const fallback = { kind: 'link', id: String(id) }

  if (candidates.length === 0) return fallback

  const newest = candidates.reduce((a, b) => (b.fetchedAt > a.fetchedAt ? b : a))
  // Same instant, different answers: nothing here can break the tie, and
  // splitting it on millisecond order would be drawing the displayed state out
  // of a hat. Say nothing rather than pick.
  const contested = candidates.some((c) => c.fetchedAt === newest.fetchedAt && c.state !== newest.state)
  if (contested) return fallback

  return { kind: 'badge', state: newest.state }
}

/**
 * Only the current user's entries, and only while that user holds a session.
 * The two gates answer different questions and neither replaces the other:
 * `isAuthenticated` reads the token ("is a session open"), the ownership test
 * reads `meta.userId` ("is this theirs"). Ownership alone is what closes the
 * real leak — the queue is device-global and `logout()` does not clear it
 * (rightly: that is the loss Epic 4 exists to prevent), so without it the next
 * user reads the previous one's transaction ids and the server `message` that
 * interpolates their email. The token gate is kept on top because `token` and
 * `user` load from two independent localStorage keys (`auth.js:18-19`): should
 * they ever diverge, a sessionless page must show nothing rather than trust a
 * leftover user object.
 *
 * An entry with no `meta.userId` (queued before this shipped) is shown to
 * nobody: inventing an owner for it would reopen exactly that hole. Nothing is
 * lost either way — the entry outlives the session and comes back to its own
 * owner at their next login.
 */
const entries = computed(() => {
  const userId = auth.user?.id
  if (!auth.isAuthenticated || userId == null) return []
  return frozenEntries.value
    .filter((entry) => entry.meta?.userId != null && String(entry.meta.userId) === String(userId))
    .map((entry) => {
      const reason = describeFailure(entry.failure)
      const message = entry.failure?.message
      return {
        id: entry.id,
        // `hasOwn`, like `describeFailure`: `meta` round-trips through
        // IndexedDB, and a bare lookup of `constructor` would render a function
        // into the page — `||` cannot catch it, a function being truthy.
        action: Object.hasOwn(ACTION_LABELS, entry.meta?.type ?? '')
          ? ACTION_LABELS[entry.meta.type]
          : 'A queued action',
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
        state: realState(entry),
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
             Nothing is offered to do with them: recovery is Story 4.5. -->
        <span v-if="entry.fileCount > 0" class="mt-1 block text-red-700">
          {{ entry.fileCount }} attached file(s) are still stored on this device.
        </span>
      </li>
    </ul>
  </div>
</template>
