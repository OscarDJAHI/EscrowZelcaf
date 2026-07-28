import { describe, expect, it } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { SUPPORTED_LOCALES, createEscrowI18n, missingKeysReport, resetMissingKeys } from '@/i18n'
import en from '@/i18n/en.json'
import fr from '@/i18n/fr.json'

/**
 * AC4, second volet : « aucune nouvelle chaîne littérale n'est admise dans les
 * composants migrés ». La parité des catalogues garantit qu'une clé existe dans les
 * deux langues ; elle ne dit rien d'un texte écrit EN DUR dans un gabarit, qui ne
 * passe par aucun catalogue et n'apparaît donc dans aucun rapport de clé manquante.
 * C'est ce trou-ci que ce fichier ferme.
 */

const SRC = resolve(process.cwd(), 'src')

/**
 * Jetons visibles tolérés dans un gabarit : ils ne se traduisent pas.
 * Toute addition ici doit se justifier — c'est la porte de sortie de la garde.
 */
const NOT_TRANSLATABLE = new Set([
  'Z', // monogramme du logo
  '✕', // croix de fermeture, le libellé accessible passe par aria-label
  'EN',
  'FR',
])

/**
 * Texte brut des nœuds du gabarit, hors interpolations `{{ }}` et hors attributs.
 *
 * <p>Les valeurs d'attributs sont neutralisées AVANT le découpage sur les balises :
 * une expression comme `v-if="files.length > 0"` contient un `>` qui, sinon, coupe la
 * balise en deux et fait passer un morceau d'attribut pour du texte rendu. La première
 * version de cette garde rapportait ainsi des faux positifs du genre `0" class="…`.
 */
function bareTextNodes(source, rel = '?') {
  // La balise ouvrante peut porter des attributs (`<template lang="pug">`). La première
  // version cherchait `indexOf('<template>')`, qui rend −1 dans ce cas ; `slice(-1)` ne
  // gardait alors qu'UN caractère et le composant entier passait pour conforme. Une garde
  // qui se neutralise en silence est pire que pas de garde : on échoue bruyamment.
  const opening = source.match(/<template(\s[^>]*)?>/)
  if (!opening) {
    throw new Error(`${rel} : aucune balise <template> trouvée — la garde ne peut pas lire ce fichier`)
  }
  const template = source.slice(opening.index + opening[0].length)
  return (
    template
      // Les commentaires HTML ne s'affichent pas.
      .replace(/<!--[\s\S]*?-->/g, '')
      // Neutralise le CONTENU des attributs, en gardant les guillemets pour que la
      // structure des balises reste intacte.
      .replace(/="[^"]*"/g, '=""')
      .replace(/='[^']*'/g, "=''")
      // Les interpolations sont, par définition, du contenu calculé.
      .replace(/\{\{[\s\S]*?\}\}/g, '')
      // Tout ce qui est ENTRE deux balises est du texte rendu.
      .split(/<[^>]*>/g)
      .map((chunk) => chunk.replace(/\s+/g, ' ').trim())
      .filter(Boolean)
      .filter((chunk) => !NOT_TRANSLATABLE.has(chunk))
      // Deux lettres suffisent : le seuil de trois laissait passer « By », un vrai
      // littéral anglais rendu à l'utilisateur (constat de revue).
      .filter((chunk) => /\p{L}{2}/u.test(chunk))
  )
}

describe('Aucune chaîne en dur dans les gabarits', () => {
  it('aucun composant ni vue ne rend de texte littéral', () => {
    const offenders = []
    for (const rel of globSync('{components,views}/**/*.vue', { cwd: SRC })) {
      if (rel.includes('__tests__')) continue
      const bare = bareTextNodes(readFileSync(resolve(SRC, rel), 'utf8'), rel)
      if (bare.length) offenders.push(`${rel} → ${JSON.stringify(bare)}`)
    }
    expect(offenders).toEqual([])
  })
})

describe('Clé manquante à l’exécution — signalée, jamais silencieuse', () => {
  it('une clé absente est enregistrée au lieu de passer inaperçue', () => {
    // Le comportement par défaut de vue-i18n est d'afficher la clé brute : l'utilisateur
    // voit `nope.jamais` et rien ne rougit. AD-23 exige l'inverse.
    resetMissingKeys()
    const i18n = createEscrowI18n('fr')

    i18n.global.t('nope.jamais')

    expect(missingKeysReport()).toContain('fr:nope.jamais')
    resetMissingKeys()
  })

  it('une clé existante ne pollue pas le rapport', () => {
    resetMissingKeys()
    const i18n = createEscrowI18n('fr')

    expect(i18n.global.t('auth.password')).toBe('Mot de passe')

    expect(missingKeysReport()).toEqual([])
  })
})

