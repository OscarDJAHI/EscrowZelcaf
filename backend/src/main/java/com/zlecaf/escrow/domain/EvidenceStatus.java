package com.zlecaf.escrow.domain;

/**
 * Lifecycle of a piece of evidence. Withdrawal is logical only — the stored
 * binary is never physically deleted, so the audit trail stays intact.
 */
public enum EvidenceStatus {
    ACTIVE,
    WITHDRAWN
}
