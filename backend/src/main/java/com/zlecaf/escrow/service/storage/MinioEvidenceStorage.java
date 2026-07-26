package com.zlecaf.escrow.service.storage;

import com.zlecaf.escrow.security.crypto.SecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * MinIO-backed {@link EvidenceStorage}. This is the only place in the
 * application that knows the storage is S3-compatible.
 *
 * <p>Depuis la Story 1.7, c'est aussi le seul endroit qui chiffre les binaires de
 * preuves (AD-29 : « côté stockage objet, derrière {@code EvidenceStorage} »). Le
 * chiffrement est fait CÔTÉ CLIENT et non par le serveur d'objets (SSE-S3/KES) :
 * le backend objet définitif n'est pas tranché, et une configuration serveur ne
 * survivrait pas à la bascule — ni ne protégerait une copie de volume. Le port
 * garde sa signature ({@code byte[]} / {@link InputStream}) : rien au-dessus ne
 * sait que les objets sont chiffrés, pas plus qu'ils sont dans S3.
 */
@Component
public class MinioEvidenceStorage implements EvidenceStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioEvidenceStorage.class);

    /**
     * Plafond de ce que {@link #load(String)} accepte de charger en mémoire :
     * la limite métier de 10 Mo par pièce ({@code EvidenceService.MAX_FILE_SIZE},
     * d'un autre paquet), plus la marge d'enveloppe (magic, version, id de clé,
     * IV et tag ~ quelques dizaines d'octets). Un objet plus gros que cela n'a pas
     * pu être déposé par l'application : c'est une anomalie de stockage, traitée
     * comme une indisponibilité (502) plutôt qu'en épuisant le tas.
     */
    private static final long MAX_OBJECT_BYTES = 10_485_760L + 4_096L;

    private final S3Client s3Client;
    private final String bucket;
    private final SecretCipher cipher;

    public MinioEvidenceStorage(
            S3Client s3Client,
            @Value("${escrow.storage.bucket}") String bucket,
            SecretCipher cipher) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.cipher = cipher;
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
        // AAD = la clé de stockage : une enveloppe recopiée sous une AUTRE clé ne se
        // déchiffre pas. Gratuit ici, puisque la clé est unique par objet (AD-12) et
        // déjà connue avant l'écriture.
        byte[] sealed;
        try {
            sealed = cipher.encryptBytes(content, storageKey);
        } catch (RuntimeException e) {
            // Un trousseau cassé est une panne d'infrastructure du point de vue de
            // l'appelant : 502 comme une panne S3, jamais un 500 nu qui échapperait
            // à la classification transitoire/permanent du client (AD-10).
            throw new EvidenceStorageException(storageKey, e);
        }
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                // contentType inchangé, volontairement : c'est le type du CONTENU
                // déchiffré, celui que le téléchargement doit servir. Le déclarer
                // « application/octet-stream » ferait perdre l'information sans rien
                // protéger — S3 ne lit pas le corps.
                .contentType(contentType)
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(sealed));
        } catch (SdkException e) {
            // Infra failure (outage/timeout/protocol) — surface a storage-neutral
            // exception so the service/web layer never sees an S3 type (502).
            throw new EvidenceStorageException(storageKey, e);
        }
        return storageKey;
    }

    @Override
    public InputStream load(String storageKey) {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(storageKey)
                .build();
        // Objet lu INTÉGRALEMENT en mémoire : GCM n'authentifie qu'une fois le tag
        // final vérifié, donc un flux déchiffré à la volée rendrait du clair non
        // authentifié — exactement ce que « jamais de clair partiel » interdit.
        //
        // La borne est donc posée ICI, et non déduite de la limite d'ingestion : tout
        // ce qui atteint le bucket n'est pas passé par le contrôle de taille du
        // service (restauration de sauvegarde, outil d'admin parlant à S3 en direct,
        // futur canal d'ingestion). Sans ce plafond, un seul objet surdimensionné
        // suffirait à emporter la JVM entière, pas seulement sa requête.
        byte[] stored;
        try (ResponseInputStream<GetObjectResponse> object = s3Client.getObject(request)) {
            Long declaredLength = object.response().contentLength();
            if (declaredLength != null && declaredLength > MAX_OBJECT_BYTES) {
                throw new EvidenceStorageException(storageKey, new IllegalStateException(
                        "Objet de " + declaredLength + " octets au-delà du plafond de matérialisation ("
                                + MAX_OBJECT_BYTES + ") : refus de le charger en mémoire."));
            }
            stored = object.readAllBytes();
        } catch (NoSuchKeyException e) {
            // Translated here so callers never need an S3 type to handle a miss.
            throw new EvidenceNotFoundException(storageKey, e);
        } catch (SdkException | IOException e) {
            // Any non-miss infra failure maps to a storage-neutral 502 exception,
            // distinct from the 404 "object absent" above.
            throw new EvidenceStorageException(storageKey, e);
        }
        if (!cipher.isEnvelope(stored)) {
            // Objet déposé avant la Story 1.7 : le rendre tel quel. La rétention WORM
            // (AD-25, >= 5 ans) interdit qu'un déploiement rende une preuve illisible ;
            // il n'y a pas de reprise en masse, ces objets restent lisibles à vie.
            log.warn("Objet de preuve lu EN CLAIR (déposé avant le chiffrement au repos) : {}", storageKey);
            return new ByteArrayInputStream(stored);
        }
        try {
            return new ByteArrayInputStream(cipher.decryptBytes(stored, storageKey));
        } catch (RuntimeException e) {
            // Objet altéré, ou clé retirée du trousseau : côté appelant c'est une
            // indisponibilité du stockage (502), pas une preuve absente (404) ni une
            // erreur interne opaque (500). Le message actionnable — quelle clé
            // manque — reste dans la cause, journalisée, jamais renvoyée au client.
            throw new EvidenceStorageException(storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) {
        // S3 DeleteObject is idempotent: removing an absent key returns 204, not
        // an error — exactly the no-op-on-missing contract the port requires for
        // the rollback-cleanup path.
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(storageKey)
                    .build());
        } catch (SdkException e) {
            throw new EvidenceStorageException(storageKey, e);
        }
    }
}
