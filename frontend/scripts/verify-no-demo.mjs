import { readdirSync, readFileSync, statSync } from 'node:fs'

/**
 * Vérifie qu'aucun artefact de production ne contient la galerie de composants.
 *
 * Deux signaux, parce qu'un seul s'est déjà révélé insuffisant :
 *  1. le NOM d'un fichier émis (Vite nomme le chunk d'après le composant) ;
 *  2. le MARQUEUR rendu (`data-gallery`), qui survit à la minification — contrairement
 *     à un commentaire, que la première version de ce contrôle cherchait en vain, ce
 *     qui le faisait passer même quand la galerie ÉTAIT dans le bundle.
 */
const MARKER = 'ESCROW_COMPONENT_GALLERY_DEV_ONLY'
const COMPONENT = 'ComponentGalleryView'

const walk = (dir) =>
  readdirSync(dir).flatMap((entry) => {
    const path = `${dir}/${entry}`
    return statSync(path).isDirectory() ? walk(path) : [path]
  })

const files = walk('dist')
const byName = files.filter((p) => p.includes(COMPONENT))
const byMarker = files.filter((p) => readFileSync(p, 'latin1').includes(MARKER))
const hits = [...new Set([...byName, ...byMarker])]

if (hits.length) {
  console.error(`ÉCHEC : la galerie de composants est présente dans le build de production.`)
  for (const hit of hits) console.error(`  - ${hit}`)
  process.exit(1)
}
console.log(`Galerie absente du build de production — ${files.length} fichiers vérifiés (nom + marqueur).`)