/**
 * Angle mort majeur relevé en revue : la garde ci-dessus ne lit que le TEXTE des gabarits.
 * Une chaîne anglaise vivant dans un `<script setup>`, dans `utils/*.js` ou dans une table
 * de constantes, puis rendue par interpolation, lui était totalement invisible — c'est par
 * là que sont passés `'Buyer'`, `EVENT_LABELS`, les messages de validation de fichier et
 * les libellés d'échec de synchronisation.
 *
 * <p>Heuristique assumée : un littéral « en prose » commence par une majuscule suivie d'une
 * minuscule. Cela attrape `'Buyer'`, `'Pay funds'`, `'Uploading…'` sans se déclencher sur
 * les valeurs techniques (`'BUYER'` en capitales, `'escrow_token'`, chemins, classes CSS).
 * Les exceptions sont NOMMÉES ci-dessous : c'est la seule porte de sortie, et elle se voit
 * en revue.
 */
/**
 * DETTE NOMMÉE, PAS UNE PORTE DÉROBÉE.
 *
 * <p>Ces modules portent encore des libellés anglais destinés à l'utilisateur. Ils sont
 * listés ICI, un par un, plutôt que soustraits par un motif de répertoire : une exception
 * qu'on doit écrire à la main et justifier se voit en revue ; un `skip` sur `utils/**` se
 * serait élargi tout seul.
 *
 * <p>Le correctif est le même pour tous — le module expose une CLÉ, la vue traduit — mais
 * il touche `FAILURE_LABELS`, dont la garde anti-dérive contre `ErrorCode.java` doit être
 * préservée. Reporté délibérément plutôt que bâclé (voir `deferred-work.md`, revue 2.1).
 *
 * <p>RETIRER une entrée de cette liste doit faire ROUGIR la suite tant que le module
 * n'est pas migré : c'est le test de non-régression de cette dette.
 */
const PENDING_MIGRATION = new Set([
  'utils/evidence.js',
  'utils/frozenEntry.js',
  'utils/replayFailure.js',
  'stores/auth.js',
  'stores/escrow.js',
  'stores/evidence.js',
])

const PROSE_ALLOWED = new Set([
  'Inter Variable', // nom de police, ne se traduit pas
  'Intl', // identifiant d'API
  'Content-Type', // en-tête HTTP : valeur de protocole, jamais affichée
])

/**
 * Blocs `<script>` d'un SFC (ou fichier entier pour un `.js`), COMMENTAIRES RETIRÉS.
 *
 * <p>Le retrait des commentaires n'est pas cosmétique : sans lui, la garde signalait les
 * phrases citées dans les javadocs (« Never rejects », un libellé cité pour l'expliquer,
 * un commentaire français mentionnant l'Epic 5). Une garde qui crie sur de la
 * documentation est une garde qu'on finit par désactiver — et une garde désactivée ne
 * protège rien.
 */
function scriptSource(body, rel) {
  const raw = rel.endsWith('.vue')
    ? [...body.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n')
    : body
  return raw.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1')
}

describe('Aucune chaîne en prose dans le code, hors catalogues', () => {
  it('aucun littéral destiné à l’utilisateur ne vit hors des catalogues i18n', () => {
    const offenders = []
    const files = globSync('**/*.{vue,js}', { cwd: SRC }).filter(
      (rel) => !rel.includes('__tests__') && !rel.startsWith('i18n/'),
    ).filter((rel) => !PENDING_MIGRATION.has(rel))
    for (const rel of files) {
      const code = scriptSource(readFileSync(resolve(SRC, rel), 'utf8'), rel)
      const literals = [...code.matchAll(/'([^'\\\n]{2,})'|"([^"\\\n]{2,})"/g)]
        .map((m) => m[1] ?? m[2])
        .filter((v) => /^\p{Lu}\p{Ll}/u.test(v))
        .filter((v) => !PROSE_ALLOWED.has(v))
      if (literals.length) offenders.push(`${rel} → ${JSON.stringify([...new Set(literals)])}`)
    }
    expect(offenders).toEqual([])
  })
})

describe('Clés construites dynamiquement', () => {
  it('chaque langue supportée possède son libellé de sélecteur', () => {
    // `$t(`language.${code}`)` est un littéral de gabarit : l'extracteur statique de
    // `catalogues.spec.js` ne le voit pas. Cette garde ferme le trou pour le seul motif
    // dynamique du dépôt — ajouter une langue sans son libellé rougira ici.
    for (const code of SUPPORTED_LOCALES) {
      expect(en.language, `language.${code} absente du catalogue EN`).toHaveProperty(code)
      expect(fr.language, `language.${code} absente du catalogue FR`).toHaveProperty(code)
    }
  })
})
