package com.zlecaf.escrow.service.storage;

import java.io.InputStream;

/**
 * Port isolating evidence binaries behind an opaque storage key. No storage
 * technology type ever crosses this boundary — callers only ever see
 * {@code byte[]}, {@code String} and {@code InputStream}.
 */
public interface EvidenceStorage {

    /**
     * Stores a binary and returns the key under which it can be read back.
     * <p>
     * The key is <strong>generated</strong> by the implementation, never derived
     * from any client-supplied name: this port deliberately accepts no filename,
     * so a hostile value such as {@code ../../etc/passwd} cannot reach the
     * storage layout. Callers must treat the returned value as opaque and
     * persist it verbatim; its internal shape is an implementation detail.
     *
     * @param transactionId the transaction this evidence belongs to
     * @param content       the raw bytes to store
     * @param contentType   the MIME type recorded alongside the object
     * @return the opaque storage key
     */
    String store(Long transactionId, byte[] content, String contentType);

    /**
     * Reads back the binary stored under {@code storageKey}. The caller owns the
     * returned stream and must close it.
     *
     * @param storageKey a key previously returned by {@link #store}
     * @return the object's content as a stream
     * @throws EvidenceNotFoundException if no binary exists under that key.
     *         Deliberately storage-neutral: mapping it to a client-facing 404 is
     *         the calling layer's job, and must not require an S3 type to do it.
     */
    InputStream load(String storageKey);
}
