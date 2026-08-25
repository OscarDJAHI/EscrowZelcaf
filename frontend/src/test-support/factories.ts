/**
 * Fabriques de fixtures TYPÉES, pour les suites.
 *
 * <p><b>Pourquoi elles existent.</b> La migration a rendu visible ce que les suites
 * faisaient depuis toujours : construire des objets partiels — une transaction sans
 * devise, une entrée de file sans `method` ni `url` — et les faire passer pour le vrai
 * contrat. Tant que rien ne vérifiait, un test pouvait prouver un comportement sur une
 * forme que la production ne produit jamais. Chaque fabrique part donc d'un objet
 * COMPLET et valide, que l'appelant surcharge sur le seul champ qui l'intéresse.
 *
 * <p><b>Ce que ça change pour un test.</b> Ce qu'il écrit devient ce qu'il teste : un
 * champ mentionné est un champ qui compte, les autres sont là parce que la production les
 * a toujours. L'inverse — le remplissage silencieux — est ce qui a laissé
 * `SyncFailureNotice.spec` affirmer des choses sur des entrées de file sans URL.
 *
 * <p>Hors du projet applicatif (`tsconfig.app.json` l'exclut, comme les `__tests__`) :
 * ce module ne doit jamais être atteignable depuis le code d'application.
 */
import type {
  AuditLog,
  EscrowState,
  EvidenceItem,
  Transaction,
  User,
} from '@/types/domain'
import type { QueueEntry, QueueFailure } from '@/types/queue'

/** Horodatage fixe : une fixture ne doit pas dépendre de l'heure qu'il est. */
export const AT = '2026-01-15T10:00:00.000Z'

export function aUser(overrides: Partial<User> = {}): User {
  return { id: 42, email: 'alice@corp.example', role: 'BUYER', ...overrides }
}

export function aTransaction(overrides: Partial<Transaction> = {}): Transaction {
  return {
    id: 7,
    buyerEmail: 'alice@corp.example',
    sellerEmail: 'bob@corp.example',
    amount: 1500,
    currency: 'USD',
    description: 'Cargaison de cacao',
    state: 'INITIATED',
    createdAt: AT,
    updatedAt: AT,
    ...overrides,
  }
}

export function anAuditLog(overrides: Partial<AuditLog> = {}): AuditLog {
  return { id: 1, actionBy: 'alice@corp.example', nextState: 'FUNDS_LOCKED', timestamp: AT, ...overrides }
}

export function anEvidenceItem(overrides: Partial<EvidenceItem> = {}): EvidenceItem {
  return {
    id: 1,
    originalFilename: 'connaissement.pdf',
    mimeType: 'application/pdf',
    sizeBytes: 2048,
    status: 'ACTIVE',
    uploadedByUserId: 42,
    uploaderType: 'BUYER',
    createdAt: AT,
    ...overrides,
  }
}

/**
 * Une entrée en attente de rejeu.
 *
 * <p>`method` et `url` ont des valeurs par défaut RÉELLES et non vides : `flush()` les
 * envoie, et une entrée sans URL n'existe pas en production.
 */
export function aQueueEntry(overrides: Partial<QueueEntry> = {}): QueueEntry {
  return {
    id: 'entry-1',
    timestamp: AT,
    seq: 1,
    method: 'post',
    url: '/api/v1/escrow/7/dispute',
    meta: { type: 'OPEN_DISPUTE', transactionId: '7', userId: 42 },
    ...overrides,
  }
}

/** Une entrée GELÉE : le serveur a rendu son verdict définitif. */
export function aFrozenEntry(
  overrides: Partial<QueueEntry> = {},
  failure: Partial<QueueFailure> = {},
): QueueEntry {
  return aQueueEntry({
    frozen: true,
    failure: { code: 'ILLEGAL_TRANSITION', status: 409, message: null, at: AT, ...failure },
    ...overrides,
  })
}

/** Un état hors catalogue reste possible : `EXPIRED` arrive avec l'Epic 5. */
export function aState(state: string): EscrowState {
  return state as EscrowState
}
