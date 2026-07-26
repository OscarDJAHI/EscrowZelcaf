package com.zlecaf.escrow.domain;

/**
 * The authoritative application error vocabulary carried by the {@code code}
 * field of every error envelope built by {@code GlobalExceptionHandler}.
 *
 * <p><strong>Why it exists.</strong> The HTTP status is too coarse (one 400
 * covers a permanently invalid file and a transiently unreadable one) and the
 * {@code message} text is interpolated, localisable and locked by no contract —
 * neither can be classified against. A client (AD-10: the offline replay queue)
 * must decide "retry later" vs "give up and surface" on a stable machine token;
 * this enum is that token, and it is the single source of truth.
 *
 * <p><strong>Where it lives.</strong> In {@code domain/} so both {@code service/}
 * (which throws) and {@code web/} (which serialises) may import it without
 * inverting the downward dependency rule.
 *
 * <p><strong>Retryability is not serialised.</strong> The envelope carries only
 * {@code code}; whether a code is worth retrying is policy that belongs to the
 * client. The classification is kept here — and locked by
 * {@code ErrorCodeContractTest} — as the authority the client mirrors, so a
 * rename or a reclassification breaks a test instead of breaking a queue in
 * silence.
 */
public enum ErrorCode {

    // --- Lifecycle / state machine -------------------------------------------

    /**
     * A dispute was replayed against a transaction whose dispute has already been
     * arbitrated. Distinct from {@link #TRANSACTION_TERMINAL}: the state alone
     * cannot tell the two apart (RELEASED is reachable both by arbitration and by
     * a plain delivery confirmation), so the audit trail is the discriminator.
     */
    DISPUTE_ALREADY_RESOLVED(Retryability.PERMANENT),

    /** The transaction reached a terminal state (RELEASED/REFUNDED): nothing more may happen to it. */
    TRANSACTION_TERMINAL(Retryability.PERMANENT),

    /** The event is not defined for the current (non-terminal) state. */
    ILLEGAL_TRANSITION(Retryability.PERMANENT),

    /** The event is defined for the current state but the acting role may not trigger it. */
    UNAUTHORIZED_TRANSITION(Retryability.PERMANENT),

    // --- Evidence ------------------------------------------------------------

    /** Evidence mutation is closed for the current (non-terminal) state. */
    WINDOW_CLOSED(Retryability.PERMANENT),

    /** The file itself is unacceptable: empty, off-whitelist, inconsistent type, or over the size cap. */
    EVIDENCE_INVALID(Retryability.PERMANENT),

    /**
     * L'analyse antivirus à l'ingestion a déclenché sur le fichier (Story 1.8,
     * NFR-P7). PERMANENT, et non « transitoire le temps que les bases changent » :
     * rejouer le MÊME fichier redéclenchera à l'identique, donc la file offline doit
     * geler l'entrée et afficher le motif plutôt que la rejouer indéfiniment. Le nom
     * de la signature reste au journal serveur et dans l'entrée d'audit — jamais
     * dans la réponse, qui serait sinon un banc d'essai d'évasion.
     */
    EVIDENCE_MALWARE_DETECTED(Retryability.PERMANENT),

    /** Withdrawing would leave a DISPUTED transaction with no active evidence (FR-6). */
    EVIDENCE_FLOOR_VIOLATION(Retryability.PERMANENT),

    /** The batch carries more files than a single deposit accepts. */
    TOO_MANY_FILES(Retryability.PERMANENT),

    /** The dispute comment is absent or shorter than the required minimum. */
    COMMENT_TOO_SHORT(Retryability.PERMANENT),

    // --- Access / identity ---------------------------------------------------

    /** The actor (or the partner's company) is not a party to the transaction. */
    NOT_A_PARTY(Retryability.PERMANENT),

    /** The referenced transaction does not exist. */
    TRANSACTION_NOT_FOUND(Retryability.PERMANENT),

