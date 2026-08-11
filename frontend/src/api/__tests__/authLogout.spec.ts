import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import apiClient from '@/api/client'
import { logoutUser } from '@/api/auth'

/**
 * CE QUE `logoutUser` ENVOIE — et surtout ce qu'elle n'envoie pas (Story 2.7, AC4 / T5).
 *
 * <p><b>Pourquoi ce fichier existe.</b> La Story 2.7 borne l'ATTENTE de la révocation
 * (`stores/session.ts`, `REVOCATION_WAIT_MS`). Il existait une façon apparemment naturelle
 * de borner cette attente — poser un `AbortSignal` sur le `fetch` — et c'est exactement
 * celle qu'il ne faut PAS prendre : elle ANNULE la révocation au lieu de cesser de
 * l'attendre, et le jeton reste alors accepté côté serveur pendant les 24 h de son TTL
 * (`application.yml:77`, hors périmètre de cette story). C'est aussi ce que la NEVER de la
 * spec 1.9 protège, héritée de la revue 1.6 : `fetch` + `keepalive`, jamais axios, jamais
 * d'abandon.
 *
 * <p>Aucun test ne surveillait cette fonction avant aujourd'hui. Les deux garanties
 * qu'elle porte — `keepalive: true`, et RIEN qui puisse interrompre la requête — étaient
 * donc des commentaires, pas des propriétés.
 *
 * <p>`fetch` est doublé au niveau de la globale et non `logoutUser` elle-même : c'est la
 * charge REMISE AU NAVIGATEUR qu'on veut lire, et un double posé plus haut ne dirait rien
 * de ce qui part.
 */

const TOKEN = 'jeton-d-alice'

/** Le double de `fetch`, et la charge exacte qu'il a reçue. */
function stubFetch() {
  const call = vi.fn(async () => new Response(null, { status: 204 }))
  vi.stubGlobal('fetch', call)
  return call
}

beforeEach(() => {
  vi.clearAllMocks()
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('logoutUser — la révocation est remise au navigateur, pas retenue par l’onglet', () => {
  it('porte `keepalive` et AUCUN moyen d’interrompre la requête', async () => {
    const call = stubFetch()

    await logoutUser(TOKEN)

    expect(call).toHaveBeenCalledTimes(1)
    const [url, init] = call.mock.calls[0] as unknown as [string, RequestInit]
    expect(url).toBe(`${apiClient.defaults.baseURL ?? ''}/api/v1/auth/logout`)
    expect(init.method).toBe('POST')

    // LA garantie : le navigateur mène la requête à terme même après le déchargement du
    // document. C'est elle qui rend acceptable d'abandonner l'attente côté `endSession`.
    expect(init.keepalive).toBe(true)

    // Et son revers, tout aussi load-bearing : rien ici ne peut annuler la requête. Un
    // `signal` posé pour « borner » l'attente couperait la révocation en vol et laisserait
    // le jeton vivant côté serveur — le contraire exact de ce que la Story 1.6 a obtenu.
    expect(init.signal).toBeUndefined()
    expect(Object.hasOwn(init, 'signal')).toBe(false)
  })

  it('présente le jeton qu’on lui DONNE, jamais celui qui traîne dans le stockage', async () => {
    // Appariée à l'assertion précédente, et pas décorative : elle prouve que la requête
    // partie est bien une révocation utile. Le jeton est passé explicitement parce que
    // l'état local est déjà vidé quand on arrive ici (`endSession` purge tout avant le
    // réseau) — une lecture du stockage ne trouverait plus rien et révoquerait `null`.
    const call = stubFetch()

    await logoutUser(TOKEN)

    const [, init] = call.mock.calls[0] as unknown as [string, RequestInit]
    expect(init.headers).toEqual({ Authorization: `Bearer ${TOKEN}` })
  })

  it('se résout sur un refus du serveur — un 401 n’est pas une raison de rester connecté', async () => {
    // Le jeton peut être déjà mort côté serveur : c'est un succès fonctionnel, pas un
    // échec. La fonction contourne délibérément l'intercepteur d'axios pour cette raison.
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(null, { status: 401 })),
    )

    await expect(logoutUser(TOKEN)).resolves.toBeUndefined()
  })
})
