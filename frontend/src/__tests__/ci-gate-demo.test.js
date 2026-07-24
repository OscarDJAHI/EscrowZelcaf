// PR de démonstration Story 11.1 (AC3) : test volontairement rouge pour
// prouver que le gate CI bloque. NE PAS MERGER — cette PR sera fermée.
import { describe, it, expect } from 'vitest'

describe('CI gate demo', () => {
  it('échoue volontairement pour prouver le gate', () => {
    expect('gate').toBe('bloquant')
  })
})
