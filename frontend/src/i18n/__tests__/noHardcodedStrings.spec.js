import { describe, expect, it } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createEscrowI18n, missingKeysReport, resetMissingKeys } from '@/i18n'

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
function bareTextNodes(source) {
  const template = source.slice(source.indexOf('<template>'))
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
      // Un mot d'au moins trois lettres : en deçà, c'est de la ponctuation ou un symbole.
      .filter((chunk) => /\p{L}{3}/u.test(chunk))
  )
}

describe('Aucune chaîne en dur dans les gabarits', () => {
  it('aucun composant ni vue ne rend de texte littéral', () => {
    const offenders = []
    for (const rel of globSync('{components,views}/**/*.vue', { cwd: SRC })) {
      if (rel.includes('__tests__')) continue
      const bare = bareTextNodes(readFileSync(resolve(SRC, rel), 'utf8'))
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
