import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  FAILURE_LABELS,
  TRANSIENT_CODES,
  classifyReplayFailure,
  describeFailure,
  extractFailureReason,
  isBareAuthFailure,
} from '@/utils/replayFailure'

// Anchored on the Vitest CWD (`frontend/`): under jsdom `import.meta.url` is an
// http URL, not a file one.
const BACKEND_DIR = resolve(process.cwd(), '../backend')
const ERROR_CODE_JAVA = resolve(BACKEND_DIR, 'src/main/java/com/zlecaf/escrow/domain/ErrorCode.java')

// Two guards below read the Java enum as the authority. That coupling is
// deliberate (see their comments) but it made `npm run test` require a FULL
// checkout: run from a front-only context — a `docker build frontend/`, a CI job
// that clones one module — `readFileSync` threw ENOENT and the suite reported a
// *drift* failure, which is a lie. It could not tell "no backend here" from "the
// mirror is stale".
//
// The two cases are now separated, and only one of them is benign:
//   - no `../backend` directory at all -> front-only context, the guard is not
//     applicable and skips WITH ITS REASON PRINTED;
//   - `../backend` present but the enum missing -> the authority MOVED and this
//     guard has gone blind. That fails loudly. Skipping there would be the silent
//     pass this whole barrier exists to prevent.
const BACKEND_CHECKED_OUT = existsSync(BACKEND_DIR)

/** Reads the authority, or fails with the reason the mirror can no longer be checked. */
function readErrorCodeJava() {
  if (!existsSync(ERROR_CODE_JAVA)) {
    throw new Error(
      `ErrorCode.java introuvable à ${ERROR_CODE_JAVA} alors que ../backend existe : ` +
        `l'autorité du contrat a bougé, et cette garde anti-dérive ne protège plus rien. ` +
        `Corriger le chemin ici, ne pas neutraliser le test.`,
    )
  }
  return readFileSync(ERROR_CODE_JAVA, 'utf8')
}

/** `it` for the guards that need the backend sources; skipped (not failed) without them. */
const itWithBackend = it.skipIf(!BACKEND_CHECKED_OUT)

/** Shapes an AxiosError the way `client.js` relays it: `err.response.data` is the envelope. */
function httpError(status: number, data: unknown) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data },
  })
}

describe('isBareAuthFailure — the one rule that decides whether a session dies', () => {
  // Asserted directly, and not only through `classifyReplayFailure`, because
  // Story 1.9 gave this predicate a second caller with a far heavier
  // consequence: the interceptor of `api/client.js` ends the session on it. A
  // false positive signs a user out on a business verdict; a false negative
  // leaves a revoked token in a zombie authenticated UI.
  it('recognises a bare 401 and a bare 403 — the shape an expired or revoked token really takes', () => {
    expect(isBareAuthFailure({ code: null, status: 403 })).toBe(true)
    expect(isBareAuthFailure({ code: null, status: 401 })).toBe(true)
    // `undefined` reaches it too: callers hand over raw envelopes, not only
    // `extractFailureReason`'s normalised nulls.
    expect(isBareAuthFailure({ status: 403 })).toBe(true)
  })

  it('refuses a *coded* 403 — an authorization verdict is not an expiry', () => {
    expect(isBareAuthFailure({ code: 'NOT_A_PARTY', status: 403 })).toBe(false)
    expect(isBareAuthFailure({ code: 'FORBIDDEN', status: 403 })).toBe(false)
    expect(isBareAuthFailure({ code: 'UNAUTHORIZED_TRANSITION', status: 403 })).toBe(false)
  })

  it('refuses a coded 401 AUTH_FAILED — a wrong password must not reset the sign-in form', () => {
    expect(isBareAuthFailure({ code: 'AUTH_FAILED', status: 401 })).toBe(false)
  })

  it('refuses every other bare status, however envelope-less', () => {
    expect(isBareAuthFailure({ code: null, status: 404 })).toBe(false)
    expect(isBareAuthFailure({ code: null, status: 500 })).toBe(false)
    // No response at all (network down): nothing here says the session is dead.
    expect(isBareAuthFailure({ code: null, status: null })).toBe(false)
    expect(isBareAuthFailure(null)).toBe(false)
  })
})

