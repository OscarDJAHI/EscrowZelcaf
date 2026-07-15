package com.zlecaf.escrow.domain;

/**
 * The capacity in which the acting user relates to a given transaction. This is
 * derived per-transaction (buyer vs seller identity, or platform ADMIN) and is
 * what the state machine authorises against — not the account's static role.
 */
public enum ParticipantRole {
    BUYER,
    SELLER,
    ADMIN
}
