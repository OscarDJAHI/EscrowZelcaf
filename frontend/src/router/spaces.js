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

export const SPACES = Object.freeze({
  CLIENT: 'client',
  ARBITRATION: 'arbitration',
  ADMIN: 'admin',
})

/**
 * Rôle → espace. Tout rôle absent de cette table n'a AUCUN espace.
 *
 * <p>`ARBITRATOR` y figure alors qu'il n'existe pas encore dans l'énumération backend
 * (`Role.java` vaut `{BUYER, SELLER, ADMIN}`) : son octroi est porté par la Story 7-2,
 * et AD-21 interdit de l'attribuer autrement. La console est donc livrée avec son guard
 * mais reste INATTEIGNABLE jusque-là. Décrire le mapping ici est correct ; l'ajouter à
 * l'énumération serveur sans son mécanisme d'octroi audité ne le serait pas.
 */
const SPACE_BY_ROLE = Object.freeze({
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
export const DESKTOP_NAV = Object.freeze({
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
export const HOME_BY_SPACE = Object.freeze({
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
export function spaceForRole(role) {
  return SPACE_BY_ROLE[role] ?? null
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
export function resolveSpaceAccess(role, space) {
  return spaceForRole(role) === space && space !== undefined ? { allowed: true } : { allowed: false }
}
