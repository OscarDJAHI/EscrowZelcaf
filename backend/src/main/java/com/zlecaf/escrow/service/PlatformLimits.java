package com.zlecaf.escrow.service;

/**
 * Shared platform hard limits, kept in one place so the same bound is applied
 * consistently across services (deposit and dispute opening share the file cap;
 * the evidence list and the audit trail share the result cap). These are not
 * per-file business rules (see {@code EvidenceService.MAX_FILE_SIZE}); they cap
 * batch cardinality and unbounded reads before Epic 3/4 amplify the load.
 */
public final class PlatformLimits {

    private PlatformLimits() {}

    /**
     * Maximum number of files accepted in a single deposit (or composite dispute
     * opening) multipart request. Rejected <em>before</em> any byte buffering so a
     * hostile batch cannot exhaust memory.
     */
    public static final int MAX_FILES_PER_DEPOSIT = 20;

    /**
     * Hard cap on rows returned by an unbounded list read (evidence list, audit
     * trail). Bounds memory/load without paginating: the response shape stays a
     * JSON array, only the length is capped.
     */
    public static final int MAX_LIST_RESULTS = 500;
}
