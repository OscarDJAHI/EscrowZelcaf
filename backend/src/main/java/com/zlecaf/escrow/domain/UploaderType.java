package com.zlecaf.escrow.domain;

/**
 * The capacity in which evidence was contributed to a transaction. A partner
 * carrier uploads on behalf of a company rather than as an authenticated party,
 * which is why it is distinct from the transaction's buyer and seller.
 */
public enum UploaderType {
    BUYER,
    SELLER,
    ADMIN,
    CARRIER_PARTNER
}