describe('classifyReplayFailure — the status decides before the code', () => {
  it('treats a rejection with no response (network down) as transient', () => {
    expect(classifyReplayFailure(new Error('network down'))).toBe('transient')
  })

  it('treats a bare 401 or 403 as transient — an expired session carries no envelope', () => {
    // Never frozen: the very same request is valid once re-authenticated. 403 is
    // the one that actually happens here (Http403ForbiddenEntryPoint); 401 is
    // covered because the day a real entry point is configured, evidence must
    // not start freezing on an expiry.
    expect(classifyReplayFailure(httpError(401, ''))).toBe('transient')
    expect(classifyReplayFailure(httpError(403, ''))).toBe('transient')
  })

  it('still classifies a *coded* 403 as permanent — an expiry is not a verdict', () => {
    // The absence of the envelope is the discriminator, never the status: this is
    // a genuine authorization verdict and replaying it would fail identically.
    expect(classifyReplayFailure(httpError(403, { code: 'NOT_A_PARTY' }))).toBe('permanent')
    expect(classifyReplayFailure(httpError(403, { code: 'UNAUTHORIZED_TRANSITION' }))).toBe('permanent')
  })

  it('treats 408 / 429 / 5xx as transient before any code is read', () => {
    expect(classifyReplayFailure(httpError(408, {}))).toBe('transient')
    expect(classifyReplayFailure(httpError(429, {}))).toBe('transient')
    expect(classifyReplayFailure(httpError(503, {}))).toBe('transient')
    // A 500 whose envelope carries a permanent code is still transient: the
    // status rule fires first.
    expect(classifyReplayFailure(httpError(500, { code: 'EVIDENCE_INVALID' }))).toBe('transient')
  })
})

describe('classifyReplayFailure — the verdict comes from the code, not the HTTP class', () => {
  it('classifies CONCURRENT_MODIFICATION (409) as transient', () => {
    expect(classifyReplayFailure(httpError(409, { code: 'CONCURRENT_MODIFICATION' }))).toBe('transient')
  })

  it('classifies FILE_READ_ERROR (400) as transient — the status does not decide', () => {
    expect(classifyReplayFailure(httpError(400, { code: 'FILE_READ_ERROR' }))).toBe('transient')
  })

  it('classifies STORAGE_UNAVAILABLE as transient', () => {
    expect(classifyReplayFailure(httpError(502, { code: 'STORAGE_UNAVAILABLE' }))).toBe('transient')
  })

  it('classifies SCAN_UNAVAILABLE (502) as transient — an antivirus outage must never freeze a legitimate proof', () => {
    // Story 1.8. Le garde-fou TRANSIENT_CODES ci-dessous prouve la SYNCHRONISATION
    // avec l'enum ; il ne prouve pas que ce code-ci se comporte comme il le doit.
    // Sans cette entrée, le défaut « permanent » gèlerait une preuve pour une
    // simple panne d'infrastructure.
    expect(classifyReplayFailure(httpError(502, { code: 'SCAN_UNAVAILABLE' }))).toBe('transient')
  })

  it('classifies EVIDENCE_MALWARE_DETECTED (400) as permanent and renders a real sentence, not the raw code', () => {
    // Rejouer le même fichier redéclenchera à l'identique : l'entrée doit être
    // gelée, avec un motif lisible. `SOME_CODE` n'est pas une phrase.
    const err = httpError(400, { code: 'EVIDENCE_MALWARE_DETECTED' })
    expect(classifyReplayFailure(err)).toBe('permanent')

    const label = describeFailure(extractFailureReason(err))
    expect(label).not.toContain('EVIDENCE_MALWARE_DETECTED')
    expect(label).toBe(FAILURE_LABELS.EVIDENCE_MALWARE_DETECTED)
    expect(label).toMatch(/antivirus/i)
  })

  it('classifies DISPUTE_ALREADY_RESOLVED (409) as permanent — same status, opposite verdict', () => {
    expect(classifyReplayFailure(httpError(409, { code: 'DISPUTE_ALREADY_RESOLVED' }))).toBe('permanent')
  })

  it('classifies EVIDENCE_INVALID (400) as permanent — same status, opposite verdict', () => {
    expect(classifyReplayFailure(httpError(400, { code: 'EVIDENCE_INVALID' }))).toBe('permanent')
  })

  it("gèle les rejets natifs de Spring (Story 1.10) — c'est la reclassification voulue, pas un effet de bord", () => {
    // La Story 1.10 a sorti les rejets natifs du filet 500 pour leur donner un 4xx codé.
    // Ce faisant elle les fait passer, ICI, de transitoire à PERMANENT. Ce test asservit
    // cette moitié-là du changement, qu'aucune assertion ne couvrait : le backend prouve
    // qu'il renvoie bien 404/400/405 codés, rien ne prouvait ce que le client en fait.
    expect(classifyReplayFailure(httpError(404, { code: 'RESOURCE_NOT_FOUND' }))).toBe('permanent')
    expect(classifyReplayFailure(httpError(400, { code: 'INVALID_REQUEST' }))).toBe('permanent')
    expect(classifyReplayFailure(httpError(405, { code: 'INVALID_REQUEST' }))).toBe('permanent')

    // La direction opposée, pour que le compromis soit lisible : sous l'ancien 500 la
    // MÊME requête était rejouée indéfiniment. Geler n'est pas perdre — l'entrée et son
    // binaire sont conservés et la Story 4.5 fournit la reprise — mais une PWA au shell
    // précaché qui rejoue contre des routes déplacées ne guérit plus toute seule. C'est
    // le choix d'AD-10, et il est ici écrit noir sur blanc.
    expect(classifyReplayFailure(httpError(500, { code: 'INTERNAL_ERROR' }))).toBe('transient')
  })
})

