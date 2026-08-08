import { describe, expect, it } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import en from '@/i18n/en.json'
import fr from '@/i18n/fr.json'
import { DEFAULT_LOCALE, SUPPORTED_LOCALES } from '@/i18n'

/**
 * AD-23 : « le frontend possède les clés EN/FR de toute l'UI — clé manquante = échec CI
 * (Story 2.1) ». Ces tests SONT cet échec.
 *
 * <p>Sans eux, une clé absente d'un catalogue tomberait sur le repli anglais et
 * l'utilisateur francophone verrait de l'anglais sans que rien ne rougisse — le mode de
 * défaillance silencieux exact que le contrat interdit.
 */

/** Un catalogue de traduction : arbre de chaînes, de profondeur libre. */
type Catalogue = { [key: string]: string | Catalogue }

/** Chemins feuille d'un objet imbriqué : `{a:{b:1}}` → `['a.b']`. */
function flatten(node: Catalogue, prefix = ''): string[] {
  return Object.entries(node).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return value !== null && typeof value === 'object' ? flatten(value, path) : [path]
  })
}

/**
 * Lecture d'une clé pointée dans un catalogue.
 *
 * <p>Remplace trois `reduce((node, part) => node[part], …)` recopiés : c'est la même
 * traversée à chaque fois, et elle n'était typable nulle part tant qu'elle vivait en
 * triple exemplaire.
 */
function readPath(catalogue: Catalogue, key: string): unknown {
  return key.split('.').reduce<unknown>(
    (node, part) => (node && typeof node === 'object' ? (node as Catalogue)[part] : undefined),
    catalogue,
  )
}

const enKeys = flatten(en as unknown as Catalogue)
const frKeys = flatten(fr as unknown as Catalogue)

describe('Parité des catalogues EN / FR', () => {
  it('les deux catalogues portent EXACTEMENT le même ensemble de clés', () => {
    const missingInFr = enKeys.filter((k: string) => !frKeys.includes(k))
    const missingInEn = frKeys.filter((k: string) => !enKeys.includes(k))

    expect({ absentesDuFrancais: missingInFr, absentesDeLAnglais: missingInEn }).toEqual({
      absentesDuFrancais: [],
      absentesDeLAnglais: [],
    })
  })

  it('aucune valeur vide, dans aucune des deux langues', () => {
    // Une clé présente mais vide passerait le test de parité tout en n'affichant rien.
    const empty = []
    for (const [locale, catalogue] of [
      ['en', en as unknown as Catalogue],
      ['fr', fr as unknown as Catalogue],
    ] as const) {
      for (const key of flatten(catalogue)) {
        const value = readPath(catalogue, key)
        if (typeof value !== 'string' || value.trim() === '') empty.push(`${locale}:${key}`)
      }
    }
    expect(empty).toEqual([])
  })

  it('les paramètres nommés sont les mêmes des deux côtés', () => {
    // `{email}` traduit en `{courriel}` produirait un littéral affiché tel quel.
    const params = (s: unknown) =>
      typeof s === 'string' ? [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort() : []
    const mismatched: string[] = []
    for (const key of enKeys) {
      const read = (c: Catalogue) => readPath(c, key)
      const a = params(read(en as unknown as Catalogue))
      const b = params(read(fr as unknown as Catalogue))
      if (JSON.stringify(a) !== JSON.stringify(b)) mismatched.push(`${key} : EN=${a} / FR=${b}`)
    }
    expect(mismatched).toEqual([])
  })

  it("l'anglais est la langue par défaut et les deux langues sont déclarées supportées", () => {
    expect(DEFAULT_LOCALE).toBe('en')
    expect(SUPPORTED_LOCALES).toEqual(['en', 'fr'])
  })
})

describe('Aucune clé utilisée sans exister au catalogue', () => {
  const SRC = resolve(process.cwd(), 'src')

  it('toute clé référencée par un `t(...)` existe dans les deux catalogues', () => {
    // La parité garantit que les deux catalogues coïncident ; elle ne dit rien d'une clé
    // que le code appelle et qu'AUCUN catalogue ne porte. C'est ce trou-ci que ce test ferme.
    const files = globSync('**/*.{vue,js}', { cwd: SRC }).filter((rel) => !rel.includes('__tests__'))
    const used = new Set<string>()
    for (const rel of files) {
      const body = readFileSync(resolve(SRC, rel), 'utf8')
      for (const match of body.matchAll(/\bt\(\s*'([a-zA-Z][\w.]*)'/g)) {
        if (match[1]) used.add(match[1])
      }
      for (const match of body.matchAll(/\$t\(\s*'([a-zA-Z][\w.]*)'/g)) {
        if (match[1]) used.add(match[1])
      }
    }
    const unknown = [...used].filter((key) => !enKeys.includes(key))
    expect(unknown).toEqual([])
  })
})
