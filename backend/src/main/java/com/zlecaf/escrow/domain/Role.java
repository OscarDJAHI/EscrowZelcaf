package com.zlecaf.escrow.domain;

/**
 * Platform roles. A single user may act as a buyer on one transaction and a
 * seller on another; the role stored here is the account's primary capacity,
 * while transaction-level authorisation is derived from buyer/seller identity.
 */
public enum Role {
    BUYER,
    SELLER,
    ADMIN
}
