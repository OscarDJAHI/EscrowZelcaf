/**
 * Saves a Blob to disk through the browser's own download path.
 *
 * Extracted from `stores/evidence.js:81-88`, which was the repo's only copy of
 * this idiom until Story 4.5 became its second caller — and the first to start
 * from a binary that is *already* local (a `File` kept in the offline queue),
 * with no HTTP fetch in front of it. Two hand-written copies of eight lines of
 * DOM API is exactly the shape that diverges on the part nobody sees fail: the
 * `revokeObjectURL`. A leaked ObjectURL pins its Blob in memory for the life of
 * the document, and no test and no user ever notices.
 *
 * The `<a>` is appended before being clicked: a detached anchor's click is a
 * no-op in Firefox, and the element is removed again immediately so nothing is
 * left in the DOM.
 * @param {Blob} blob the bytes to save — already in memory, never fetched here
 * @param {string} filename the name proposed to the user's save dialog
 */
export function saveBlob(blob, filename) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
