import { defineStore } from 'pinia'
import { loginUser, registerUser, logoutUser, verifyEmail, resendVerification } from '@/api/auth'
import type { LoginPayload, RegisterPayload, VerifyEmailPayload } from '@/api/auth'
import { TOKEN_STORAGE_KEY } from '@/api/client'
import { readCredential, writeCredential, removeCredential } from '@/utils/credentialStorage'
import { beginSession } from './session'
import { apiErrorHeader, apiErrorMessage } from '@/utils/apiError'
import type { Role, Session, User } from '@/types/domain'

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

/**
 * Le profil relu depuis localStorage n'est PAS digne de confiance.
 *
 * <p>`JSON.parse` rend `any` : le typer `User` d'autorité ferait croire au compilateur
 * qu'un `role` valide est garanti, alors que la valeur vient d'un stockage que
 * l'utilisateur peut éditer. Le ledger de la Story 1.9 documente déjà le cas réel
 * (`escrow_user` illisible pendant que `escrow_token` survit), et le routeur a une
 * branche « session incohérente » pour ça. On rend donc `User | null` après une
 * vérification minimale de forme, plutôt qu'une promesse que rien ne tient.
 */
function loadStoredUser(): User | null {
  try {
    const raw = readCredential(USER_STORAGE_KEY)
    if (!raw) return null
    const parsed: unknown = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    return parsed as User
  } catch {
    return null
  }
}

interface AuthState {
  token: string | null
  user: User | null
  loading: boolean
  error: string | null
}

/**
 * LES ÉTATS DU JETON (Story 2.7, AC5) — et pourquoi ce n'est pas un booléen.
 *
 * <p><b>Le défaut que cette énumération ferme.</b> `loadStoredUser()` rend `null` sur tout
 * échec de `JSON.parse` ALORS QUE LE JETON SURVIT. `isAuthenticated` valant
 * `Boolean(token)`, la garde du routeur laisse passer et l'API fonctionne ; mais la file
 * hors-ligne lisait `user?.id == null` comme « personne n'est connecté », c'est-à-dire
 * qu'elle confondait deux états que rien ne permettait de distinguer :
 * <ul>
 *   <li>`'anonymous'` — pas de jeton. Il n'y a personne, et c'est normal.</li>
 *   <li>`'incoherent'` — un jeton, et un profil qu'on ne sait pas lire. Il y a
 *       quelqu'un, mais on ne sait pas QUI.</li>
 * </ul>
 * Les deux conséquences de la confusion sont réelles : `RecoveryView` annonçait « rien à
 * récupérer » pendant que les preuves dormaient en IndexedDB, et chaque mise en file
 * estampillait `userId: undefined` — des entrées orphelines, que la déconnexion suivante
 * supprime (`offlineQueue.idb.ts:135-141`).
 *
 * <p><b>Une énumération et non un second booléen.</b> La Story 2.4 introduit un QUATRIÈME
 * état — compte non vérifié — et un `isIncoherent` posé à côté d'`isAuthenticated` aurait
 * obligé à le réécrire. Ici il s'insère entre deux branches existantes, à un seul endroit
 * (voir le repère ci-dessous), sans qu'aucun appelant n'ait à changer de forme.
 *
 * <p><b>Ce que cet état ne dit PAS.</b> Il décrit la paire jeton/profil, jamais un droit
 * d'accès. La branche « session incohérente » du routeur (`router/index.ts`, Story 2.3)
 * pose la MÊME question sur un autre champ — `spaceForRole(user?.role) === null`, parce
 * que le routeur a besoin d'un rôle routable, là où la file a besoin d'un identifiant de
 * propriétaire. Les deux prédicats ne sont pas interchangeables et ne doivent pas être
 * fusionnés : un rôle inconnu d'un profil par ailleurs lisible est une question
 * d'autorisation, pas de cohérence. C'est le vocabulaire qui est commun, pas la condition.
 */