describe('classifyReplayFailure — no code, no assumption', () => {
  it('classifies a bare 404 (routing, no envelope) as permanent', () => {
    expect(classifyReplayFailure(httpError(404, ''))).toBe('permanent')
  })

  it('classifies an unknown code under a 4xx as permanent — permanent is the default', () => {
    expect(classifyReplayFailure(httpError(400, { code: 'SOME_CODE_ADDED_LATER' }))).toBe('permanent')
  })
})

describe('TRANSIENT_CODES', () => {
  itWithBackend('mirrors exactly the TRANSIENT partition declared by ErrorCode.java (needs ../backend)', () => {
    // Read from the authority itself rather than compared to a literal spelled
    // out right here: asserting the Set against a hand-copied list would be a
    // tautology that no backend drift could ever break. `ErrorCodeContractTest`
    // guards the Java side, but nothing there knows this file exists — a backend
    // dev adding a TRANSIENT code would fix the Java literal and leave the front
    // silently freezing entries that deserved a retry. This is the only link
    // between the two.
    const java = readErrorCodeJava()
    const declared = [...java.matchAll(/(\w+)\(Retryability\.TRANSIENT\)/g)].map((m) => m[1])

    expect(declared.length).toBeGreaterThan(0) // the regex still matches the enum's shape
    expect([...TRANSIENT_CODES].sort()).toEqual(declared.sort())
  })
})

describe('FAILURE_LABELS', () => {
  itWithBackend('names only codes the PERMANENT partition of ErrorCode.java really declares (needs ../backend)', () => {
    // Same rationale as the TRANSIENT_CODES guard: read the authority, never a
    // hand-copied list. A subset assertion, not an equality — AUTH_FAILED is
    // PERMANENT but partner-only, so it legitimately has no label. The regex is
    // pinned to `Retryability.PERMANENT` and not to `Retryability.\w+`: a code
    // reclassified TRANSIENT backend-side would otherwise keep its label here
    // while becoming unreachable (`classifyReplayFailure` never freezes a
    // transient), and the drift would pass in silence.
    const java = readErrorCodeJava()
    const permanent = [...java.matchAll(/(\w+)\(Retryability\.PERMANENT\)/g)].map((m) => m[1])

    expect(permanent.length).toBeGreaterThan(0) // the regex still matches the enum's shape
    // The subset assertion below is vacuously green on an empty map — it filters
    // the map's own keys, so deleting every label would pass it while every user
    // fell back to the generic refusal. This says the map is still populated.
    // Deliberately not an equality against `permanent`: a *subset* is the spec's
    // standing decision (AUTH_FAILED is PERMANENT but partner-only, and a new
    // backend code must fall back on its message, not redden the front).
    expect(Object.keys(FAILURE_LABELS).length).toBeGreaterThan(0)
    const unknown = Object.keys(FAILURE_LABELS).filter((code) => !permanent.includes(code))
    // Story 1.10 — la liste des codes HÉRITÉS, et rien d'autre.
    //
    // L'anti-énumération a retiré `NOT_A_PARTY` de `ErrorCode.java` : le backend ne
    // l'émet plus. Son libellé reste pourtant ici volontairement, parce qu'un `code`
    // est gravé dans l'entrée gelée persistée en IndexedDB au moment du refus — une
    // file remplie avant le déploiement le porte encore. Le miroir est donc, pour ce
    // code précis, un SUR-ensemble assumé de l'autorité backend.
    //
    // Égalité EXACTE et non « ignore les codes hérités » : les deux directions de
    // dérive doivent rester rouges. Ajouter un libellé pour un code que le backend ne
    // déclare pas reste une erreur ; retirer le libellé hérité (le nettoyage évident,
    // « ce code n'existe plus côté serveur ») casse la seule prise en charge des
    // entrées d'avant la bascule — les deux méritent d'être un geste délibéré. Idem si
    // le backend rouvrait un jour ce code : la liste devrait alors être vidée ici.
    //
    // Trié des deux côtés : `unknown` dérive de l'ordre d'insertion de FAILURE_LABELS,
    // qui n'est le contrat de personne. Avec un seul élément la comparaison brute était
    // verte par chance ; au deuxième code hérité elle serait devenue un test qui casse
    // parce qu'on a déplacé une ligne. Le tri retire cette sensibilité SANS rien
    // affaiblir — c'est toujours une égalité exacte, donc les deux directions de dérive
    // restent rouges (un libellé en trop comme un libellé hérité supprimé).
    const LEGACY_CODES = ['NOT_A_PARTY']
    expect([...unknown].sort()).toEqual([...LEGACY_CODES].sort())
  })

})

