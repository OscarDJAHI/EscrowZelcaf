import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useOfflineQueueStore } from './stores/offlineQueue'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// Start listening for connectivity changes so queued offline
// requests (transaction creation / state events) are flushed
// automatically as soon as the device comes back online.
useOfflineQueueStore().init()

app.mount('#app')
