import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useOfflineQueueStore } from './stores/offlineQueue'
import { installSessionExpiryListener } from './stores/session'
import { i18n } from './i18n'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
// i18n enregistré AVANT le montage : un composant qui appellerait `$t` sans le plugin
// lèverait à l'exécution. L'ordre relatif à pinia/router est indifférent, mais celui
// des deux appels ci-dessous ne l'est pas — voir leurs commentaires.
app.use(i18n)
// La langue restaurée doit se refléter dans l'attribut `lang` du document : c'est ce
// que lisent les lecteurs d'écran et la césure typographique du navigateur.
document.documentElement.setAttribute('lang', i18n.global.locale.value)

// A revoked or expired token surfaces as a bare 403 from the interceptor, which
// only dispatches an event (it cannot import the router without closing a cycle).
// This is what turns that event into a teardown and a trip back to sign-in.
installSessionExpiryListener(router)

// Start listening for connectivity changes so queued offline
// requests (transaction creation / state events) are flushed
// automatically as soon as the device comes back online.
useOfflineQueueStore().init()

app.mount('#app')
