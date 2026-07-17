package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.ErrorCode;

import java.util.Objects;

/**
 * Base type for state-machine rejections. Carries the authoritative
 * {@link ErrorCode} so the web layer can both map to the right HTTP status and
 * serialise a stable machine token to the client.
 *
 * <p>This generalises the former {@code Reason {ILLEGAL_TRANSITION, UNAUTHORIZED}}:
 * that enum was the only machine-readable rejection datum in the codebase, but it
 * was consumed for status selection alone and never reached the client.
 */
public class TransitionException extends RuntimeException {

    private final ErrorCode code;

    public TransitionException(ErrorCode code, String message) {
        super(message);
        // A null code would reach Map.of in the envelope builder and turn a clean
        // business rejection into a 500. Fail here, at the throw site, instead.
        this.code = Objects.requireNonNull(code, "code");
    }

    public ErrorCode getCode() {
        return code;
    }
}
