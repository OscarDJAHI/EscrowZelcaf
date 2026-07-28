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

let files
try {
  files = walk('dist')
} catch (err) {
  // Sortie non nulle dans tous les cas — c'est le bon sens de l'échec —, mais avec un
  // message qui dit quoi faire plutôt qu'une trace ENOENT brute.
  console.error(`ÉCHEC : impossible de lire dist/ — lancer « npm run build » avant. (${err.code ?? err.message})`)
  process.exit(1)
}
const byName = files.filter((p) => p.includes(COMPONENT))
const byMarker = files.filter((p) => {
  try {
    return readFileSync(p, 'latin1').includes(MARKER)
  } catch {
    // Un fichier illisible ne prouve PAS l'absence : on le signale comme suspect plutôt
    // que de l'ignorer, la garde devant pencher du côté prudent.
    console.error(`Fichier illisible pendant la vérification : ${p}`)
    return true
  }
})
const hits = [...new Set([...byName, ...byMarker])]

if (hits.length) {
  console.error(`ÉCHEC : la galerie de composants est présente dans le build de production.`)
  for (const hit of hits) console.error(`  - ${hit}`)
  process.exit(1)
}
console.log(`Galerie absente du build de production — ${files.length} fichiers vérifiés (nom + marqueur).`)