describe('describeFailure — code first, message second, generic last', () => {
  it('prefers the label of a known code over the server message', () => {
    // The whole point: `message` is interpolated and locked by no contract
    // (ErrorCode.java:8-11), so it must never decide what the user reads.
    const reason = describeFailure({
      code: 'DISPUTE_ALREADY_RESOLVED',
      status: 409,
      message: 'Dispute on transaction 7 was already arbitrated',
    })

    expect(reason).toBe(FAILURE_LABELS.DISPUTE_ALREADY_RESOLVED)
    expect(reason).not.toBe('Dispute on transaction 7 was already arbitrated')
  })

  it('falls back on the server message when the code is unknown', () => {
    // A code added backend-side and not yet labelled here: the message is the
    // best thing left. Never the raw code — `SOME_NEW_CODE` is not a sentence.
    const reason = describeFailure({ code: 'SOME_NEW_CODE', status: 400, message: 'Server said no' })

    expect(reason).toBe('Server said no')
    expect(reason).not.toContain('SOME_NEW_CODE')
  })

  it('falls back on a generic refusal when there is no envelope at all', () => {
    // A routing 404 or Spring's /error: `extractFailureReason` yields nulls
    // rather than an axios-internal string, so this is the shape that arrives.
    const reason = describeFailure({ code: null, status: 404, message: null })

    expect(reason).toBe(describeFailure(null))
    expect(reason).toMatch(/refused/i)
    // Never the axios internals, whatever happens.
    expect(reason).not.toMatch(/status code/i)
  })

  it('ignores an empty server message rather than showing a blank reason', () => {
    expect(describeFailure({ code: null, status: 400, message: '   ' })).toBe(
      describeFailure({ code: null, status: 400, message: null }),
    )
  })

  it('does not walk the prototype chain for a code coming off a response', () => {
    // `code` is attacker-adjacent data: a plain `FAILURE_LABELS[code]` lookup
    // would return Object.prototype.constructor here — a function, rendered as
    // source text next to the user's dispute.
    const reason = describeFailure({ code: 'constructor', status: 400, message: 'Server said no' })

    expect(reason).toBe('Server said no')
  })
})

describe('extractFailureReason', () => {
  it('reads code, status and message out of the envelope', () => {
    const reason = extractFailureReason(
      httpError(409, { code: 'DISPUTE_ALREADY_RESOLVED', message: 'Le litige a déjà été arbitré' }),
    )

    expect(reason).toEqual({
      code: 'DISPUTE_ALREADY_RESOLVED',
      status: 409,
      message: 'Le litige a déjà été arbitré',
    })
  })

  it('yields a null code AND a null message — never a guess — when there is no envelope', () => {
    // The message is held to the same standard as the code: `err.message` is an
    // axios-internal English string, and 4.4 is meant to show this to a user.
    expect(extractFailureReason(httpError(404, ''))).toEqual({
      code: null,
      status: 404,
      message: null,
    })
  })

  it('yields nothing at all for a network failure — which never freezes anyway', () => {
    expect(extractFailureReason(new Error('network down'))).toEqual({
      code: null,
      status: null,
      message: null,
    })
  })
})
