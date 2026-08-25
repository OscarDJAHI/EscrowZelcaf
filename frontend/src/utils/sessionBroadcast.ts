/**
 * La propagation d'une fin de session entre les onglets d'un MÊME appareil
 * (Story 2.7, AC3 — décision D-B).
 *
 * <p><b>Pourquoi `BroadcastChannel` et pas un écouteur `storage`.</b> L'évidence du ledger
 * (entrée E1) proposait « un écouteur `storage` sur la clé de jeton ». Ce correctif est
 * périmé par la décision D4 elle-même : l'événement `storage` ne se déclenche PAS entre
 * onglets pour `sessionStorage`, qui est cloisonné par onglet — et `sessionStorage` est
 * désormais le substrat par DÉFAUT (AC1). Le chemin par défaut n'aurait donc émis aucun
 * événement, et la propagation n'aurait fonctionné que pour les postes « rester connecté ».
 * Une mesure qui ne s'applique pas au mode appareil partagé n'est pas une mesure de
 * politique d'appareil partagé.
 *
 * <p><b>LA RÈGLE D'AUTORITÉ ENTRE ONGLETS — le commentaire que l'AC3 exige.</b> L'onglet
 * où l'action a lieu est l'ÉMETTEUR UNIQUE de la révocation serveur. Les onglets
 * récepteurs exécutent une terminaison PUREMENT LOCALE : ils n'appellent ni `logoutUser`
 * ni aucun autre endpoint, et ne retouchent pas l'état partagé de l'appareil (IndexedDB,
 * cache de lecture Workbox, marqueur `escrow_last_user`) que l'émetteur vient déjà de
 * traiter selon la raison. Motif : NFR-P2 limite le débit de `/auth/*`, et N onglets
 * produisant N révocations transformeraient une déconnexion ordinaire en rafale
 * anti-bruteforce dirigée contre l'utilisateur lui-même. Ce que le récepteur DOIT faire,
 * en revanche, c'est vider ses propres identifiants : par l'AC1 ils vivent en
 * `sessionStorage`, donc chaque onglet en détient sa PROPRE copie et la purge de
 * l'émetteur ne l'atteint pas. C'est là toute la raison d'être de ce canal.
 *
 * <p><b>Un seul objet `BroadcastChannel` par onglet, et c'est load-bearing.</b> La
 * spécification garantit qu'un canal ne reçoit jamais ses propres messages — mais elle ne
 * garantit rien entre DEUX objets du même onglet : deux canaux ouverts ici et l'onglet
 * s'entendrait lui-même, déclencherait sa propre terminaison en pleine terminaison, et le
 * garde d'authentification ne serait plus qu'une question de calendrier. D'où l'instance
 * unique ci-dessous, partagée par l'émission et par la réception.
 *
 * <p><b>Ce module ne connaît pas le vocabulaire des raisons</b>, exactement comme
 * `credentialStorage` ignore les clés métier et `idleTimeout` ignore ce qu'est une
 * session : il transporte une chaîne, `stores/session.ts` décide de ce qu'elle veut dire.
 * C'est aussi ce qui le rend importable de partout sans rouvrir le cycle
 * `router → stores/auth → api/auth → api/client`.
 *
 * <p><b>Ce que cette propagation NE couvre PAS — écart nommé, pas coché.</b> Elle est
 * LOCALE À L'APPAREIL. `BroadcastChannel` ne franchit ni l'origine ni la machine : une
 * révocation déclenchée côté serveur depuis un AUTRE appareil (changement de mot de passe,
 * Story 2.6 / NFR-P5) ne produit aucun message ici. Ce cas-là se détecte par le 403 nu au
 * prochain appel API — le chemin `escrow:session-expired` qui existe déjà — et pas
 * autrement. Ne pas écrire « toute fin de session se propage » : c'est faux.
 */

/**
 * Le nom du canal, tranché par la décision D-B. Même origine uniquement : seule notre
 * propre application peut y écrire.
 */
export const SESSION_CHANNEL_NAME = 'escrow-session'

/**
 * Le discriminant du message. Il n'est pas décoratif : le canal porte un nom générique,
 * et une story ultérieure qui y ferait transiter autre chose (une synchronisation de
 * langue, un compteur) verrait ses messages lus comme des fins de session par tous les
 * onglets ouverts. Un message sans ce champ est IGNORÉ.
 */
const SESSION_END = 'session-end'

export interface SessionEndMessage {
  type: typeof SESSION_END
  /** `null` quand l'appelant n'en avait pas — le récepteur retombe alors sur le chemin conservateur. */
  reason: string | null
}

