package com.zlecaf.escrow.service.storage;

/**
 * Raised when no binary exists under the requested storage key. Storage-neutral
 * by design: callers map this to a client-facing 404 without ever importing an
 * S3 type, which is the whole point of the {@link EvidenceStorage} port.
 */
public class EvidenceNotFoundException extends RuntimeException {

    public EvidenceNotFoundException(String storageKey, Throwable cause) {
        super("No evidence stored under key: " + storageKey, cause);
    }
}
