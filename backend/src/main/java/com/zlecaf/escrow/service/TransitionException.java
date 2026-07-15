package com.zlecaf.escrow.service;

/**
 * Base type for state-machine rejections. Carries a machine-readable reason so
 * the web layer can map to the right HTTP status.
 */
public class TransitionException extends RuntimeException {

    public enum Reason {
        /** The event is not defined for the current state. */
        ILLEGAL_TRANSITION,
        /** The event is defined but the acting role is not permitted. */
        UNAUTHORIZED
    }

    private final Reason reason;

    public TransitionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
