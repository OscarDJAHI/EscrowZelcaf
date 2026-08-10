import { beforeEach, afterEach, describe, expect, it } from 'vitest'
import {
  PERSISTENCE_PREFERENCE_KEY,
  DEFAULT_PERSISTENCE,
  readPersistence,
  writePersistence,
  readCredential,
  writeCredential,
  removeCredential,
} from '@/utils/credentialStorage'

const KEY = 'escrow_token'

/**
 * Le substrat de stockage des identifiants (Story 2.7, AC1).
 *
 * <p>Ce que cette suite garde : le jeton n'existe QUE dans le substrat actif, un
 * changement de préférence ne laisse pas de fantôme dans l'autre, et un stockage
 * hostile ne casse rien. Les trois se sont déjà produits ailleurs dans ce dépôt sous
 * une forme ou une autre.
 */
describe('credentialStorage', () => {
  beforeEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  afterEach(() => {
    localStorage.clear()
    sessionStorage.clear()
  })

  describe('préférence de persistance', () => {
    it('vaut « session » par défaut — le jeton meurt avec l\'onglet', () => {
      expect(readPersistence()).toBe('session')
      expect(DEFAULT_PERSISTENCE).toBe('session')
    })

    it('vit en localStorage, sinon « rester connecté » ne survivrait pas à la fermeture', () => {
      writePersistence('local')
      // Le point n'est pas cosmétique : une préférence rangée en sessionStorage
      // mourrait avec l'onglet, donc l'option qu'elle porte ne tiendrait jamais
      // sa promesse — elle serait vraie pendant la session et fausse après.
      expect(localStorage.getItem(PERSISTENCE_PREFERENCE_KEY)).toBe('local')
      expect(sessionStorage.getItem(PERSISTENCE_PREFERENCE_KEY)).toBeNull()
    })

    it('retombe sur « session » devant une valeur persistée inconnue', () => {
      localStorage.setItem(PERSISTENCE_PREFERENCE_KEY, 'forever')
      // Direction sûre : une valeur qu'on ne comprend pas ne doit pas accorder la
      // persistance la plus longue.
      expect(readPersistence()).toBe('session')
    })
  })

  describe('écriture dans le substrat actif', () => {
    it('en mode session, écrit dans sessionStorage et NULLE PART ailleurs', () => {
      writePersistence('session')
      writeCredential(KEY, 'jeton-a')

      expect(sessionStorage.getItem(KEY)).toBe('jeton-a')
      expect(localStorage.getItem(KEY)).toBeNull()
    })

    it('en mode local, écrit dans localStorage et NULLE PART ailleurs', () => {
      writePersistence('local')
      writeCredential(KEY, 'jeton-a')

      expect(localStorage.getItem(KEY)).toBe('jeton-a')
      expect(sessionStorage.getItem(KEY)).toBeNull()
    })

    it('efface le FANTÔME laissé dans l\'autre substrat au changement de préférence', () => {
      // A coche « rester connecté » : le jeton part en localStorage.
      writePersistence('local')
      writeCredential(KEY, 'jeton-de-A')
      expect(localStorage.getItem(KEY)).toBe('jeton-de-A')

      // B se connecte sans cocher. Sans cette purge, le jeton de A resterait en
      // localStorage — et la première bascule de préférence vers « local » le
      // rendrait de nouveau lisible. Un identifiant abandonné dans un substrat
      // inactif reste un identifiant sur l'appareil.
      writePersistence('session')
      writeCredential(KEY, 'jeton-de-B')

      expect(sessionStorage.getItem(KEY)).toBe('jeton-de-B')
      expect(localStorage.getItem(KEY)).toBeNull()
    })
  })

  describe('lecture', () => {
    it('ne lit QUE le substrat actif — une valeur dans l\'inactif est invisible', () => {
      writePersistence('session')
      localStorage.setItem(KEY, 'jeton-oublie')

      // Pas de repli sur l'autre substrat : un repli ressusciterait exactement la
      // session que l'AC1 promet de laisser mourir.
      expect(readCredential(KEY)).toBeNull()
    })

    it('relit ce qui vient d\'être écrit, dans les deux modes', () => {
      writePersistence('session')
      writeCredential(KEY, 'jeton-s')
      expect(readCredential(KEY)).toBe('jeton-s')

      writePersistence('local')
      writeCredential(KEY, 'jeton-l')
      expect(readCredential(KEY)).toBe('jeton-l')
    })
  })

  describe('suppression', () => {
    it('retire la clé des DEUX substrats, quelle que soit la préférence', () => {
      localStorage.setItem(KEY, 'residu-local')
      sessionStorage.setItem(KEY, 'residu-session')

      removeCredential(KEY)

      // La purge de session ne peut pas dépendre de la préférence courante : elle
      // s'exécute précisément quand on ne veut plus rien laisser derrière soi.
      expect(localStorage.getItem(KEY)).toBeNull()
      expect(sessionStorage.getItem(KEY)).toBeNull()
    })
  })

  describe('stockage hostile', () => {
    /**
     * Safari « bloquer tous les cookies » : c'est l'ACCÈS À LA PROPRIÉTÉ qui lève,
     * pas la méthode. Le patron vient de `i18n/index.ts`, dont le commentaire
     * explique que le mettre en paramètre par défaut évalue l'accès AVANT le `try`.
     */
    function withHostileStorage<T>(name: 'localStorage' | 'sessionStorage', run: () => T): T {
      const original = Object.getOwnPropertyDescriptor(globalThis, name)
      Object.defineProperty(globalThis, name, {
        configurable: true,
        get() {
          throw new DOMException('The operation is insecure.', 'SecurityError')
        },
      })
      try {
        return run()
      } finally {
        if (original) Object.defineProperty(globalThis, name, original)
      }
    }

    it('un sessionStorage inaccessible ne fait pas lever la lecture', () => {
      expect(() =>
        withHostileStorage('sessionStorage', () => {
          expect(readCredential(KEY)).toBeNull()
        }),
      ).not.toThrow()
    })

    it('un sessionStorage inaccessible ne fait pas lever l\'écriture ni la suppression', () => {
      expect(() =>
        withHostileStorage('sessionStorage', () => {
          writeCredential(KEY, 'jeton')
          removeCredential(KEY)
        }),
      ).not.toThrow()
    })

    it('un localStorage inaccessible laisse la préférence par défaut utilisable', () => {
      expect(() =>
        withHostileStorage('localStorage', () => {
          expect(readPersistence()).toBe('session')
          writePersistence('local')
        }),
      ).not.toThrow()
    })
  })
})
