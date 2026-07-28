import { describe, expect, it } from 'vitest'
import { HOME_BY_SPACE, SPACES, resolveSpaceAccess, spaceForRole } from '@/router/spaces'

/**
 * AC1 — trois espaces sous une seule authentification, et des guards qui ne DISENT RIEN.
 *
 * <p>Le cœur de sécurité de cette story tient en une propriété : un utilisateur ne doit
 * pas pouvoir distinguer « cet espace n'existe pas » de « il existe mais pas pour vous ».
 * C'est NFR-P9, la même exigence que la Story 1.10 a passée entière à établir côté API —
 * un `403` distinct d'un `404` y donnait un oracle d'énumération parfait.
 *
 * <p>La logique vit dans un module PUR, hors du routeur : elle est ainsi asservissable
 * sans monter d'application, et le routeur ne fait que l'appliquer.
 */

describe('spaceForRole — le routage se fonde EXCLUSIVEMENT sur le rôle (AD-21)', () => {
  it('envoie acheteur et vendeur vers l’espace client', () => {
    expect(spaceForRole('BUYER')).toBe(SPACES.CLIENT)
    expect(spaceForRole('SELLER')).toBe(SPACES.CLIENT)
  })

  it('envoie l’administrateur vers le back-office', () => {
    expect(spaceForRole('ADMIN')).toBe(SPACES.ADMIN)
  })

  it('envoie l’arbitre vers la console d’arbitrage', () => {
    // ⚠️ Ce rôle n'existe PAS encore dans l'énumération backend : son octroi appartient à
    // la Story 7-2 (AD-21). Le mapping est livré et testé ; l'espace reste inatteignable
    // en pratique tant que personne ne peut porter le rôle. C'est documenté, pas masqué.
    expect(spaceForRole('ARBITRATOR')).toBe(SPACES.ARBITRATION)
  })

  it('n’accorde AUCUN espace à un rôle inconnu ou absent', () => {
    // Un rôle non reconnu ne doit pas retomber sur l'espace client « par défaut » : ce
    // serait accorder un accès sur une absence de décision.
    for (const role of [null, undefined, '', 'ROOT', 'buyer']) {
      expect(spaceForRole(role), `rôle ${JSON.stringify(role)}`).toBeNull()
    }
  })
})

describe('Rôles issus de la chaîne de prototypes', () => {
  // `SPACE_BY_ROLE` est un objet littéral : il HÉRITE d'`Object.prototype`. Une lecture
  // par `SPACE_BY_ROLE[role]` avec `role = 'constructor'` rend donc la fonction `Object`,
  // pas `undefined` — et `?? null` ne rattrape rien, une fonction n'étant ni l'un ni
  // l'autre. Le contrat « rôle non reconnu → aucun espace » est alors muet.
  //
  // L'accès reste refusé (aucune de ces valeurs n'égale un nom d'espace), mais le dégât
  // est ailleurs : `space` cessant d'être `null`, la branche « session incohérente » du
  // routeur est sautée, et l'utilisateur ne peut plus atteindre `/auth` pour réparer son
  // profil. Il est enfermé. C'est exactement ce que cette branche existe pour éviter.
  it.each(['constructor', 'toString', 'valueOf', 'hasOwnProperty', '__proto__', 'isPrototypeOf'])(
    'ne donne AUCUN espace au rôle « %s »',
    (role) => {
      expect(spaceForRole(role)).toBeNull()
    },
  )

  it('refuse ces rôles sur les trois espaces', () => {
    for (const role of ['constructor', '__proto__']) {
      for (const space of Object.values(SPACES)) {
        expect(resolveSpaceAccess(role, space), `${role} / ${space}`).toEqual({ allowed: false })
      }
    }
  })
})

describe('resolveSpaceAccess — la réponse est UNIFORME (NFR-P9)', () => {
  it('laisse passer un utilisateur dans son propre espace', () => {
    expect(resolveSpaceAccess('BUYER', SPACES.CLIENT)).toEqual({ allowed: true })
  })

  it('refuse l’espace d’un autre rôle', () => {
    expect(resolveSpaceAccess('BUYER', SPACES.ADMIN)).toEqual({ allowed: false })
  })

  it('rend EXACTEMENT la même chose pour un espace inexistant que pour un espace interdit', () => {
    // L'assertion qui porte toute l'exigence : les deux réponses sont identiques au sens
    // de l'égalité profonde. Si un jour l'une porte un motif et l'autre non, l'oracle
    // est rouvert et ce test le dit.
    const interdit = resolveSpaceAccess('BUYER', SPACES.ADMIN)
    const inexistant = resolveSpaceAccess('BUYER', 'espace-qui-n-existe-pas')
    expect(interdit).toEqual(inexistant)
  })

  it('ne divulgue aucun motif, aucun espace, aucun rôle dans sa réponse', () => {
    const refus = resolveSpaceAccess('BUYER', SPACES.ADMIN)
    expect(Object.keys(refus)).toEqual(['allowed'])
    expect(JSON.stringify(refus)).not.toMatch(/admin|buyer|role|space/i)
  })

  it('refuse tout à un rôle inconnu, sans traitement particulier', () => {
    for (const space of Object.values(SPACES)) {
      expect(resolveSpaceAccess('ROOT', space)).toEqual({ allowed: false })
    }
  })
})

describe('HOME_BY_SPACE — chaque espace a une porte d’entrée', () => {
  it('déclare un accueil pour chacun des trois espaces', () => {
    for (const space of Object.values(SPACES)) {
      expect(HOME_BY_SPACE[space], `espace ${space} sans accueil`).toBeTruthy()
    }
  })
})
