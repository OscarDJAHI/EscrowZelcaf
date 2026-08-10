/**
 * Le substrat de stockage des identifiants de session (Story 2.7, AC1, décision D4).
 *
 * <p><b>Pourquoi ce module existe.</b> Avant cette story, le jeton et le profil vivaient
 * en dur dans `localStorage`, lus depuis TROIS endroits (`api/client.ts` ×2,
 * `stores/auth.ts`) et écrits depuis un. Un jeton en `localStorage` survit à la fermeture
 * de l'onglet ET au redémarrage du navigateur : sur un poste partagé, la personne suivante
 * rouvrait l'application et était silencieusement authentifiée à la place de la
 * précédente, pendant les 24 h du TTL. C'est le mode de défaillance DOMINANT de NFR-P8,
 * et la décision D4 le ferme à la racine — le jeton meurt avec l'onglet par défaut.
 *
 * <p><b>Une seule indirection, et c'est le point.</b> Un substrat commutable dont trois
 * appelants gardent un accès direct n'est pas commutable ; il est commutable pour deux
 * d'entre eux et cassé pour le troisième, sans que rien ne rougisse. Tous les accès aux
 * identifiants passent désormais par ici.
 *
 * <p><b>Ce module ne connaît AUCUNE clé métier.</b> Il ne sait pas ce qu'est un jeton ni
 * un profil ; `TOKEN_STORAGE_KEY` et `USER_STORAGE_KEY` restent chez leurs propriétaires.
 * Il ne répond qu'à une question : « dans quel stockage, et comment sans lever ».
 */

/**
 * Où vit la PRÉFÉRENCE elle-même — en `localStorage`, obligatoirement.
 *
 * <p>La ranger en `sessionStorage` la ferait mourir avec l'onglet, donc l'option
 * « rester connecté » serait vraie pendant la session et fausse après : exactement
 * l'inverse de ce qu'elle promet. Elle ne porte aucune donnée personnelle — deux valeurs
 * possibles, ni identité ni horodatage — et n'est donc pas concernée par la purge de
 * session.
 */
export const PERSISTENCE_PREFERENCE_KEY = 'escrow_persist'

export type CredentialPersistence = 'session' | 'local'

/**
 * `session` par défaut : c'est la décision D4. Un appareil dont on ne sait rien est
 * traité comme un appareil partagé.
 */
export const DEFAULT_PERSISTENCE: CredentialPersistence = 'session'

/**
 * L'accès à la propriété est fait ICI, dans le `try` de l'appelant, jamais en paramètre
 * par défaut.
 *
 * <p>Leçon reprise telle quelle de `i18n/index.ts` (revue Story 2.1) : écrit
 * `fn(storage = globalThis.localStorage)`, l'accès est évalué AVANT le corps de la
 * fonction, donc avant son `try` — et sous Safari « bloquer tous les cookies », où la
 * simple LECTURE de la propriété lève, l'exception échappait au garde-fou et l'application
 * ne démarrait pas. Page blanche, avec un commentaire promettant le contraire.
 */
function store(kind: CredentialPersistence): Storage | undefined {
  return kind === 'local' ? globalThis.localStorage : globalThis.sessionStorage
}

/** Le substrat qui n'est PAS actif — celui qu'il faut tenir vide. */
function other(kind: CredentialPersistence): CredentialPersistence {
  return kind === 'local' ? 'session' : 'local'
}

/**
 * La préférence en vigueur sur cet appareil.
 *
 * <p>Toute valeur qui n'est pas exactement `'local'` retombe sur `session` : devant une
 * valeur qu'on ne comprend pas — stockage édité à la main, format d'une version future —
 * la direction sûre est la persistance la plus COURTE. Accorder la plus longue sur un mot
 * non reconnu rouvrirait le défaut que cette story ferme.
 */
export function readPersistence(): CredentialPersistence {
  try {
    return globalThis.localStorage?.getItem(PERSISTENCE_PREFERENCE_KEY) === 'local'
      ? 'local'
      : DEFAULT_PERSISTENCE
  } catch {
    return DEFAULT_PERSISTENCE
  }
}

/** Enregistre le choix « rester connecté ». Un stockage indisponible ne casse pas la connexion. */
export function writePersistence(persistence: CredentialPersistence): void {
  try {
    globalThis.localStorage?.setItem(PERSISTENCE_PREFERENCE_KEY, persistence)
  } catch {
    // La session reste utilisable avec le défaut ; une préférence non persistée
    // dégrade le confort, jamais la sécurité (le défaut est le mode le plus court).
  }
}

/**
 * Lit un identifiant dans le substrat ACTIF, et seulement lui.
 *
 * <p><b>Aucun repli sur l'autre substrat.</b> Un repli ressusciterait précisément la
 * session que l'AC1 promet de laisser mourir : après la fermeture de l'onglet, le
 * `sessionStorage` est vide et c'est le résultat voulu — aller chercher ailleurs
 * annulerait la mesure.
 */
export function readCredential(key: string): string | null {
  try {
    return store(readPersistence())?.getItem(key) ?? null
  } catch {
    return null
  }
}

/**
 * Écrit dans le substrat actif **et vide l'autre**.
 *
 * <p>Le second geste n'est pas de la propreté, c'est de l'hygiène d'appareil : sans lui,
 * un utilisateur ayant coché « rester connecté » puis un suivant ne l'ayant pas coché
 * laisseraient un jeton abandonné en `localStorage`. Il ne serait plus lu — jusqu'à la
 * prochaine bascule de préférence, qui le rendrait de nouveau lisible. Un identifiant
 * oublié dans un substrat inactif reste un identifiant sur l'appareil.
 */
export function writeCredential(key: string, value: string): void {
  const active = readPersistence()
  try {
    store(active)?.setItem(key, value)
  } catch {
    // Voir `readCredential` : un stockage indisponible n'est pas une raison d'échouer
    // l'authentification en cours, qui reste valide en mémoire pour la durée de l'onglet.
  }
  try {
    store(other(active))?.removeItem(key)
  } catch {
    // Idem — et l'échec ici ne laisse au pire que l'état d'avant.
  }
}

/**
 * Retire la clé des **DEUX** substrats, quelle que soit la préférence courante.
 *
 * <p>Délibérément asymétrique avec la lecture. Cette fonction s'exécute quand on ne veut
 * plus rien laisser derrière soi ; la faire dépendre de la préférence en vigueur
 * laisserait intact le stockage inactif, c'est-à-dire exactement l'endroit qu'un
 * changement de préférence en cours de route a pu peupler.
 */
export function removeCredential(key: string): void {
  for (const kind of ['local', 'session'] as const) {
    try {
      store(kind)?.removeItem(key)
    } catch {
      // Chaque substrat dans son propre `try` : l'indisponibilité de l'un ne doit pas
      // empêcher la purge de l'autre. Même règle que le `try/catch`-par-effet
      // d'`endSession`.
    }
  }
}
