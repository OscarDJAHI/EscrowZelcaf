package com.zlecaf.escrow.service.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * MinIO-backed {@link EvidenceStorage}. This is the only place in the
 * application that knows the storage is S3-compatible.
 */
@Component
public class MinioEvidenceStorage implements EvidenceStorage {

    private final S3Client s3Client;
    private final String bucket;

    public MinioEvidenceStorage(
            S3Client s3Client,
            @Value("${escrow.storage.bucket}") String bucket) {
        this.s3Client = s3Client;
        this.bucket = bucket;
    }

    @Override
    public String store(Long transactionId, byte[] content, String contentType) {
        // A null id would silently produce a "null/<uuid>" key: an object nothing
        // can ever attribute back to a transaction. Fail loudly instead.
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(content, "content");
        // Key is generated, never derived from client input: the transaction id
        // groups objects, the random UUID makes every store unique.
        String storageKey = transactionId + "/" + UUID.randomUUID();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .contentType(contentType)
                .build();
        s3Client.putObject(request, RequestBody.fromBytes(content));
        return storageKey;
    }

    @Override
    public InputStream load(String storageKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .build();
        try {
            return s3Client.getObject(request);
        } catch (NoSuchKeyException e) {
            // Translated here so callers never need an S3 type to handle a miss.
            throw new EvidenceNotFoundException(storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        // S3 DeleteObject is idempotent: removing an absent key returns 204, not
        // an error — exactly the no-op-on-missing contract the port requires for
        // the rollback-cleanup path.
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .build());
    }
}
