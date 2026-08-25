import { readFileSync, existsSync } from 'node:fs'

/**
 * Vérifie le manifeste PWA et le service worker ÉMIS, dans `dist/`.
 *
 * <p><b>Ce que ce script n'est pas.</b> L'AC de la story demande un audit Lighthouse
 * « installable ». Ceci est MOINS que cela : Lighthouse lance un vrai Chrome et juge une
 * page servie, là où ce script inspecte des fichiers. Il ne dit rien du HTTPS, rien de la
 * portée effective du service worker à l'exécution, rien des performances. Ce qu'il fait,
 * il le fait sans navigateur ni dépendance, donc en CI sans installer 300 Mo de Chromium —
 * et il attrape la classe de pannes qui rend réellement une PWA non installable :
 * un champ manquant, une icône déclarée mais absente, ou déclarée 512×512 alors qu'elle
 * fait 192 px. L'écart est assumé et consigné dans la story ; l'audit Lighthouse complet
 * reste à faire, en local ou dans un job dédié.
 *
 * <p><b>Pourquoi vérifier les DIMENSIONS RÉELLES.</b> Un manifeste est déclaratif : il
 * affirme des tailles que personne ne recoupe. Un fichier présent mais de mauvaise taille
 * passe toutes les vérifications d'existence et fait échouer l'installation sur Android.
 * On lit donc l'en-tête PNG.
 */

const FAILURES = []
const fail = (message) => FAILURES.push(message)

// --- Manifeste ---------------------------------------------------------------
// `vite-plugin-pwa` émet `manifest.webmanifest`. Si le nom change un jour, ce contrôle
// échoue bruyamment plutôt que de vérifier silencieusement un fichier absent.
const MANIFEST = 'dist/manifest.webmanifest'
if (!existsSync(MANIFEST)) {
  console.error(`ÉCHEC : ${MANIFEST} introuvable — lancer « npm run build » avant.`)
  process.exit(1)
}

let manifest
try {
  manifest = JSON.parse(readFileSync(MANIFEST, 'utf8'))
} catch (err) {
  console.error(`ÉCHEC : ${MANIFEST} n'est pas un JSON valide (${err.message}).`)
  process.exit(1)
}

// Champs sans lesquels un navigateur refuse l'installation, ou installe une application
// sans nom ni couleur. `scope` et `description` n'y sont pas : leur absence dégrade sans
// empêcher — cette liste n'est PAS « tout ce qui serait bien d'avoir ».
for (const field of ['name', 'short_name', 'start_url', 'display', 'theme_color', 'background_color', 'icons']) {
  if (manifest[field] === undefined || manifest[field] === '') fail(`champ manquant : ${field}`)
}

if (manifest.display && !['standalone', 'fullscreen', 'minimal-ui'].includes(manifest.display)) {
  // `browser` est une valeur légale du standard, mais elle signifie « ouvrir dans un
  // onglet » : l'application cesse alors d'être installable au sens où la story l'entend.
  fail(`display « ${manifest.display} » : l'application s'ouvrirait dans un onglet, pas comme application installée`)
}

if (manifest.start_url && !manifest.start_url.startsWith('/')) {
  fail(`start_url « ${manifest.start_url} » doit être absolu`)
}

