package com.zlecaf.escrow.service.storage;

/**
 * Raised when the object store fails for any reason other than a missing object
 * (an outage, a timeout, a protocol error). Storage-neutral by design: callers
 * map this to a client-facing 502 without ever importing an S3 type, which is
 * the whole point of the {@link EvidenceStorage} port. A genuine "object absent"
 * stays a {@link EvidenceNotFoundException} (404) — the two are never conflated.
 */
public class EvidenceStorageException extends RuntimeException {

    public EvidenceStorageException(String storageKey, Throwable cause) {
        super("Evidence storage unavailable for key: " + storageKey, cause);
    }
}
