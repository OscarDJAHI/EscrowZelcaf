/// <reference types="vite/client" />

/**
 * Variables d'environnement de build, DÉCLARÉES et non devinées.
 *
 * <p>`VITE_API_BASE` porte une distinction que `string` seul effacerait : la chaîne
 * VIDE signifie « même origine » (chemins relatifs `/api/v1/...` proxifiés par
 * l'ingress, Story 1.4), tandis que l'absence de la variable fait retomber
 * `api/client.ts` sur le backend local. Les deux cas sont différents et le code les
 * distingue par `=== undefined` — d'où le `?`, qui rend cette distinction visible au
 * compilateur au lieu de la laisser à un commentaire.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
