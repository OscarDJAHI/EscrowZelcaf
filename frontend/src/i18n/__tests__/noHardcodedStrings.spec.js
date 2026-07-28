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
  // BORNÉ à la fermante. La première correction n'avait fait que DÉPLACER le défaut :
  // découper de la balise ouvrante jusqu'à la fin du fichier fait entrer un bloc
  // `<style>` — un motif de SFC parfaitement ordinaire — dans le texte « rendu », et la
  // garde se met alors à échouer sur du CSS (constat de la 2e passe de revue).
  const body = source.slice(opening.index + opening[0].length)
  const closing = body.lastIndexOf('</template>')
  if (closing < 0) {
    throw new Error(`${rel} : balise </template> fermante absente — la garde ne peut pas borner ce fichier`)
  }
  const template = body.slice(0, closing)
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
  // Schéma d'autorisation HTTP. Nommé une fois par forme d'interpolation plutôt que
  // dilué dans le motif : une exception qu'on doit écrire à la main se voit en revue.
  'Bearer ${token}',
  'Bearer ${currentToken}',
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
/**
 * Un littéral ressemble-t-il à du texte destiné à l'utilisateur ?
 *
 * <p>La première heuristique ne retenait qu'un critère : majuscule suivie d'une minuscule.
 * Un auditeur a construit le contre-exemple et l'a EXÉCUTÉ — `'upload complete'` et
 * `'PLEASE WAIT'`, interpolés dans le gabarit par une liaison, passaient les DEUX gardes à
 * la fois : le scan de gabarit ignore les interpolations (à raison, elles portent
 * normalement du `$t()`), et le scan de code ne voyait ni la minuscule initiale, ni les
 * capitales.
 *
 * <p>Second critère : au moins deux mots purement alphabétiques. Il attrape ces deux
 * formes sans mordre sur les valeurs techniques, qui portent presque toujours un tiret, un
 * point ou un slash (`bg-warning-surface text-warning`, `application/json`) et ne comptent
 * donc pas deux mots alphabétiques.
 */
function looksLikeProse(value) {
  // Diagnostic de console : `[session] …`, `[offlineQueue] …`. Exclu par RÈGLE et non par
  // énumération — ces messages ne sont jamais rendus, et une liste nominative aurait
  // grossi à chaque nouveau log.
  if (/^\[[a-zA-Z][\w-]*\]/.test(value)) return false
  if (/^\p{Lu}\p{Ll}/u.test(value)) return true
  const words = value.split(/\s+/).filter((w) => /^\p{L}{2,}$/u.test(w))
  return words.length >= 2
}

function scriptSource(body, rel) {
  const raw = rel.endsWith('.vue')
    ? [...body.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)].map((m) => m[1]).join('\n')
    : body
  // Le retrait des commentaires de ligne se fait en SUIVANT l'état de citation :
  // un `replace` naïf sur `//` amputait toute ligne portant une URL protocol-relative
  // ou n'importe quelle chaîne contenant `//`, et pouvait ainsi effacer un littéral en
  // prose voisin — donc le cacher à la garde (constat de la 2e passe de revue).
  return stripLineComments(raw.replace(/\/\*[\s\S]*?\*\//g, ''))
}

/** Retire les `//` de fin de ligne, en ignorant ceux situés à l'intérieur d'une chaîne. */
function stripLineComments(code) {
  return code
    .split('\n')
    .map((line) => {
      let quote = null
      for (let i = 0; i < line.length; i += 1) {
        const c = line[i]
        if (quote) {
          if (c === '\\') i += 1
          else if (c === quote) quote = null
        } else if (c === "'" || c === '"' || c === '`') {
          quote = c
        } else if (c === '/' && line[i + 1] === '/') {
          return line.slice(0, i)
        }
      }
      return line
    })
    .join('\n')
}

describe('Aucune chaîne en prose dans le code, hors catalogues', () => {
  it('aucun littéral destiné à l’utilisateur ne vit hors des catalogues i18n', () => {
    const offenders = []
    const files = globSync('**/*.{vue,js}', { cwd: SRC }).filter(
      (rel) => !rel.includes('__tests__') && !rel.startsWith('i18n/'),
    ).filter((rel) => !PENDING_MIGRATION.has(rel))
    for (const rel of files) {
      const code = scriptSource(readFileSync(resolve(SRC, rel), 'utf8'), rel)
      // Les backticks comptent, INTERPOLATIONS COMPRISES. Une première correction avait
      // exclu `$` du motif, si bien qu'un littéral en prose du genre
      // `The entry could not be deleted: ${err.message}` — motif on ne peut plus banal
      // pour composer un message d'erreur — restait invisible. Un littéral commençant
      // par `${` ne ressemble pas à de la prose et n'est donc pas signalé.
      // Les séquences échappées font partie du littéral : sans elles, un apostrophe
      // dans `user\'s` ouvrait une fausse chaîne et la garde signalait « s queued
      // entries », un fragment qui n'existe nulle part.
      const literals = [...code.matchAll(/'((?:[^'\\\n]|\\.){2,})'|"((?:[^"\\\n]|\\.){2,})"|`((?:[^`\\\n]|\\.){2,})`/g)]
        .map((m) => m[1] ?? m[2] ?? m[3])
        .filter(looksLikeProse)
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
