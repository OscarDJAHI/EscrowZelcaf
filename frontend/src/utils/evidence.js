/**
 * Single source of truth for the evidence limits mirrored from the server.
 * The server remains the sole authority; these constants only power the
 * pre-upload UX validation and the human-readable list display.
 */

export const MAX_EVIDENCE_SIZE = 10485760 // 10 MiB, mirrors the server limit

export const ALLOWED_MIME_TYPES = ['image/jpeg', 'image/png', 'application/pdf']

export const ALLOWED_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.pdf']

const UPLOADER_TYPE_LABELS = {
  BUYER: 'Buyer',
  SELLER: 'Seller',
  ADMIN: 'Admin',
  CARRIER_PARTNER: 'Carrier partner',
}

/** Formats a byte count into a short, human-readable string. */
export function formatBytes(bytes) {
  if (bytes === null || bytes === undefined || Number.isNaN(bytes)) return '—'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  const exponent = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / 1024 ** exponent
  const rounded = exponent === 0 ? value : Math.round(value * 10) / 10
  return `${rounded} ${units[exponent]}`
}

/**
 * Human-readable label for who deposited a piece of evidence.
 * Shows "You" when the current user is the uploader, otherwise maps the
 * uploaderType enum. Identity is never inferred from the email alone.
 */
export function uploaderLabel(item, currentUserId) {
  if (item && currentUserId != null && item.uploadedByUserId === currentUserId) {
    return 'You'
  }
  return UPLOADER_TYPE_LABELS[item?.uploaderType] || 'Unknown'
}

/**
 * Client-side mirror validation (comfort only, never authority).
 * @returns {string|null} an English error message, or null when the file passes.
 */
export function validateFile(file) {
  if (!file) return 'Please choose a file to upload.'
  if (file.size === 0) return 'The selected file is empty.'
  if (file.size > MAX_EVIDENCE_SIZE) {
    return 'Each file must be 10 MB or smaller.'
  }
  const typeAllowed = ALLOWED_MIME_TYPES.includes(file.type)
  const name = (file.name || '').toLowerCase()
  const extensionAllowed = ALLOWED_EXTENSIONS.some((ext) => name.endsWith(ext))
  if (!typeAllowed && !extensionAllowed) {
    return 'Only JPG, PNG or PDF files are allowed.'
  }
  return null
}
