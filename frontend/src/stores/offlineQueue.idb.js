import { openDB } from 'idb'

const DB_NAME = 'escrow-offline'
const DB_VERSION = 1
const STORE_NAME = 'queue'

/** Legacy localStorage key: read once by `migrateFromLocalStorage()`, then dropped for good. */
const LEGACY_STORAGE_KEY = 'escrow_offline_queue'

let dbPromise = null

/**
 * Opens (and memoises) the queue database. `keyPath: 'id'` mirrors the id the
 * store generates, so `put` is an upsert and `delete` takes the item id.
 * @returns {Promise<import('idb').IDBPDatabase>}
 */
function getDB() {
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
 * Reads the whole queue back, oldest first (FIFO replay order).
 *
 * `timestamp` alone is not a total order: it has millisecond resolution, so two
 * entries queued in the same millisecond tie and the sort would fall back to
 * IndexedDB's key order — which the random id suffix makes arbitrary. `seq` is
 * the monotonic enqueue counter that breaks such ties. Legacy entries migrated
 * from localStorage carry no `seq`; they are strictly older, so the timestamp
 * comparison settles them before the tiebreak is ever reached.
 * @returns {Promise<object[]>}
 */
export async function getAll() {
  const db = await getDB()
  const items = await db.getAll(STORE_NAME)
  return items.sort((a, b) => {
    if (a.timestamp !== b.timestamp) return a.timestamp < b.timestamp ? -1 : 1
    return (a.seq ?? 0) - (b.seq ?? 0)
  })
}

/**
 * Writes one queue item. Blobs inside `files` are stored as-is (structured
 * clone) — never base64. Rejects on failure so the caller can surface it:
 * silently losing binary is the one thing this layer must not do.
 * @param {object} item
 * @returns {Promise<object>} the same item, once durably written
 */
export async function put(item) {
  const db = await getDB()
  await db.put(STORE_NAME, item)
  return item
}

/**
 * Deletes one queue item by id.
 * @param {string} id
 */
export async function remove(id) {
  const db = await getDB()
  await db.delete(STORE_NAME, id)
}

/** Clears every queue item (test/reset helper). */
export async function clear() {
  const db = await getDB()
  await db.clear(STORE_NAME)
}

/**
 * One-shot import of a pre-IndexedDB queue left in localStorage by an already
 * installed PWA. Entries are copied over, then the legacy key is removed so the
 * migration never runs twice. A corrupt payload costs the (unreadable) legacy
 * entries, not a crash at boot: the key is dropped and startup continues.
 * @returns {Promise<number>} how many legacy entries were imported
 */
export async function migrateFromLocalStorage() {
  let raw
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

  let legacy
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
    if (!item || typeof item !== 'object' || !item.id) continue
    // eslint-disable-next-line no-await-in-loop
    await put(item)
    imported += 1
  }

  // Only dropped once every entry is safely in IndexedDB: if a write above
  // throws, the key survives and the migration is retried on the next boot.
  localStorage.removeItem(LEGACY_STORAGE_KEY)
  return imported
}

/** Test seam: drops the memoised connection so a fresh DB can be opened. */
export function resetDBForTests() {
  dbPromise = null
}
