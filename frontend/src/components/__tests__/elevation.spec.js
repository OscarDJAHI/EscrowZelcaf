import { describe, expect, it } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * Règle d'élévation de `DESIGN.md`, reportée de la Story 2.1 vers celle-ci.
 *
 * <p>« Cartes bordées SANS ombre au repos ; ombre légère réservée aux surfaces
 * FLOTTANTES (menus, toasts, modales). » La Story 2.1 avait livré le token
 * `--shadow-floating` en encodant la règle, mais huit surfaces existantes portaient
 * encore `shadow-sm`/`shadow-lg` — l'auditeur d'acceptation l'a relevé, et la décision a
 * été de traiter la dette ici, avec les composants Carte.
 *
 * <p>Cette garde interdit le retour de la dette. Elle lit les SOURCES et non un rendu :
 * une ombre réintroduite dans un composant que personne ne monte en test passerait
 * autrement inaperçue — exactement ce qui est arrivé à `StateBadge` en 2.1.
 */

const SRC = resolve(process.cwd(), 'src')

/** La seule ombre autorisée du dépôt, et elle porte son usage dans son nom. */
const ALLOWED = 'shadow-floating'

describe('Élévation — une seule ombre existe, et elle est réservée', () => {
  it('aucune source n’emploie une ombre autre que shadow-floating', () => {
    const offenders = []
    for (const rel of globSync('**/*.{vue,js,css}', { cwd: SRC })) {
      if (rel.includes('__tests__')) continue
      const body = readFileSync(resolve(SRC, rel), 'utf8')
      // `shadow` suivi d'une taille Tailwind, ou `shadow` nu employé comme classe.
      const found = [...body.matchAll(/\bshadow(-(sm|md|lg|xl|2xl|inner))?\b(?!-floating)/g)]
        .map((m) => m[0])
        .filter((token) => token !== ALLOWED)
      if (found.length) offenders.push(`${rel} → ${[...new Set(found)].join(', ')}`)
    }
    expect(offenders).toEqual([])
  })

  it('le token d’ombre existe et reste unique dans la feuille de tokens', () => {
    // Si quelqu'un ajoute `--shadow-card`, la règle « une seule ombre » est morte et
    // cette assertion le dit avant que douze epics ne s'en servent.
    const css = readFileSync(resolve(SRC, 'style.css'), 'utf8')
    const shadowTokens = [...css.matchAll(/--shadow-[\w-]+/g)].map((m) => m[0])
    expect([...new Set(shadowTokens)]).toEqual(['--shadow-floating'])
  })
})