// --- Cohérence avec les tokens de DESIGN.md ----------------------------------
// Le manifeste ne peut pas lire une variable CSS (l'OS le lit avant tout chargement de
// style), donc les couleurs y sont RECOPIÉES. Une copie non surveillée dérive : ce champ
// a porté un teal retiré du nuancier par la Story 2.1 sans que rien ne le signale.
const STYLE = 'src/style.css'
const readToken = (name) => {
  const match = readFileSync(STYLE, 'utf8').match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{3,8})`))
  return match?.[1]?.toLowerCase() ?? null
}

for (const [field, token] of [
  ['theme_color', '--color-brand-navy'],
  ['background_color', '--color-surface-page'],
]) {
  const expected = readToken(token)
  if (!expected) {
    fail(`token ${token} introuvable dans src/style.css — impossible de vérifier ${field}`)
  } else if (manifest[field]?.toLowerCase() !== expected) {
    fail(`${field} = ${manifest[field]} mais ${token} = ${expected} : la copie a dérivé du nuancier`)
  }
}

// --- Icônes : présentes ET aux bonnes dimensions -----------------------------
/**
 * Dimensions d'un PNG, lues dans le bloc IHDR.
 *
 * <p>Un PNG commence par une signature de 8 octets, puis IHDR : longueur (4), type (4),
 * puis largeur et hauteur sur 4 octets chacune, en gros-boutien. D'où les offsets 16 et
 * 20. On vérifie la signature avant de lire, sinon un SVG renommé `.png` donnerait des
 * dimensions absurdes au lieu d'un message clair.
 */
function pngSize(path) {
  const buf = readFileSync(path)
  const SIGNATURE = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a])
  if (buf.length < 24 || !buf.subarray(0, 8).equals(SIGNATURE)) return null
  return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) }
}

const icons = Array.isArray(manifest.icons) ? manifest.icons : []
if (!icons.length) fail('aucune icône déclarée')

for (const icon of icons) {
  const path = `dist/${String(icon.src).replace(/^\//, '')}`
  if (!existsSync(path)) {
    fail(`icône déclarée mais ABSENTE du build : ${icon.src}`)
    continue
  }
  if (!icon.src.endsWith('.png')) continue // Un SVG n'a pas de dimensions intrinsèques à recouper.

  const size = pngSize(path)
  if (!size) {
    fail(`${icon.src} n'est pas un PNG valide malgré son extension`)
    continue
  }
  const [w, h] = String(icon.sizes ?? '').split('x').map(Number)
  if (!w || !h) {
    fail(`${icon.src} : attribut sizes illisible (« ${icon.sizes} »)`)
  } else if (size.width !== w || size.height !== h) {
    fail(`${icon.src} déclare ${icon.sizes} mais mesure ${size.width}x${size.height}`)
  }
}

// Android exige une icône ≥ 192 px pour proposer l'installation, et une icône `maskable`
// pour ne pas rogner le logo dans le gabarit du lanceur.
if (!icons.some((i) => Number(String(i.sizes ?? '').split('x')[0]) >= 192)) {
  fail('aucune icône de 192 px ou plus : Android ne proposera pas l’installation')
}
if (!icons.some((i) => String(i.purpose ?? '').split(/\s+/).includes('maskable'))) {
  fail('aucune icône « maskable » : le logo sera rogné dans le lanceur Android')
}

// --- Service worker et pré-cache du shell ------------------------------------
// Sans service worker, pas d'installation ; et sans `index.html` dans son pré-cache, le
// `navigateFallback` n'a rien à servir hors ligne — la coquille est là, vide.
const SW = 'dist/sw.js'
if (!existsSync(SW)) {
  fail('dist/sw.js absent : aucun service worker émis')
} else {
  const sw = readFileSync(SW, 'utf8')
  if (!sw.includes('index.html')) {
    fail('index.html n’apparaît pas dans le pré-cache du service worker : l’application ne démarrerait pas hors ligne')
  }
}

// --- Verdict -----------------------------------------------------------------
if (FAILURES.length) {
  console.error('ÉCHEC : vérification du manifeste PWA.')
  for (const f of FAILURES) console.error(`  - ${f}`)
  process.exit(1)
}
console.log(
  `Manifeste PWA conforme : ${icons.length} icônes vérifiées (présence + dimensions réelles), ` +
    `couleurs alignées sur les tokens, service worker émis avec le shell pré-caché.\n` +
    `Rappel : ceci ne remplace PAS un audit Lighthouse (HTTPS, portée à l’exécution, performances non couverts).`,
)
