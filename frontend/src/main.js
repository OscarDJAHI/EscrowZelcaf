import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useOfflineQueueStore } from './stores/offlineQueue'
import { installSessionExpiryListener } from './stores/session'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// A revoked or expired token surfaces as a bare 403 from the interceptor, which
// only dispatches an event (it cannot import the router without closing a cycle).
// This is what turns that event into a teardown and a trip back to sign-in.
installSessionExpiryListener(router)

// Start listening for connectivity changes so queued offline
// requests (transaction creation / state events) are flushed
// automatically as soon as the device comes back online.
useOfflineQueueStore().init()

app.mount('#app')