export type SessionState = 'anonymous' | 'active' | 'incoherent'

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: readCredential(TOKEN_STORAGE_KEY) || null,
    user: loadStoredUser(),
    loading: false,
    error: null,
  }),

  getters: {
    /**
     * INCHANGÉ, délibérément : « un jeton est présent », et rien de plus.
     *
     * <p>Le rendre faux sur une session incohérente aurait été le raccourci tentant et
     * c'est le mauvais. Le jeton EST valide côté serveur : les appels API aboutissent, et
     * une garde de routeur qui refuserait de le voir renverrait l'utilisateur vers un
     * écran de connexion sans jamais dire pourquoi — tout en laissant `client.ts`
     * continuer à envoyer l'en-tête `Authorization`. Ce qui manque n'est pas
     * l'authentification, c'est le profil : `sessionState` le dit, celui-ci n'a pas à
     * mentir pour le compenser.
     */
    isAuthenticated: (state): boolean => Boolean(state.token),
    role: (state): Role | null => state.user?.role || null,

    /**
     * L'état de la paire jeton/profil — voir la documentation de `SessionState`.
     *
     * <p>Aucune table de correspondance ici, et c'est volontaire : un objet littéral
     * hérite d'`Object.prototype`, si bien qu'une table indexée par une valeur venue du
     * stockage rendrait la fonction `Object` pour `'constructor'` — le défaut qui, dans
     * `spaceForRole`, a fini en ENFERMEMENT hors de `/auth`. Trois conditions écrites en
     * toutes lettres ne peuvent pas être indexées par une clé hostile.
     */
    sessionState: (state): SessionState => {
      // Pas de jeton : personne, quel que soit ce qui traîne dans `escrow_user`.
      if (!state.token) return 'anonymous'

      // ⚠️ REPÈRE POUR LA STORY 2.4 — le quatrième état s'insère ICI, entre « le profil
      // est lisible » et « la session est utilisable » :
      //     if (state.user.emailVerified === false) return 'unverified'
      // Il suppose un profil lisible, donc il vient après la condition ci-dessous et
      // avant le `return 'active'`. Aucun appelant n'a à changer de forme pour lui.

      // Un jeton, mais pas de propriétaire nommable. `user` peut être `null` (JSON
      // illisible, valeur non-objet) comme un objet sans `id` — les deux produisent
      // exactement le même dégât en aval, et les distinguer ici ne servirait personne.
      if (state.user?.id == null) return 'incoherent'

      return 'active'
    },
  },

  actions: {
    /**
     * Story 2.7 : passe par le substrat commutable, jamais par `localStorage` en direct.
     *
     * <p>Le profil suit le jeton dans le MÊME substrat, et ce n'est pas un détail
     * d'implémentation : laisser `escrow_user` en `localStorage` pendant que le jeton
     * meurt avec l'onglet donnerait à la personne suivante un profil sans jeton — l'état
     * « incohérent » que l'AC5 existe justement pour traiter, fabriqué à chaque
     * fermeture d'onglet.
     */
    persist(): void {
      if (this.token) writeCredential(TOKEN_STORAGE_KEY, this.token)
      else removeCredential(TOKEN_STORAGE_KEY)

      if (this.user) writeCredential(USER_STORAGE_KEY, JSON.stringify(this.user))
      else removeCredential(USER_STORAGE_KEY)
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
     */
    applySession({ token, user }: Session): Promise<void> {
      this.token = token
      this.user = user
      this.persist()
      return beginSession(user?.id)
    },

    async login(credentials: LoginPayload): Promise<boolean> {
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
        this.error = apiErrorMessage(err) || 'Invalid email or password.'
        return false
      } finally {
        this.loading = false
      }
    },

    /**
     * Inscription (Story 2.4). NE POSE PAS de session.
     *
     * <p>Avant cette story, `register` enchaînait sur `applySession` : le compte était
     * connecté sur-le-champ. Le serveur ne rend plus de jeton — le compte naît non vérifié
     * — et l'appelant doit passer par l'écran de saisie du code. Rendre `true` ici signifie
     * « demande acceptée », pas « connecté » : c'est `verify` qui ouvre la session.
     */
    async register(payload: RegisterPayload): Promise<boolean> {
      this.loading = true
      this.error = null
      try {
        await registerUser(payload)
        return true
      } catch (err) {
        this.error = apiErrorMessage(err) || 'Registration failed. Please try again.'
        return false
      } finally {
        this.loading = false
      }
    },

    /** Saisie du code (AC2) : le seul endroit du parcours d'inscription qui ouvre une session. */
    async verify({ email, code }: VerifyEmailPayload): Promise<boolean> {
      this.loading = true
      this.error = null
      try {
        const session = await verifyEmail({ email, code })
        // Attendu, pour la même raison qu'au login : sur un appareil partagé,
        // `beginSession` purge le cache de lecture du précédent utilisateur, et
        // « avant tout rendu » n'est vrai que si la vue attend encore ici.
        await this.applySession(session)
        return true
      } catch (err) {
        this.error = apiErrorMessage(err)
        return false
      } finally {
        this.loading = false
      }
    },

    /**
     * Renvoi du code (AC4).
     *
     * <p>Rend le délai avant le prochain envoi autorisé, en secondes, ou `0`. La valeur
     * vient de l'en-tête `Retry-After` du SERVEUR (AD-11) : un compte à rebours calculé
     * localement se remettrait à zéro au rechargement de la page.
     *
     */
    async resend({ email }: { email: string }): Promise<{ ok: boolean; retryAfterSeconds: number }> {
      this.error = null
      try {
        await resendVerification({ email })
        return { ok: true, retryAfterSeconds: 0 }
      } catch (err) {
        const header = apiErrorHeader(err, 'Retry-After')
        const parsed = Number(header)
        // `Number.isFinite` et non un `||` : `Number(undefined)` vaut NaN, et un NaN
        // propagé jusqu'à l'affichage produirait un compte à rebours « NaN s » sans
        // qu'aucune exception ne soit levée.
        return { ok: false, retryAfterSeconds: Number.isFinite(parsed) && parsed > 0 ? Math.ceil(parsed) : 0 }
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
    clearSession(): void {
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
     * <p>Toujours résolue, jamais rejetée.
     */
    revokeOnServer(token: string | null | undefined): Promise<void> {
      if (!token) return Promise.resolve()
      return logoutUser(token).catch(() => {})
    },

    /**
     * Purge locale puis révocation — et **rien d'autre**.
     *
     * <p>⚠️ N'EST PLUS LE CHEMIN DE DÉCONNEXION DE L'APPLICATION depuis la Story
     * 1.9. Le seul point de sortie correct est `endSession({reason:'logout'})`
     * (`stores/session.js`), qui appelle les deux moitiés ci-dessus dans le bon
     * ordre PUIS remet à zéro les stores `escrow`/`evidence`, vide la file en
     * mémoire, supprime les entrées IndexedDB du partant et détruit le cache de
     * lecture. Câbler un nouveau bouton de déconnexion ici ne nettoierait que
     * les deux clés de localStorage et rouvrirait en silence les quatre reports
     * du ledger que cette story a fermés — la suite de tests resterait verte.
     *
     * <p>Conservée parce qu'elle est la brique testée du store (`auth.spec.js`)
     * et l'enchaînement que `endSession` reproduit ; aucun appelant de
     * production ne l'utilise (`DashboardView.vue` passe par `endSession`).
     *
     * <p>Toujours résolue, jamais rejetée.
     */
    logout(): Promise<void> {
      const revokedToken = this.token
      this.clearSession()
      return this.revokeOnServer(revokedToken)
    },
  },
})
