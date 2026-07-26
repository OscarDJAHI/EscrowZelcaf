package com.zlecaf.escrow.service.storage;

import com.zlecaf.escrow.security.crypto.SecretCipher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the storage round-trip against a real MinIO. The adapter is built
 * directly rather than through a Spring context: nothing here needs the broker
 * or the database, and mocking the round-trip would prove nothing.
 *
 * <p>Depuis la Story 1.7 la preuve porte aussi sur le chiffrement côté client :
 * l'objet relu par un {@link S3Client} nu — c'est-à-dire ce que voit qui copie le
 * volume — ne doit contenir aucun octet en clair.
 */
@Testcontainers
class MinioEvidenceStorageTest {

    private static final String BUCKET = "escrow-evidence";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");

    private static S3Client s3Client;
    private static MinioEvidenceStorage storage;

    /** Matériel de clé déterministe et manifestement de 32 octets (AES-256). */
    private static String keyMaterial(byte filler) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, filler);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static final String V1 = "v1:" + keyMaterial((byte) 0x41);
    private static final String V2 = "v2:" + keyMaterial((byte) 0x42);

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
        storage = new MinioEvidenceStorage(s3Client, BUCKET, new SecretCipher(V1, "v1"));
    }

    /** Lecture BRUTE de l'objet, sans passer par l'adaptateur : la vue de l'attaquant. */
    private static byte[] rawObject(String storageKey) throws IOException {
        try (InputStream in = s3Client.getObject(GetObjectRequest.builder()
                .bucket(BUCKET).key(storageKey).build())) {
            return in.readAllBytes();
        }
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

    @Test
    @DisplayName("delete removes a stored object: a subsequent load misses (404)")
    void deleteRemovesAStoredObject() {
        String storageKey = storage.store(42L, fixedBytes(128), "image/png");

        storage.delete(storageKey);

        assertThatThrownBy(() -> storage.load(storageKey))
                .isInstanceOf(EvidenceNotFoundException.class);
    }

    @Test
    @DisplayName("delete of an absent key is a no-op, never an error (rollback-cleanup contract)")
    void deleteOfAbsentKeyIsANoOp() {
        // The rollback-cleanup path must tolerate a key whose object was never
        // flushed; S3 DeleteObject is idempotent, so this must not throw.
        storage.delete("42/never-stored");
    }

    @Test
    @DisplayName("A non-miss infra failure (unreachable endpoint) is wrapped as a storage-neutral EvidenceStorageException")
    void infraFailureWrapsAsStorageException() {
        // Point the adapter at a refused endpoint so putObject fails with an SDK
        // client error (not a NoSuchKeyException): the adapter must translate it
        // to a storage-neutral EvidenceStorageException, never let the S3 type out.
        S3Client broken = S3Client.builder()
                .endpointOverride(URI.create("http://localhost:1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("x", "y")))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .build();
        MinioEvidenceStorage brokenStorage = new MinioEvidenceStorage(broken, BUCKET, new SecretCipher(V1, "v1"));

        assertThatThrownBy(() -> brokenStorage.store(1L, fixedBytes(16), "application/pdf"))
                .isInstanceOf(EvidenceStorageException.class);
    }

    @Test
    @DisplayName("Un objet démesuré est refusé AVANT d'être chargé en mémoire (le tas n'est pas une limite)")
    void oversizedObjectIsRefusedInsteadOfMaterialized() {
        // Tout ce qui atteint le bucket n'est pas passé par le contrôle de taille du
        // service : restauration de sauvegarde, outil d'admin parlant à S3 en direct.
        // Depuis la Story 1.7 la lecture matérialise l'objet (GCM n'authentifie qu'au
        // tag final) : sans plafond, un seul objet suffirait à emporter la JVM.
        byte[] oversized = new byte[10 * 1024 * 1024 + 8192];
        s3Client.putObject(PutObjectRequest.builder().bucket(BUCKET).key("42/oversized").build(),
                RequestBody.fromBytes(oversized));
        try {
            assertThatThrownBy(() -> storage.load("42/oversized"))
                    .isInstanceOf(EvidenceStorageException.class)
                    .hasStackTraceContaining("plafond de matérialisation");
        } finally {
            // 10 Mo laissés dans un seau partagé par toute la classe : les tests
            // suivants n'ont pas à travailler sur l'état de celui-ci.
            s3Client.deleteObject(DeleteObjectRequest.builder().bucket(BUCKET).key("42/oversized").build());
        }
    }

    @Test
    @DisplayName("L'objet au repos ne contient PAS le clair et porte le magic ESCX (NFR-P6)")
    void storedObjectIsEncryptedAtRest() throws IOException {
        byte[] confidential = "CONNAISSEMENT BL-2026-0042 — cargaison confidentielle".getBytes(StandardCharsets.UTF_8);

        String storageKey = storage.store(42L, confidential, "application/pdf");

        byte[] atRest = rawObject(storageKey);
        assertThat(Arrays.copyOf(atRest, 4)).isEqualTo("ESCX".getBytes(StandardCharsets.US_ASCII));
        assertThat(new String(atRest, StandardCharsets.ISO_8859_1)).doesNotContain("CONNAISSEMENT");
        assertThat(atRest).isNotEqualTo(confidential);
        try (InputStream in = storage.load(storageKey)) {
            assertThat(in.readAllBytes()).isEqualTo(confidential);
        }
    }

    @Test
    @DisplayName("Un objet legacy déposé en clair (avant la story) reste lisible tel quel")
    void legacyPlaintextObjectStaysReadable() throws IOException {
        // Dépôt DIRECT, sans passer par l'adaptateur : l'état exact d'un bucket
        // peuplé avant la Story 1.7. La rétention WORM interdit que cette lecture casse.
        byte[] legacy = fixedBytes(2048);
        String storageKey = "42/objet-legacy-en-clair";
        s3Client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(storageKey).build(),
                RequestBody.fromBytes(legacy));

        try (InputStream in = storage.load(storageKey)) {
            assertThat(in.readAllBytes()).isEqualTo(legacy);
        }
    }

    @Test
    @DisplayName("Un objet écrit sous v1 reste lisible après bascule sur v2 (pas de reprise objet nécessaire)")
    void objectWrittenUnderV1StaysReadableAfterRotation() throws IOException {
        byte[] content = fixedBytes(4096);
        String storageKey = storage.store(42L, content, "image/jpeg");

        // Trousseau élargi + clé active basculée : v1 reste au trousseau, c'est
        // précisément la condition de lisibilité rappelée par le runbook.
        MinioEvidenceStorage rotated = new MinioEvidenceStorage(s3Client, BUCKET,
                new SecretCipher(V1 + "," + V2, "v2"));

        try (InputStream in = rotated.load(storageKey)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        // ...et toute NOUVELLE écriture part sous v2, sans que l'ancienne devienne illisible.
        String afterRotation = rotated.store(42L, content, "image/jpeg");
        try (InputStream in = rotated.load(afterRotation)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("Un octet altéré dans l'objet stocké -> lecture refusée (jamais de preuve corrompue rendue)")
    void tamperedObjectIsRejectedOnLoad() throws IOException {
        String storageKey = storage.store(42L, fixedBytes(512), "application/pdf");
        byte[] atRest = rawObject(storageKey);
        atRest[atRest.length - 1] ^= 0x01;
        s3Client.putObject(PutObjectRequest.builder().bucket(BUCKET).key(storageKey).build(),
                RequestBody.fromBytes(atRest));

        // Le port ne laisse fuir aucun type crypto : l'échec sort en exception NEUTRE
        // de stockage (502), comme une panne S3 — un IllegalStateException nu
        // deviendrait un 500 sans le champ `code` dont le client a besoin (AD-10).
        // Le diagnostic actionnable reste dans la cause, journalisée.
        assertThatThrownBy(() -> storage.load(storageKey))
                .isInstanceOf(EvidenceStorageException.class)
                .cause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tag GCM");
    }
}
