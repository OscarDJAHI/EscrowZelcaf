import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

// Separate from vite.config.js on purpose: that file imports `defineConfig`
// from 'vite' (no `test` key) and carries the PWA plugin, which the unit
// suite has no use for. The `@` alias is re-declared here because every
// module under src/ imports through it.
export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./vitest.setup.js'],
    // The binary round-trip tests push multi-MB Blobs through fake-indexeddb's
    // structured clone, which is well over the 5s default.
    testTimeout: 30000,
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
})
