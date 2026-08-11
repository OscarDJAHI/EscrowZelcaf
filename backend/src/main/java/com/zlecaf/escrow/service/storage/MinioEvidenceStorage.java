package com.zlecaf.escrow.service.storage;

import com.zlecaf.escrow.security.crypto.SecretCipher;
import com.zlecaf.escrow.service.EvidenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import java.util.function.Supplier;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
     * la limite métier par pièce ({@link EvidenceService#MAX_FILE_SIZE}), plus la
     * marge d'enveloppe (magic, version, id de clé, IV et tag ~ quelques dizaines
     * d'octets). Un objet plus gros que cela n'a pas pu être déposé par
     * l'application : c'est une anomalie de stockage, traitée comme une
     * indisponibilité (502) plutôt qu'en épuisant le tas.
     *
     * <p>Le plafond est DÉRIVÉ, jamais recopié : relever la limite métier sans
     * relever celle-ci rendrait indéfiniment intéléchargeables les pièces déposées
     * entre les deux valeurs — un échec asymétrique et tardif.
     */
    private static final long MAX_OBJECT_BYTES = EvidenceService.MAX_FILE_SIZE + 4_096L;

    /**
     * Nom d'observation du franchissement du stockage objet (Story 11.4, AC2).
     *
     * <p>STABLE et sans donnée variable : la clé de stockage, l'identifiant de transaction
     * et le nom du seau n'y entrent PAS. Un nom construit à l'exécution ferait exploser la
     * cardinalité des séries — chaque objet créerait sa propre métrique, et le système de
     * métriques tomberait avant de rendre le moindre service. Ce qui varie va dans un tag
     * à faible cardinalité, et il n'y en a qu'un : l'opération.
     */
    public static final String STORAGE_OBSERVATION = "escrow.evidence.storage";

    private final S3Client s3Client;
    private final String bucket;
    private final SecretCipher cipher;
    private final ObservationRegistry observations;

    public MinioEvidenceStorage(
            S3Client s3Client,
            @Value("${escrow.storage.bucket}") String bucket,
            SecretCipher cipher,
            ObservationRegistry observations) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.cipher = cipher;
        this.observations = observations;
    }

    /**
     * Exécute l'appel réseau au stockage objet SOUS une observation.
     *
     * <p><b>Pourquoi cette instrumentation existe.</b> Le SDK AWS v2 n'est pas instrumenté
     * par Micrometer : sans ce passage, une trace de requête s'arrête à la couche web et le
     * temps passé dans le stockage objet — le poste le plus lent du versement de preuve, et
     * le premier suspect d'un incident sur corridor lent — reste invisible. L'AC2 demande
     * une trace « du contrôleur au stockage objet » ; voici le second bout.
     *
     * <p><b>Ce que l'observation englobe, et ce qu'elle exclut.</b> L'appel S3, et lui seul.
     * Le chiffrement d'enveloppe (`SecretCipher`) reste dehors : c'est du calcul local, et
     * l'inclure ferait passer un ralentissement de CPU pour une lenteur réseau — exactement
     * le contresens qu'une trace est censée éviter.
     */
    private <T> T observed(String operation, Supplier<T> call) {
        return Observation.createNotStarted(STORAGE_OBSERVATION, observations)
                .lowCardinalityKeyValue("operation", operation)
                .observe(call);
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
            observed("put", () -> s3Client.putObject(request, RequestBody.fromBytes(sealed)));
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
            // La taille DÉCLARÉE n'est qu'un raccourci : un serveur qui n'annonce pas
            // Content-Length (proxy, transfert chunked, implémentation S3 tierce) la
            // rendrait muette, et le plafond redeviendrait le tas de la JVM. C'est donc
            // la taille EFFECTIVEMENT lue qui tranche — on ne lit jamais plus d'un octet
            // au-delà du plafond, sans dépendre de ce que le serveur veut bien déclarer.
            stored = object.readNBytes(Math.toIntExact(MAX_OBJECT_BYTES) + 1);
            if (stored.length > MAX_OBJECT_BYTES) {
                throw new EvidenceStorageException(storageKey, new IllegalStateException(
                        "Objet au-delà du plafond de matérialisation (" + MAX_OBJECT_BYTES
                                + ") : refus de le charger en mémoire."));
            }
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
