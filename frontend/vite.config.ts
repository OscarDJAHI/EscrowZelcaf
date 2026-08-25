import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'
import { VitePWA } from 'vite-plugin-pwa'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    tailwindcss(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icon.svg', 'pwa-192x192.png', 'pwa-512x512.png'],
      manifest: {
        name: 'ZLECAf Escrow',
        short_name: 'Escrow',
        description:
          'B2B Escrow platform for secure intra-African (ZLECAf) trade transactions.',
        // Ces deux couleurs sont les SEULES valeurs de marque qui vivent hors de
        // `style.css` : le manifeste est lu par le système d'exploitation avant que la
        // moindre feuille de style ne soit chargée, il ne peut donc pas référencer un
        // token CSS. Elles sont donc recopiées — et `verify:pwa` échoue si la copie
        // diverge des tokens, faute de quoi la dérive passerait inaperçue (elle l'a fait :
        // ce champ valait encore un teal `#0f766e` que la Story 2.1 avait retiré du
        // nuancier, et rien ne le signalait).
        theme_color: '#101e5a', // = --color-brand-navy : le chrome applicatif
        background_color: '#f6f7fb', // = --color-surface-page : l'écran de démarrage

        display: 'standalone',
        start_url: '/',
        scope: '/',
        icons: [
          {
            src: 'pwa-192x192.png',
            sizes: '192x192',
            type: 'image/png',
          },
          {
            src: 'pwa-512x512.png',
            sizes: '512x512',
            type: 'image/png',
          },
          {
            src: 'pwa-512x512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
      workbox: {
        // PRÉ-CACHE DU SHELL. `navigateFallback` ci-dessous ne sert à rien si
        // `index.html` n'est pas dans le pré-cache : hors ligne, la requête de navigation
        // n'aurait aucune réponse à recevoir. On liste donc explicitement ce que le shell
        // exige, plutôt que de s'en remettre au motif par défaut du plugin — un défaut
        // n'est pas un contrat, et celui-ci changerait sans nous prévenir.
        globPatterns: ['**/*.{js,css,html,svg,png,ico,webmanifest,woff2}'],
        navigateFallback: '/index.html',
        // App shell: HTML/CSS/JS/images always try the network first so
        // updates are picked up quickly, but fall back to cache when the
        // connection is unreliable (common on African mobile networks).
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith('/api/'),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'escrow-api-cache',
              networkTimeoutSeconds: 6,
              cacheableResponse: { statuses: [0, 200] },
              expiration: { maxEntries: 100, maxAgeSeconds: 60 * 60 * 24 },
            },
          },
          {
            urlPattern: ({ request }) =>
              ['document', 'script', 'style', 'worker'].includes(request.destination),
            handler: 'NetworkFirst',
            options: {
              cacheName: 'escrow-app-shell',
              networkTimeoutSeconds: 4,
            },
          },
          {
            urlPattern: ({ request }) => request.destination === 'image',
            handler: 'NetworkFirst',
            options: {
              cacheName: 'escrow-images',
              expiration: { maxEntries: 60, maxAgeSeconds: 60 * 60 * 24 * 30 },
            },
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
  },
})
