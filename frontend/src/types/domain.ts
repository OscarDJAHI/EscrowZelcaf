/**
 * Formes du CONTRAT SERVEUR, écrites une fois.
 *
 * <p>Ces types étaient déjà décrits, en JSDoc, dans `api/*.ts` — mais une annotation
 * JSDoc ne se vérifie pas : `fetchTransactions` promettait `Promise<Array>` et rien
 * n'empêchait un composant de lire un champ inexistant. La migration promeut ces
 * descriptions au rang de contrat contrôlé.
 *
 * <p><b>Ce fichier ne décrit que ce que le serveur envoie</b>, jamais ce que l'interface
 * en fait. Les formes propres au client (entrées de la file hors ligne, état gelé d'une
 * reprise) vivent avec le module qui les possède : les mélanger ici ferait croire à un
 * contrat serveur là où il n'y en a pas.
 */

/**
 * Rôles de compte.
 *
 * <p>`ARBITRATOR` est déclaré ici comme il l'est dans `router/spaces.ts` : la console
 * d'arbitrage a son garde, mais le rôle n'existe pas encore dans l'énumération backend
 * (`Role.java` vaut `{BUYER, SELLER, ADMIN}`) — son octroi est porté par la Story 7-2.
 * Le décrire est correct ; l'attribuer sans mécanisme audité ne le serait pas.
 */
export type Role = 'BUYER' | 'SELLER' | 'ADMIN' | 'ARBITRATOR'

/** États de la machine escrow, miroir de `EscrowStateMachine` côté serveur. */
export type EscrowState =
  | 'INITIATED'
  | 'FUNDS_LOCKED'
  | 'SHIPPED'
  | 'RELEASED'
  | 'DISPUTED'
  | 'REFUNDED'

/** Événements de transition déclenchables depuis l'interface. */
export type EscrowEventName =
  | 'PAY_FUNDS'
  | 'SHIP_GOODS'
  | 'DELIVERY_CONFIRMED'
  | 'OPEN_DISPUTE'
  | 'RESOLVE_RELEASE'
  | 'RESOLVE_REFUND'

/** Familles sémantiques du code couleur (DESIGN.md). Voir `utils/stateMachine.ts`. */
export type StateToken = 'warning' | 'info' | 'success' | 'danger' | 'neutral' | 'offline'

/** Profil porté par la session, et persisté sous `escrow_user`. */
export interface User {
  id: number
  email: string
  firstName?: string
  lastName?: string
  role: Role
}

/** Ce que rendent `POST /auth/login` et `POST /auth/verify-email`. */
export interface Session {
  token: string
  user: User
}

/** Une transaction escrow telle que le serveur la sérialise. */
export interface Transaction {
  id: number
  buyerEmail: string
  sellerEmail: string
  amount: number
  currency: string
  description?: string
  state: EscrowState
  createdAt?: string
  updatedAt?: string
  version?: number
}

/** Une ligne du journal d'audit d'une transaction. */
export interface AuditLog {
  id: number
  actionBy?: string | null
  previousState?: EscrowState | null
  nextState: EscrowState
  payload?: string | null
  timestamp: string
}

/** Réponse de `GET /escrow/{id}`. */
export interface TransactionDetail {
  transaction: Transaction
  auditLogs: AuditLog[]
}

/** Qui a déposé une preuve. `CARRIER_PARTNER` vient des dépôts partenaires signés. */
export type UploaderType = 'BUYER' | 'SELLER' | 'ADMIN' | 'CARRIER_PARTNER'

/** Une preuve retirée reste listée : le retrait est LOGIQUE, jamais une suppression. */
export type EvidenceStatus = 'ACTIVE' | 'WITHDRAWN'

/** Une pièce déposée sur une transaction. */
export interface EvidenceItem {
  id: number
  transactionId?: number
  originalFilename: string
  mimeType: string
  sizeBytes: number
  status: EvidenceStatus
  uploadedByUserId?: number | null
  uploaderType?: UploaderType
  partnerCompanyId?: number | null
  comment?: string | null
  createdAt: string
  withdrawnAt?: string | null
  withdrawnByUserId?: number | null
}

/** Réponse de l'ouverture de litige composite (`POST /escrow/{id}/dispute`). */
export interface DisputeOpened {
  transaction: Transaction
  evidence: EvidenceItem[]
}
