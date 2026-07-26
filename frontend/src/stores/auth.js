import { defineStore } from 'pinia'
import { loginUser, registerUser, logoutUser } from '@/api/auth'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import { beginSession } from './session'

/**
 * Exported since Story 1.9, and paired with `TOKEN_STORAGE_KEY`: the two keys a
 * session leaves in localStorage are the two things anybody auditing a sign-out
 * has to name. `api/client.js` used to spell this one out as a literal in its
 * interceptor — a copy that would have outlived any rename — and the export is
 * what stops the next such copy being written.
 *
 * This store is now the only writer; nothing outside it removes either key.
 */
export const USER_STORAGE_KEY = 'escrow_user'

function loadStoredUser() {
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_STORAGE_KEY) || null,
    user: loadStoredUser(),
    loading: false,
    error: null,
  }),

  getters: {
    isAuthenticated: (state) => Boolean(state.token),
    role: (state) => state.user?.role || null,
  },

  actions: {
    persist() {
      if (this.token) localStorage.setItem(TOKEN_STORAGE_KEY, this.token)
      else localStorage.removeItem(TOKEN_STORAGE_KEY)

      if (this.user) localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(this.user))
      else localStorage.removeItem(USER_STORAGE_KEY)
    },

    /**
     * The one point where the identity on this device changes, hence the one
     * place `beginSession()` can be hooked: `main.js` has long finished running
     * by the time anybody signs in, so without this the user who just logged in
     * would never hydrate their own queued entries.
     *
     * The state is written *synchronously* — `token`, `user` and `persist()` are
     * done before the first `await` inside `beginSession` — and the promise is
     * returned rather than awaited, so `login()` can sequence the read-cache
     * purge before it hands control back to the view. `beginSession` never
     * rejects, so ignoring the returned promise is safe.
     * @param {{token: string, user: object}} session
     * @returns {Promise<void>}
     */
    applySession({ token, user }) {
      this.token = token
      this.user = user
      this.persist()
      return beginSession(user?.id)
    },

    async login(credentials) {
      this.loading = true
      this.error = null
      try {
        const session = await loginUser(credentials)
        // Awaited: on a shared device `beginSession` drops the previous user's
        // read cache, and "before anything is rendered" is only true if the view
        // is still waiting on us here.
        await this.applySession(session)
        return true
      } catch (err) {
        this.error = err.response?.data?.message || 'Invalid email or password.'
        return false
      } finally {
        this.loading = false
      }
    },

    async register(payload) {
      this.loading = true
      this.error = null
      try {
        const session = await registerUser(payload)
        await this.applySession(session)
        return true
      } catch (err) {
        this.error = err.response?.data?.message || 'Registration failed. Please try again.'
        return false
      } finally {
        this.loading = false
      }
    },

    /**
     * Purge locale, SYNCHRONE et sans réseau (Story 1.9). Séparée de la
     * révocation parce qu'une session **expirée** doit pouvoir être vidée sans
     * appeler un endpoint qui refusera de toute façon le jeton mort.
     *
     * <p>`loading`/`error` sont remis à zéro avec le reste : sans cela, le
     * message d'erreur de la session précédente accueille l'utilisateur suivant
     * sur l'écran de connexion.
     */
    clearSession() {
      this.token = null
      this.user = null
      this.loading = false
      this.error = null
      this.persist()
    },

    /**
     * Révocation serveur (Story 1.6, NFR-P5) : le jeton présenté n'est plus
     * accepté. Le jeton est passé explicitement parce que l'état local est
     * généralement déjà vidé quand on arrive ici.
     *
     * <p>La révocation était auparavant lâchée en microtâche sans être attendue :
     * le bouton de déconnexion enchaînant sur `window.location`, le navigateur
     * avortait la requête et le jeton restait valide côté serveur jusqu'à son
     * expiration. `logoutUser` utilise `keepalive` en défense de second rang,
     * mais l'attente est ce qui rend le comportement déterministe.
     *
     * <p>L'échec (hors ligne, jeton déjà invalide) reste ignoré : la déconnexion
     * locale prime.
     *
     * @param {string|null|undefined} token le JWT à révoquer
     * @returns {Promise<void>} toujours résolue, jamais rejetée
     */
    revokeOnServer(token) {
      if (!token) return Promise.resolve()
      return logoutUser(token).catch(() => {})
    },

    /**
     * Déconnexion au sens du store : purge locale puis révocation. Conservée
     * telle quelle après la Story 1.9 — l'hygiène complète (stores, file
     * IndexedDB, cache de lecture) appartient à `endSession({reason:'logout'})`
     * de `stores/session.js`, qui appelle les deux moitiés ci-dessus dans le bon
     * ordre. Cette action reste le chemin correct pour tout appelant qui n'a que
     * la session à fermer.
     *
     * @returns {Promise<void>} toujours résolue, jamais rejetée
     */
    logout() {
      const revokedToken = this.token
      this.clearSession()
      return this.revokeOnServer(revokedToken)
    },
  },
})
