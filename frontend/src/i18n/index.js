import { createI18n } from 'vue-i18n'
import en from './en.json'
import fr from './fr.json'

/**
 * Infrastructure i18n par clés (NFR-P24, AD-23).
 *
 * <p>AD-23 pose le contrat : le frontend possède les clés EN/FR de TOUTE l'interface,
 * et les réponses API ne portent jamais de texte destiné à l'utilisateur — seulement
 * des codes machine. Un libellé se dérive donc du champ `code` de l'enveloppe d'erreur,
 * jamais de son champ `message`.
 */

export const SUPPORTED_LOCALES = ['en', 'fr']
export const DEFAULT_LOCALE = 'en'

/**
 * Clé de persistance de la langue.
 *
 * <p><b>Ce n'est PAS une donnée de session, et c'est une décision, pas un oubli.</b>
 * `stores/session.js#endSession()` retire des clés NOMMÉES (`escrow_last_user`,
 * `escrow_token`, `escrow_user`) et ne fait jamais de `localStorage.clear()` : cette
 * clé-ci survit donc à une déconnexion, ce qui est le comportement voulu. Un poste
 * francophone qui repasserait en anglais à chaque déconnexion serait hostile, et la
 * langue choisie ne révèle rien de l'identité du partant — c'est une préférence
 * d'APPAREIL.
 *
 * <p>⚠️ Story 2.7 (politique de session sur appareil partagé) réécrira `session.js` :
 * ne pas ajouter cette clé à la purge sans rouvrir cette décision.
 */
export const LOCALE_STORAGE_KEY = 'escrow_locale'

/** Langue persistée si elle est encore supportée, sinon la langue par défaut. */
export function readStoredLocale(storage = globalThis.localStorage) {
  try {
    const stored = storage?.getItem(LOCALE_STORAGE_KEY)
    return SUPPORTED_LOCALES.includes(stored) ? stored : DEFAULT_LOCALE
  } catch {
    // Stockage indisponible (mode privé verrouillé, quota) : la langue par défaut
    // reste utilisable. Une préférence d'affichage ne doit jamais empêcher de démarrer.
    return DEFAULT_LOCALE
  }
}

export function persistLocale(locale, storage = globalThis.localStorage) {
  try {
    storage?.setItem(LOCALE_STORAGE_KEY, locale)
  } catch {
    // Idem : l'échec de persistance ne casse pas la bascule en cours de session.
  }
}

/**
 * Clés manquantes : signalées, jamais silencieuses (AC4).
 *
 * <p>Le comportement par défaut de vue-i18n est d'afficher la clé brute — l'utilisateur
 * voit `dashboard.title` et rien ne rougit. Le contrat AD-23 dit l'inverse : « clé
 * manquante = échec CI (Story 2.1) ». On collecte donc les manquants, et
 * `missingKeysReport()` permet au test de convention de les faire échouer.
 */
const missingKeys = new Set()

export function missingKeysReport() {
  return [...missingKeys]
}

export function resetMissingKeys() {
  missingKeys.clear()
}

export function createEscrowI18n(locale = readStoredLocale()) {
  return createI18n({
    // Composition API : `legacy: true` exposerait `$t` via un mixin global et un
    // `this` qui n'existe pas dans `<script setup>`.
    legacy: false,
    globalInjection: true,
    locale,
    fallbackLocale: DEFAULT_LOCALE,
    messages: { en, fr },
    // Le repli vers l'anglais est un filet d'affichage, pas une excuse : on veut le
    // filet ET le signalement. Sans ces deux lignes, une clé FR absente s'afficherait
    // en anglais sans que personne ne l'apprenne jamais.
    silentFallbackWarn: false,
    silentTranslationWarn: false,
    missing(missingLocale, key) {
      missingKeys.add(`${missingLocale}:${key}`)
    },
  })
}

export const i18n = createEscrowI18n()

/** Langue supportée, ou langue par défaut. */
export function normalizeLocale(locale) {
  return SUPPORTED_LOCALES.includes(locale) ? locale : DEFAULT_LOCALE
}

/**
 * Applique une langue à la référence réactive `locale` FOURNIE par l'appelant, la
 * persiste, et met à jour l'attribut `lang` du document.
 *
 * <p>La référence est un paramètre et non le singleton du module : une version câblée
 * en dur sur `i18n.global.locale` rendait le sélecteur intestable — un composant monté
 * avec sa propre instance voyait ses clics partir sur l'instance globale, sans effet
 * visible et sans erreur. Le test l'a révélé, pas la relecture.
 */
export function applyLocale(localeRef, locale) {
  const next = normalizeLocale(locale)
  localeRef.value = next
  persistLocale(next)
  globalThis.document?.documentElement?.setAttribute('lang', next)
  return next
}
