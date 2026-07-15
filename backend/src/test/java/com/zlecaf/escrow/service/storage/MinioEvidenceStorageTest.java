package com.zlecaf.escrow.service.storage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the storage round-trip against a real MinIO. The adapter is built
 * directly rather than through a Spring context: nothing here needs the broker
 * or the database, and mocking the round-trip would prove nothing.
 */
@Testcontainers
class MinioEvidenceStorageTest {

    private static final String BUCKET = "escrow-evidence";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");

    private static S3Client s3Client;
    private static MinioEvidenceStorage storage;

    @BeforeAll
    static void setUp() {
        s3Client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .build();
        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        storage = new MinioEvidenceStorage(s3Client, BUCKET);
    }

    /** Deterministic by design: a fixed seed keeps failures reproducible, and
     *  {@link #keysAreUniquePerStore} relies on two stores yielding equal bytes. */
    private static byte[] fixedBytes(int length) {
        byte[] bytes = new byte[length];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    @Test
    @DisplayName("Round-trip: the bytes read back are identical to those stored")
    void roundTripIsByteForByteIdentical() throws IOException {
        // Binary, non-textual content: anything corrupting encoding would show up.
        byte[] original = fixedBytes(256 * 1024);

        String storageKey = storage.store(42L, original, "image/jpeg");

        try (InputStream in = storage.load(storageKey)) {
            assertThat(in.readAllBytes()).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("The generated key is {transactionId}/{uuid}")
    void keyFollowsTransactionUuidFormat() {
        String storageKey = storage.store(42L, fixedBytes(16), "application/pdf");

        assertThat(storageKey).matches("^42/[0-9a-f-]{36}$");
    }

    @Test
    @DisplayName("Two stores of identical content yield distinct, independently readable keys")
    void keysAreUniquePerStore() throws IOException {
        byte[] content = fixedBytes(1024);

        String firstKey = storage.store(7L, content, "application/pdf");
        String secondKey = storage.store(7L, content, "application/pdf");

        assertThat(firstKey).isNotEqualTo(secondKey);
        try (InputStream first = storage.load(firstKey);
             InputStream second = storage.load(secondKey)) {
            assertThat(first.readAllBytes()).isEqualTo(content);
            assertThat(second.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("The contentType is recorded on the stored object")
    void contentTypeIsPersistedOnTheObject() {
        String storageKey = storage.store(42L, fixedBytes(64), "application/pdf");

        String storedContentType = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET)
                .key(storageKey)
                .build()).contentType();

        assertThat(storedContentType).isEqualTo("application/pdf");
    }

    @Test
    @DisplayName("An unknown key raises a storage-neutral EvidenceNotFoundException (mapped to 404 by the caller)")
    void unknownKeyRaisesStorageNeutralException() {
        assertThatThrownBy(() -> storage.load("42/inexistant"))
                .isInstanceOf(EvidenceNotFoundException.class);
    }

    @Test
    @DisplayName("A null transactionId is rejected rather than yielding a \"null/<uuid>\" key")
    void nullTransactionIdIsRejected() {
        assertThatThrownBy(() -> storage.store(null, fixedBytes(16), "application/pdf"))
                .isInstanceOf(NullPointerException.class);
    }
}
