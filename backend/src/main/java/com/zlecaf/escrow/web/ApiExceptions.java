package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.ErrorCode;

import java.util.Objects;

/**
 * Simple application exceptions mapped to HTTP statuses by the handler.
 *
 * <p>Each type carries an {@link ErrorCode}, supplied at the throw site because
 * the type alone is too coarse: one {@code BadRequestException} covers a
 * permanently invalid file, an over-cap batch, a too-short comment <em>and</em> a
 * transiently unreadable upload. Deriving the code from the type would reproduce
 * exactly the ambiguity the code exists to remove.
 *
 * <p>The legacy {@code (String message)} constructor is kept and delegates to an
 * explicit per-type default, so throw sites that have no more specific meaning
 * stay compilable and still carry a non-null, honest code — the envelope builder
 * uses {@code Map.of}, which rejects a null value.
 *
 * <p><strong>Anti-enumeration convention (Story 1.10, NFR-P9).</strong> A refusal
 * must never tell the caller that a resource they are not entitled to know about
 * exists. Two answers are indistinguishable only when the <em>status</em>, the
 * {@code code}, the {@code message} and the headers all match — the {@code code}
 * is an oracle at exactly the same rank as the status (AD-10), so uniforming one
 * without the other closes nothing.
 *
 * <p><strong>Scope of the claim: response CONTENT, not response TIMING.</strong>
 * What these factories buy is that the two answers are byte-for-byte equal. They
 * say nothing about how long each took, and the two paths demonstrably differ: the
 * "someone else's transaction" path runs a successful {@code findById} plus role
 * resolution (and up to two extra {@code users.findById} on the partner channel),
 * where the "unknown id" path short-circuits. Worse, {@code findByIdForUpdate}
 * makes two concurrent probes on a <em>real</em> id serialise on a
 * {@code PESSIMISTIC_WRITE} lock, which never happens on a fictitious one — a
 * side channel an attacker can trigger on demand rather than merely measure.
 * Both are known, out of scope here, and carried by the "canal temporel" entry of
 * {@code deferred-work.md}. Claiming more than content-equality in a future
 * javadoc would be claiming something no test in this repo checks.
 *
 * <p>That is why "unknown" and "not yours" are produced <em>here</em>, by a single
 * factory per resource family ({@link #transactionNotFound()},
 * {@link #evidenceNotFound()}), and never by two independent literals at two throw
 * sites: two literals drift the day one of them is reworded, and the property dies
 * in silence with no test able to see it. The repo already paid for that lesson on
 * the partner auth message, declared twice
 * ({@code deferred-work.md}, entry "Invalid partner credentials").
 *
 * <p>Every protected resource added later — wallet, ticket, dispute thread, KYB
 * file — adds its own factory here and throws it from its centralised guard,
 * rather than hand-writing a refusal at the endpoint.
 */
public final class ApiExceptions {

    private ApiExceptions() {}

    /**
     * The one and only answer to "this transaction is unknown" <em>and</em> to
     * "this transaction is not yours" (Story 1.10, NFR-P9). Deliberately opaque:
     * merging the two cases is the whole point, and it is not a lie — it is a
     * refusal to confirm existence to someone who has no right to learn it.
     *
     * <p>The message carries <strong>no identifier</strong>. The id is already in
     * the URL, so repeating it buys the caller nothing; dropping it makes the two
     * responses comparable literally instead of "up to the id", which is what turns
     * the property into something a test can assert byte for byte.
     */
    public static NotFoundException transactionNotFound() {
        return new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND, "Transaction not found");
    }

    /**
     * The one and only answer to "this evidence piece is unknown", "it belongs to
     * another transaction" and "its binary is missing from the object store"
     * (Story 1.10, NFR-P9). Same no-identifier rule as
     * {@link #transactionNotFound()}.
     *
     * <p>The missing-binary case used to have its own message, which silently
     * confirmed that the row exists <em>and</em> belongs to this transaction — an
     * oracle at the piece level. The incident stays diagnosable server-side (the
     * storage key is logged where it is caught); it merely stops being published.
     */
    public static NotFoundException evidenceNotFound() {
        return new NotFoundException(ErrorCode.RESOURCE_NOT_FOUND, "Evidence not found");
    }

    /** Common carrier of the authoritative {@link ErrorCode}. */
    public abstract static class CodedException extends RuntimeException {
        private final ErrorCode code;

        protected CodedException(ErrorCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public ErrorCode getCode() {
            return code;
        }
    }

    public static class NotFoundException extends CodedException {
        public NotFoundException(String message) { this(ErrorCode.RESOURCE_NOT_FOUND, message); }
        public NotFoundException(ErrorCode code, String message) { super(code, message); }
    }

    public static class BadRequestException extends CodedException {
        public BadRequestException(String message) { this(ErrorCode.INVALID_REQUEST, message); }
        public BadRequestException(ErrorCode code, String message) { super(code, message); }
    }

    public static class ConflictException extends CodedException {
        public ConflictException(String message) { this(ErrorCode.CONFLICT, message); }
        public ConflictException(ErrorCode code, String message) { super(code, message); }
    }

    public static class ForbiddenException extends CodedException {
        public ForbiddenException(String message) { this(ErrorCode.FORBIDDEN, message); }
        public ForbiddenException(ErrorCode code, String message) { super(code, message); }
    }

    public static class UnauthorizedException extends CodedException {
        public UnauthorizedException(String message) { this(ErrorCode.AUTH_FAILED, message); }
        public UnauthorizedException(ErrorCode code, String message) { super(code, message); }
    }
}
