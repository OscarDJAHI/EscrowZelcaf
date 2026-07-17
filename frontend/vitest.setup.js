import 'fake-indexeddb/auto'
import { Blob, File } from 'node:buffer'

// Test-harness artifact, not a production concern: in a real browser Blob is
// natively structured-cloneable and FormData accepts it, so none of this exists
// outside the suite.
//
// 1. Under `environment: 'jsdom'`, fake-indexeddb's structured clone silently
//    serialises a jsdom Blob into an empty `{}`: no error is thrown, `size`
//    becomes undefined and the bytes are gone. Node's Blob/File (node:buffer)
//    are structured-cloneable and round-trip byte-perfect.
globalThis.Blob = Blob
globalThis.File = File

// 2. FormData must come from the SAME realm as the Blob above. jsdom's FormData
//    brand-checks against jsdom's own Blob, so appending a node:buffer Blob
//    falls through to string coercion and stores the literal "[object Blob]" —
//    destroying the binary exactly where the multipart replay is meant to prove
//    it survives. undici's FormData (the implementation behind Node's own
//    global) accepts a node:buffer Blob and keeps the bytes.
//
//    This import MUST stay dynamic and MUST stay below the swap above: undici
//    resolves its `webidl.is.Blob` brand check against globalThis.Blob at
//    module-evaluation time, and a static `import` is hoisted above these
//    assignments — it would capture jsdom's Blob and reject Node's.
const { FormData } = await import('undici')
globalThis.FormData = FormData
