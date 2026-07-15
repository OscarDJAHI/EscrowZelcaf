package com.zlecaf.escrow.domain;

/**
 * Triggers that drive the escrow state machine. Each event is only valid from
 * specific states and for specific roles (see {@link com.zlecaf.escrow.service.EscrowStateMachine}).
 */
public enum EscrowEvent {
    /** Buyer's funds have been locked in escrow (payment gateway / system). */
    PAY_FUNDS,
    /** Seller declares the shipment. */
    SHIP_GOODS,
    /** Buyer confirms delivery; funds are released to the seller. */
    DELIVERY_CONFIRMED,
    /** Either party freezes the process by opening a dispute. */
    OPEN_DISPUTE,
    /** Admin arbitration in favour of the seller. */
    RESOLVE_RELEASE,
    /** Admin arbitration in favour of the buyer (refund). */
    RESOLVE_REFUND
}