    /**
     * A partner request failed authentication. Deliberately opaque: unknown key,
     * inactive key, bad signature, stale or unparseable timestamp and replayed
     * nonce all share this one code. Splitting it would let a caller enumerate
     * which key-ids exist and are active.
     */
    AUTH_FAILED(Retryability.PERMANENT),

    // --- Transient -----------------------------------------------------------

    /**
     * An optimistic-lock collision: a concurrent writer won. The same request
     * replayed against fresh state may well succeed.
     */
    CONCURRENT_MODIFICATION(Retryability.TRANSIENT),

    /**
     * The uploaded bytes could not be read. The file may be perfectly valid — the
     * transfer was not — so this is retryable even though it surfaces as a 400.
     * The clearest proof that the code is not a function of the status.
     */
    FILE_READ_ERROR(Retryability.TRANSIENT),

    /** The object store is unreachable or failed; the deposit may succeed later. */
    STORAGE_UNAVAILABLE(Retryability.TRANSIENT),

    /**
     * Aucun verdict d'analyse n'a pu être obtenu : moteur injoignable, timeout,
     * réponse incomprise (Story 1.8, NFR-P7). TRANSIENT — le fichier n'est pas en
     * cause, le scanner l'était : la file DOIT rejouer, faute de quoi une preuve
     * parfaitement légitime serait gelée définitivement par une panne d'infra.
     * Symétrique de {@link #STORAGE_UNAVAILABLE}, l'autre dépendance sortante du
     * dépôt.
     */
    SCAN_UNAVAILABLE(Retryability.TRANSIENT),

    /**
     * Anti-bruteforce (Story 1.3, NFR-P2) : l'origine a depasse le seuil de
     * tentatives sur /auth/login ou /auth/register. TRANSIENT — l'acces se
     * retablit seul a l'expiration de la fenetre (Retry-After la porte).
     */
    RATE_LIMITED(Retryability.TRANSIENT),

    // --- Framework / completeness defaults ------------------------------------

    /** Bean-validation rejected the request body. */
    VALIDATION_ERROR(Retryability.PERMANENT),

    /**
     * Le mot de passe fourni (inscription ou changement) ne respecte pas la
     * politique de robustesse (Story 1.6, NFR-P5). PERMANENT : rejouer le même mot
     * de passe échouera à l'identique. Le message énumère les règles (endpoint
     * public, PAS anti-énumération — contrairement à AUTH_FAILED).
     */
    WEAK_PASSWORD(Retryability.PERMANENT),

    /** A required multipart part, request parameter or header is absent. */
    MISSING_REQUEST_PART(Retryability.PERMANENT),

    /** Default for a {@code BadRequestException} raised without an explicit code. */
    INVALID_REQUEST(Retryability.PERMANENT),

    /** Default for a {@code NotFoundException} raised without an explicit code. */
    RESOURCE_NOT_FOUND(Retryability.PERMANENT),

    /** Default for a {@code ConflictException} raised without an explicit code. */
    CONFLICT(Retryability.PERMANENT),

    /** Default for a {@code ForbiddenException} raised without an explicit code. */
    FORBIDDEN(Retryability.PERMANENT),

    /**
     * Défaut du filet de sécurité : une exception non prévue a atteint
     * {@code GlobalExceptionHandler} (revue 1.6). TRANSIENT — un défaut interne est
     * par nature circonstanciel (panne de base, indisponibilité passagère), et le
     * client a raison de réessayer plus tard plutôt que d'abandonner la requête.
     */
    INTERNAL_ERROR(Retryability.TRANSIENT);

    /** Whether replaying the identical request could plausibly succeed later. */
    public enum Retryability {
        /** Replaying the identical request will fail identically: the client must surface it. */
        PERMANENT,
        /** The failure is circumstantial: the identical request may succeed on a later attempt. */
        TRANSIENT
    }

    private final Retryability retryability;

    ErrorCode(Retryability retryability) {
        this.retryability = retryability;
    }

    public Retryability retryability() {
        return retryability;
    }
}
