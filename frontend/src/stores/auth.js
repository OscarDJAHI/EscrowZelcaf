import { defineStore } from 'pinia'
import { loginUser, registerUser, logoutUser } from '@/api/auth'
import { TOKEN_STORAGE_KEY } from '@/api/client'

const USER_STORAGE_KEY = 'escrow_user'

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

    applySession({ token, user }) {
      this.token = token
      this.user = user
      this.persist()
    },

    async login(credentials) {
      this.loading = true
      this.error = null
      try {
        const session = await loginUser(credentials)
        this.applySession(session)
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
        this.applySession(session)
        return true
      } catch (err) {
        this.error = err.response?.data?.message || 'Registration failed. Please try again.'
        return false
      } finally {
        this.loading = false
      }
    },

    /**
     * Déconnexion (Story 1.6). Le vidage local reste SYNCHRONE et immédiat — la
     * déconnexion client est garantie même si le réseau est mort — mais la méthode
     * retourne désormais une promesse que l'appelant DOIT attendre avant de
     * naviguer (revue 1.6).
     *
     * <p>La révocation serveur était auparavant lâchée en microtâche sans être
     * attendue : le bouton de déconnexion enchaînant sur `window.location`, le
     * navigateur avortait la requête et le jeton restait valide côté serveur
     * jusqu'à son expiration. `logoutUser` utilise `keepalive` en défense de
     * second rang, mais l'attente est ce qui rend le comportement déterministe.
     *
     * <p>L'échec (hors ligne, jeton déjà invalide) reste ignoré : la déconnexion
     * locale prime. L'hygiène approfondie (file offline, appareil partagé) relève
     * de la Story 1.9.
     *
     * @returns {Promise<void>} toujours résolue, jamais rejetée
     */
    logout() {
      const revokedToken = this.token
      this.token = null
      this.user = null
      this.persist()
      if (!revokedToken) return Promise.resolve()
      return logoutUser(revokedToken).catch(() => {})
    },
  },
})
