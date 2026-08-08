import { openDB } from 'idb'
import type { IDBPDatabase } from 'idb'
import { ownsEntry } from '@/utils/frozenEntry'
import type { QueueEntry } from '@/types/queue'

const DB_NAME = 'escrow-offline'
// Still 1 after Story 1.9, deliberately. Scoping the queue by owner could have
// been an index on `meta.userId` under a DB_VERSION = 2 — the "clean" answer at
// real volume. But this queue holds a handful of entries, never thousands, so an
// application-level filter costs nothing measurable, while a schema migration
// would be the only change in this batch able to lose data on a cold upgrade.
// The scoping therefore lives in the *signature* (`getAllForUser`,
// `clearForUser`): a caller can no longer obtain the whole device queue by
// accident, which is the property that actually mattered.
const DB_VERSION = 1
const STORE_NAME = 'queue'

/** Legacy localStorage key: read once by `migrateFromLocalStorage()`, then dropped for good. */
const LEGACY_STORAGE_KEY = 'escrow_offline_queue'

let dbPromise: Promise<IDBPDatabase> | null = null

/**
 * Opens (and memoises) the queue database. `keyPath: 'id'` mirrors the id the
 * store generates, so `put` is an upsert and `delete` takes the item id.
 */
function getDB(): Promise<IDBPDatabase> {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains(STORE_NAME)) {
          db.createObjectStore(STORE_NAME, { keyPath: 'id' })
        }
      },
    }).catch((err) => {
      // Never memoise a rejection: a transient open failure (a stale tab holding
      // an old version, a blocked upgrade) would otherwise disable offline
      // queuing for the whole session, with a reload as the only way out.
      dbPromise = null
      throw err
    })
  }
  return dbPromise
}

/**
 * Oldest first — the FIFO replay order.
 *
 * `timestamp` alone is not a total order: it has millisecond resolution, so two
 * entries queued in the same millisecond tie and the sort would fall back to
 * IndexedDB's key order — which the random id suffix makes arbitrary. `seq` is
 * the monotonic enqueue counter that breaks such ties. Legacy entries migrated
 * from localStorage carry no `seq`; they are strictly older, so the timestamp
 * comparison settles them before the tiebreak is ever reached.
 */
function sortFifo(items: QueueEntry[]): QueueEntry[] {
  return items.sort((a, b) => {
    if (a.timestamp !== b.timestamp) return a.timestamp < b.timestamp ? -1 : 1
    return (a.seq ?? 0) - (b.seq ?? 0)
  })
}

/**
 * Reads back the *whole* device queue, every owner included — a read-back seam
 * for the tests, and nothing else.
 *
 * Production code must go through `getAllForUser`: this database is device-
 * global, and handing the whole of it to a caller is exactly the mistake Story
 * 1.9 exists to make impossible to commit by accident.
 */
export async function getAll(): Promise<QueueEntry[]> {
  const db = await getDB()
  return sortFifo(await db.getAll(STORE_NAME))
}

/**
 * The queue as one user may legitimately see it: their own entries, oldest
 * first. Everyone else's — and every entry with no owner at all — is invisible
 * here, which is what stops `flush()` replaying a stranger's evidence under the
 * current JWT (the server answers NOT_A_PARTY and the real owner's entry is
 * frozen for good).
 *
 * Ownership is decided by `ownsEntry`, never by a second coercion written out
 * here: the same id round-trips through localStorage JSON on one side and
 * IndexedDB structured clone on the other, so it can legitimately come back a
 * number against a string. A copy of that rule would drift.
 *
 * An entry with no `meta.userId` belongs to nobody (Story 4.5 refused to invent
 * an owner for it) and so is never returned — not to the next person to sign in,
 * not to anyone.
 */
export async function getAllForUser(
  userId: number | string | null | undefined,
): Promise<QueueEntry[]> {
  if (userId == null) return []
  const db = await getDB()
  const items = await db.getAll(STORE_NAME)
  return sortFifo(items.filter((item) => ownsEntry(item, { id: userId })))
}

