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
 */
public final class ApiExceptions {

    private ApiExceptions() {}

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
