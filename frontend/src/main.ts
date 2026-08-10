import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useOfflineQueueStore } from './stores/offlineQueue'
import { enforceIdlePolicy, installIdleTimeout, installSessionExpiryListener } from './stores/session'
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

// POLITIQUE D'INACTIVITÉ (Story 2.7, AC2) — LES DEUX MOITIÉS, ET AVANT LE MONTAGE.
//
// Le contrôle au démarrage est ce qui attrape l'ONGLET ROUVERT : une minuterie ne vit que
// dans l'onglet qui la porte, et le shell applicatif est précaché par le service worker
// (UX-DR46), donc l'interface authentifiée s'affiche AVANT tout appel API — sans cette
// ligne, la mesure se contourne d'une simple réouverture. Il est appelé ici, avant
// `app.mount()` : `enforceIdlePolicy` est asynchrone, mais elle vide les identifiants et
// les stores de façon SYNCHRONE avant son premier `await`, si bien qu'au retour de cet
// appel la garde du routeur voit déjà une session morte. Ne pas l'attendre est donc un
// choix, pas un oubli — `app.mount()` n'attend rien, et une session à demi démontée au
// premier rendu serait précisément le défaut.
//
// La minuterie vient APRÈS, et l'ordre n'est pas load-bearing : `installIdleTimeout`
// n'horodate rien à l'installation, exactement pour que ce ne soit pas le cas.
void enforceIdlePolicy()
installIdleTimeout(router)

// Start listening for connectivity changes so queued offline
// requests (transaction creation / state events) are flushed
// automatically as soon as the device comes back online.
useOfflineQueueStore().init()

app.mount('#app')
