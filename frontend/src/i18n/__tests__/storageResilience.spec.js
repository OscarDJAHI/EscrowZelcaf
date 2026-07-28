import { afterEach, describe, expect, it } from 'vitest'
import { createEscrowI18n, persistLocale, readStoredLocale } from '@/i18n'

/**
 * Constat de revue (HAUT) : une préférence d'affichage ne doit JAMAIS empêcher
 * l'application de démarrer.
 *
 * <p>Le code le promettait en commentaire et ne le tenait pas : `readStoredLocale`
 * recevait le stockage par un PARAMÈTRE PAR DÉFAUT (`storage = globalThis.localStorage`),
 * or JavaScript évalue les paramètres par défaut AVANT le corps de la fonction — donc
 * avant son `try`. Dans un environnement où le simple accès à la propriété lève
 * (Safari « bloquer tous les cookies », iframe bac à sable, certaines politiques
 * d'entreprise), l'exception échappait, remontait jusqu'au `const i18n = createEscrowI18n()`
 * de niveau module, et l'application ne démarrait pas du tout : page blanche.
 */

/** Remplace `localStorage` par une propriété dont la LECTURE lève, puis restaure. */
function withHostileStorage(run) {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    get() {
      throw new DOMException('The operation is insecure.', 'SecurityError')
    },
  })
  try {
    return run()
  } finally {
    if (original) Object.defineProperty(globalThis, 'localStorage', original)
    else delete globalThis.localStorage
  }
}

afterEach(() => {
  // Ceinture et bretelles : si un test échoue au milieu, le descripteur est déjà
  // restauré par le `finally` ci-dessus ; on ne laisse rien de global derrière soi.
})

describe("Stockage inaccessible — l'application démarre quand même", () => {
  it('readStoredLocale retombe sur la langue par défaut au lieu de lever', () => {
    expect(withHostileStorage(() => readStoredLocale())).toBe('en')
  })

  it('persistLocale avale l’échec au lieu de le propager à l’appelant', () => {
    expect(() => withHostileStorage(() => persistLocale('fr'))).not.toThrow()
  })

  it("createEscrowI18n construit une instance utilisable — c'est le chemin d'amorçage", () => {
    // C'est LE scénario qui produisait la page blanche : `main.js` importe le module,
    // dont l'initialisation appelle `createEscrowI18n()` sans argument.
    const i18n = withHostileStorage(() => createEscrowI18n())
    expect(i18n.global.locale.value).toBe('en')
    expect(i18n.global.t('auth.password')).toBe('Password')
  })
})

describe('Langue explicite non supportée', () => {
  it('createEscrowI18n normalise son argument au lieu de le prendre au mot', () => {
    // Sans normalisation, le sélecteur n'affichait AUCUN bouton actif (ni EN ni FR)
    // pendant que `$t` retombait silencieusement sur l'anglais.
    expect(createEscrowI18n('de').global.locale.value).toBe('en')
  })

  it('une langue supportée est évidemment conservée', () => {
    expect(createEscrowI18n('fr').global.locale.value).toBe('fr')
  })
})
