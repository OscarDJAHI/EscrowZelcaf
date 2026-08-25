/**
 * Les trois espaces de la PWA, et la seule règle qui décide qui entre où.
 *
 * <p>Une seule application sert l'app client, la console d'arbitrage et le back-office,
 * sous la MÊME authentification (UX-DR20). AD-21 est catégorique sur le critère :
 * « Le routage des trois espaces se fonde <b>exclusivement</b> sur ce rôle. »
 *
 * <p><b>Pourquoi ce module est pur.</b> La décision d'accès est la surface de sécurité de
 * cette story. La sortir du routeur permet de l'asservir sans monter d'application, et
 * empêche qu'elle se dilue en conditions éparpillées dans des gardes successives — c'est
 * la même raison qui a fait centraliser les fabriques d'exceptions côté backend en
 * Story 1.10, après qu'un oracle d'énumération eut survécu à plusieurs revues.
 */
import type { Role } from '@/types/domain'

export const SPACES = Object.freeze({
  CLIENT: 'client',
  ARBITRATION: 'arbitration',
  ADMIN: 'admin',
})

/** L'un des trois espaces. */
export type Space = (typeof SPACES)[keyof typeof SPACES]

/**
 * Rôle → espace. Tout rôle absent de cette table n'a AUCUN espace.
 *
 * <p>`ARBITRATOR` y figure alors qu'il n'existe pas encore dans l'énumération backend
 * (`Role.java` vaut `{BUYER, SELLER, ADMIN}`) : son octroi est porté par la Story 7-2,
 * et AD-21 interdit de l'attribuer autrement. La console est donc livrée avec son guard
 * mais reste INATTEIGNABLE jusque-là. Décrire le mapping ici est correct ; l'ajouter à
 * l'énumération serveur sans son mécanisme d'octroi audité ne le serait pas.
 */
const SPACE_BY_ROLE: Readonly<Partial<Record<Role, Space>>> = Object.freeze({
  BUYER: SPACES.CLIENT,
  SELLER: SPACES.CLIENT,
  ARBITRATOR: SPACES.ARBITRATION,
  ADMIN: SPACES.ADMIN,
})

/**
 * Navigation des deux espaces DESKTOP, déclarée ici et non dans leurs vues.
 *
 * <p>Tant qu'un espace n'a qu'un écran, l'écrire dans la vue paraît équivalent. Ça cesse
 * de l'être au deuxième : la navigation devient alors la propriété de chaque vue, et une
 * vue qui oublie de l'inclure s'affiche sans aucun moyen d'en sortir. L'espace client
 * illustrait déjà le défaut — son shell existait, aucune de ses sept routes ne le rendait.
 */
export interface DesktopNav {
  titleKey: string
  navKeys: readonly string[]
}

/**
 * `Partial` : l'espace CLIENT n'y figure pas, et c'est le fond du sujet — il a son propre
 * shell mobile. Un `Record` complet aurait obligé à inventer une entrée pour lui.
 */
export const DESKTOP_NAV: Readonly<Partial<Record<Space, DesktopNav>>> = Object.freeze({
  [SPACES.ARBITRATION]: Object.freeze({
    titleKey: 'space.arbitration',
    navKeys: Object.freeze(['nav.disputeQueue', 'nav.decisions']),
  }),
  [SPACES.ADMIN]: Object.freeze({
    titleKey: 'space.admin',
    navKeys: Object.freeze(['nav.users', 'nav.kybQueue', 'nav.supervision']),
  }),
})

/** Porte d'entrée de chaque espace, utilisée pour rediriger après connexion. */
export const HOME_BY_SPACE: Readonly<Record<Space, string>> = Object.freeze({
  [SPACES.CLIENT]: 'dashboard',
  [SPACES.ARBITRATION]: 'arbitration-home',
  [SPACES.ADMIN]: 'admin-home',
})

/**
 * Espace d'un rôle, ou `null`.
 *
 * <p>`null` et non un repli sur l'espace client : un rôle non reconnu — client périmé,
 * profil trafiqué, rôle ajouté côté serveur sans être déclaré ici — ne doit pas obtenir
 * un accès par DÉFAUT. Accorder sur une absence de décision est la façon dont les
 * privilèges fuient.
 */
export function spaceForRole(role: string | null | undefined): Space | null {
  // `Object.hasOwn` et non une simple lecture indexée : `SPACE_BY_ROLE` est un objet
  // littéral, il HÉRITE donc d'`Object.prototype`. `SPACE_BY_ROLE['constructor']` rend la
  // fonction `Object`, `SPACE_BY_ROLE['toString']` une méthode — et `?? null` ne rattrape
  // ni l'une ni l'autre, une fonction n'étant ni `null` ni `undefined`. Le contrat annoncé
  // juste au-dessus était donc faux pour toute une famille de valeurs.
  //
  // L'accès n'a jamais fuité pour autant : aucune de ces valeurs n'égale un nom d'espace,
  // et `resolveSpaceAccess` refusait déjà. Le dégât était l'inverse d'un privilège — un
  // ENFERMEMENT. `space` cessant d'être `null`, le routeur sautait sa branche « session
  // incohérente » et `/auth` lui-même finissait sur l'écran de refus : plus aucun moyen
  // de rejoindre la connexion pour réparer le profil.
  return role != null && Object.hasOwn(SPACE_BY_ROLE, role)
    ? (SPACE_BY_ROLE[role as Role] ?? null)
    : null
}

/**
 * Décide l'accès d'un rôle à un espace, avec une réponse STRICTEMENT UNIFORME.
 *
 * <p>Le refus ne porte ni motif, ni nom d'espace, ni rôle — et il est identique, au sens
 * de l'égalité profonde, pour un espace interdit et pour un espace qui n'existe pas.
 * C'est NFR-P9 : un utilisateur ne doit pas pouvoir distinguer « cela n'existe pas » de
 * « cela existe mais pas pour vous ». Enrichir cet objet d'un `reason` pour améliorer un
 * message d'erreur rouvrirait l'oracle — le message se choisit à l'affichage, pas ici.
 */
export function resolveSpaceAccess(
  role: string | null | undefined,
  space: Space | null | undefined,
): { allowed: boolean } {
  return spaceForRole(role) === space && space !== undefined ? { allowed: true } : { allowed: false }
}