let channel: BroadcastChannel | null = null
let onEnd: ((reason: string | undefined) => void) | null = null

/**
 * Ouvre le canal de l'onglet, ou rend `null` — REPLI SILENCIEUX, jamais une exception.
 *
 * <p>`BroadcastChannel` manque encore à quelques navigateurs de corridor et à tout
 * contexte non-DOM. La propagation DÉGRADE alors — chaque onglet garde sa minuterie
 * d'inactivité, son contrôle au démarrage et son 403 nu — mais rien ne casse : une
 * déconnexion qui lèverait parce qu'une API optionnelle manque serait un défaut bien pire
 * que l'absence de propagation.
 */
function openChannel(): BroadcastChannel | null {
  if (channel) return channel
  if (typeof BroadcastChannel === 'undefined') return null
  try {
    const opened = new BroadcastChannel(SESSION_CHANNEL_NAME)
    // Posé À L'OUVERTURE et non dans `subscribeSessionEnd` : un onglet qui n'a fait
    // qu'émettre garde alors un gestionnaire inerte (`onEnd` vaut `null`), ce qui coûte
    // une comparaison — là où deux chemins d'installation distincts coûteraient un jour
    // un canal ouvert sans écouteur, muet et impossible à distinguer d'un canal absent.
    opened.onmessage = handleMessage
    channel = opened
  } catch {
    // Stockage cloisonné, contexte non sécurisé, quota d'objets : mêmes conséquences
    // qu'une API absente, même traitement.
    channel = null
  }
  return channel
}

function handleMessage(event: MessageEvent): void {
  const data: unknown = event.data
  if (!data || typeof data !== 'object') return
  const message = data as Partial<SessionEndMessage>
  if (message.type !== SESSION_END) return
  // Une raison qui n'est pas une chaîne est rendue INDÉFINIE plutôt que recopiée telle
  // quelle : `endSession` traite déjà explicitement la raison inconnue (chemin
  // conservateur + diagnostic), et lui passer un objet ou un nombre reviendrait à lui
  // faire décrire dans son journal une valeur qu'il n'a pas reçue d'un appelant.
  onEnd?.(typeof message.reason === 'string' ? message.reason : undefined)
}

/**
 * Annonce aux AUTRES onglets que la session s'achève ici.
 *
 * <p>Ne rend rien et ne lève jamais : l'appelant est `endSession`, dont le contrat est de
 * ne jamais rejeter. Un canal indisponible n'est pas une raison de laisser un utilisateur
 * connecté sur l'appareil qu'il vient de rendre.
 *
 * <p>L'onglet émetteur ne s'entend PAS lui-même (voir l'invariant d'instance unique en
 * tête de fichier) : ce message ne part que vers les autres.
 */
export function publishSessionEnd(reason: string | null | undefined): void {
  const bus = openChannel()
  if (!bus) return
  const message: SessionEndMessage = {
    type: SESSION_END,
    reason: typeof reason === 'string' ? reason : null,
  }
  try {
    bus.postMessage(message)
  } catch (err) {
    console.error('[session] could not announce the end of session to the other tabs', err)
  }
}

/**
 * Écoute les fins de session annoncées par les autres onglets. Rend la fonction de retrait.
 *
 * <p>UN SEUL abonné, et un second appel remplace le premier — même parti pris que
 * `startIdleWatch`, qui désinstalle avant d'installer. Deux abonnés voudraient dire deux
 * terminaisons concurrentes pour un seul message, donc deux navigations en course : la
 * ceinture ne double pas la bretelle ici, elle la contredit.
 */
export function subscribeSessionEnd(handler: (reason: string | undefined) => void): () => void {
  onEnd = handler
  openChannel()
  return () => {
    if (onEnd === handler) onEnd = null
  }
}

/**
 * Ferme le canal et oublie l'abonné.
 *
 * <p>Aucun appelant de production : un onglet vivant garde son canal ouvert pour la durée
 * de sa vie, comme il garde ses écouteurs `pointerdown`. C'est la suite de tests qui en a
 * besoin — sans elle, chaque fichier laisserait derrière lui des canaux qui continueraient
 * d'entendre les messages du suivant, et un test deviendrait dépendant de son
 * prédécesseur. Même rôle que `stopIdleWatch`.
 */
export function closeSessionBroadcast(): void {
  onEnd = null
  if (!channel) return
  try {
    channel.close()
  } catch {
    // Déjà fermé : rien à faire, et surtout rien à signaler.
  }
  channel = null
}
