import { describe, expect, it } from 'vitest'
import { globSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { ALL_STATES, STATE_COLORS, STATE_TOKENS } from '@/utils/stateMachine'

/**
 * Le code couleur du cycle de vie escrow est un CONTRAT, pas une préférence
 * esthétique : il doit être identique dans l'app cliente, la console d'arbitrage et
 * le back-office (DESIGN.md, « un code couleur d'état unique app + back-office »).
 *
 * Ces tests asservissent la SÉMANTIQUE (`INITIATED` est un état d'attente, donc
 * warning) et non des chaînes de classes. Asservir les classes aurait fait passer
 * n'importe quel remaniement de style pour une régression, tout en laissant un
 * changement de sens passer inaperçu.
 */
describe('STATE_TOKENS — mapping état → famille sémantique', () => {
  it('associe chaque état du cycle de vie à sa famille, conformément à DESIGN.md', () => {
    expect(STATE_TOKENS).toEqual({
      INITIATED: 'warning',
      FUNDS_LOCKED: 'info',
      SHIPPED: 'info',
      RELEASED: 'success',
      DISPUTED: 'danger',
      REFUNDED: 'neutral',
    })
  })

  it("INITIATED est une ATTENTE, pas un état neutre — corrigé à la Story 2.1", () => {
    // Le POC le rendait en gris, donc indistinguable d'une transaction annulée ou
    // remboursée. Une invitation en attente appelle une action : elle est warning.
    expect(STATE_TOKENS.INITIATED).toBe('warning')
    expect(STATE_TOKENS.INITIATED).not.toBe('neutral')
  })

  it("SHIPPED est un AVANCEMENT, pas une attente — corrigé à la Story 2.1", () => {
    // Le POC le rendait en ambre, couleur réservée à l'attente. L'expédition est un
    // progrès dans le flux principal : elle partage `info` avec FUNDS_LOCKED.
    expect(STATE_TOKENS.SHIPPED).toBe('info')
    expect(STATE_TOKENS.SHIPPED).toBe(STATE_TOKENS.FUNDS_LOCKED)
  })

  it('couvre TOUS les états connus de la machine — aucun état sans couleur', () => {
    // Sans cette garde, ajouter un état à la machine (EXPIRED viendra avec l'Epic 5)
    // le laisserait tomber sur une couleur de repli muette.
    for (const state of ALL_STATES) {
      expect(STATE_TOKENS[state], `état ${state} sans famille sémantique`).toBeDefined()
    }
  })
})

describe('STATE_COLORS — classes dérivées du mapping', () => {
  it('dérive ses classes de STATE_TOKENS, sans table parallèle', () => {
    for (const state of ALL_STATES) {
      const token = STATE_TOKENS[state]
      expect(STATE_COLORS[state].badge).toContain(token)
      expect(STATE_COLORS[state].dot).toContain(token)
    }
  })

  it('expose la forme attendue par les trois consommateurs existants', () => {
    // StateBadge lit `.badge`, StepperEscrow et AuditTimeline lisent `.dot`.
    // Changer cette forme sans les mettre à jour casserait le rendu en silence.
    for (const state of ALL_STATES) {
      expect(STATE_COLORS[state]).toHaveProperty('badge')
      expect(STATE_COLORS[state]).toHaveProperty('dot')
      expect(STATE_COLORS[state]).toHaveProperty('ring')
    }
  })
})

describe('Interdits de DESIGN.md — vérifiés sur les sources, pas sur une intention', () => {
  const SRC = resolve(process.cwd(), 'src')

  /**
   * Toutes les sources front, hors suites de test.
   *
   * L'exclusion des `__tests__` n'est pas une commodité : ce fichier-ci doit nommer les
   * motifs qu'il interdit (le hex du vert rejeté, `linear-gradient`…) pour pouvoir les
   * chercher. Sans exclusion, la garde se déclencherait sur elle-même et il faudrait
   * l'affaiblir — c'est le code de production qu'elle surveille.
   */
  function sourceFiles() {
    return globSync('**/*.{vue,js,css}', { cwd: SRC })
      .filter((rel) => !rel.includes('__tests__'))
      .map((rel) => ({ rel, body: readFileSync(resolve(SRC, rel), 'utf8') }))
  }

  it("le vert clair EscrowLab #50DF77 n'apparaît nulle part", () => {
    // Rejeté par DESIGN.md : contraste insuffisant avec du texte blanc.
    const offenders = sourceFiles().filter(({ body }) => /#50df77/i.test(body))
    expect(offenders.map((f) => f.rel)).toEqual([])
  })

  it("aucun dégradé décoratif n'est utilisé", () => {
    const offenders = sourceFiles().filter(({ body }) =>
      /linear-gradient|radial-gradient|\bbg-gradient-to-\b/i.test(body),
    )
    expect(offenders.map((f) => f.rel)).toEqual([])
  })

  it("le navy de marque n'est jamais employé comme couleur d'état", () => {
    // Le navy est le chrome applicatif. L'employer pour un état ferait entrer une
    // septième couleur dans un code sémantique qui en compte six.
    const stateClasses = Object.values(STATE_COLORS).flatMap((entry) => Object.values(entry))
    const navyUsedAsState = stateClasses.filter((cls) => /brand-navy/.test(cls))
    expect(navyUsedAsState).toEqual([])
  })
})