/**
 * Writes one queue item. Blobs inside `files` are stored as-is (structured
 * clone) — never base64. Rejects on failure so the caller can surface it:
 * silently losing binary is the one thing this layer must not do. Rend la même entrée,
 * une fois durablement écrite.
 */
export async function put(item: QueueEntry): Promise<QueueEntry> {
  const db = await getDB()
  await db.put(STORE_NAME, item)
  return item
}

/** Deletes one queue item by id. */
export async function remove(id: string): Promise<void> {
  const db = await getDB()
  await db.delete(STORE_NAME, id)
}

/**
 * Deletes what an explicit sign-out must not leave on a shared device: the
 * departing user's entries and the ownerless ones — and nothing else.
 *
 * `clear()` would have been a new bug: wiping the store erases *another* user's
 * pending evidence in the name of hygiene, and that user has no other copy. The
 * ownerless entries go because Story 4.5 decided nobody would ever be shown
 * them; they are provably unreachable, so only their bytes remain on the device.
 *
 * A `userId` of null or undefined removes the ownerless entries alone. That is
 * the honest outcome and not a fallback: with no id there is nobody to purge
 * for, and guessing would delete a stranger's evidence.
 *
 * One read-write transaction: reading the ids and deleting them in two separate
 * ones would let an enqueue land in between and be swept away.
 */
export async function clearForUser(userId: number | string | null | undefined): Promise<void> {
  const db = await getDB()
  const tx = db.transaction(STORE_NAME, 'readwrite')
  const items = await tx.store.getAll()
  for (const item of items) {
    const orphan = item?.meta?.userId == null
    if (orphan || ownsEntry(item, { id: userId })) tx.store.delete(item.id)
  }
  await tx.done
}

/** Clears every queue item (test/reset helper). */
export async function clear(): Promise<void> {
  const db = await getDB()
  await db.clear(STORE_NAME)
}

/**
 * One-shot import of a pre-IndexedDB queue left in localStorage by an already
 * installed PWA. Entries are copied over, then the legacy key is removed so the
 * migration never runs twice. A corrupt payload costs the (unreadable) legacy
 * entries, not a crash at boot: the key is dropped and startup continues.
 *
 * <p>Rend le nombre d'entrées héritées importées.
 */
export async function migrateFromLocalStorage(): Promise<number> {
  let raw: string | null
  try {
    // Not just `typeof localStorage === 'undefined'`: with cookies blocked or
    // storage partitioned, the accessor itself throws SecurityError. There is
    // then no legacy queue to read, and that must not take boot down.
    if (typeof localStorage === 'undefined') return 0
    raw = localStorage.getItem(LEGACY_STORAGE_KEY)
  } catch {
    return 0
  }
  if (!raw) return 0

  let legacy: unknown
  try {
    legacy = JSON.parse(raw)
  } catch {
    localStorage.removeItem(LEGACY_STORAGE_KEY)
    return 0
  }

  if (!Array.isArray(legacy)) {
    localStorage.removeItem(LEGACY_STORAGE_KEY)
    return 0
  }

  let imported = 0
  for (const item of legacy) {
    // Le contenu de localStorage n'est pas digne de confiance : `legacy` est un
    // `unknown[]` et chaque élément est vérifié avant d'être écrit. L'assertion qui
    // suit ne porte donc que sur ce que ces gardes ont déjà établi.
    if (!item || typeof item !== 'object' || !(item as QueueEntry).id) continue
    // eslint-disable-next-line no-await-in-loop
    await put(item as QueueEntry)
    imported += 1
  }

  // Only dropped once every entry is safely in IndexedDB: if a write above
  // throws, the key survives and the migration is retried on the next boot.
  localStorage.removeItem(LEGACY_STORAGE_KEY)
  return imported
}

/** Test seam: drops the memoised connection so a fresh DB can be opened. */
export function resetDBForTests(): void {
  dbPromise = null
}
