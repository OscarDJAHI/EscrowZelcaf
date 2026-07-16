package com.zlecaf.escrow.domain;

/**
 * Lifecycle states of an escrow transaction, per the finite state machine
 * defined in the backend schema specification.
 */
public enum EscrowState {
    /** Contract created, awaiting buyer payment. */
    INITIATED,
    /** Buyer paid; funds are held in escrow. */
    FUNDS_LOCKED,
    /** Seller shipped the goods and provided logistics proof. */
    SHIPPED,
    /** Funds released to the seller (terminal). */
    RELEASED,
    /**
     * A party opened a dispute; funds are frozen pending arbitration. Evidence
     * mutation stays open (see {@link #allowsEvidenceMutation()}) so parties can
     * still file counter-proof until the transaction reaches a terminal state.
     */
    DISPUTED,
    /** Funds refunded to the buyer (terminal). */
    REFUNDED;

    /** Terminal states admit no further transitions. */
    public boolean isTerminal() {
        return this == RELEASED || this == REFUNDED;
    }

    /** Source de vérité unique : dépôt ET retrait de preuve permis ssi vrai (FR-8, AD-2). */
    public boolean allowsEvidenceMutation() {
        return this == FUNDS_LOCKED || this == SHIPPED || this == DISPUTED;
    }
}
