package com.zlecaf.escrow.observability;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zlecaf.escrow.service.AuditService;
import com.zlecaf.escrow.service.scan.MalwareScanGateway;
import com.zlecaf.escrow.service.scan.ScanVerdict;
import com.zlecaf.escrow.service.storage.MinioEvidenceStorage;
import com.zlecaf.escrow.support.CapturingEmailVerificationSender;
import com.zlecaf.escrow.support.PostgresTestSupport;
import com.zlecaf.escrow.support.VerifiedAccounts;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LA TRACE DESCEND JUSQU'AU STOCKAGE OBJET (Story 11.4, AC2).
 *
 * <p><b>Le défaut que ce fichier ferme.</b> Le SDK AWS v2 n'est pas instrumenté par
 * Micrometer. Sans l'observation posée dans {@code MinioEvidenceStorage}, une trace de
 * versement de preuve s'arrête à la couche web : le poste le plus lent du parcours — l'écrit
 * réseau vers le stockage objet, premier suspect d'un incident sur corridor lent — reste
 * invisible, et l'AC2 (« du contrôleur au stockage objet ») n'est pas tenue.
 *
 * <p><b>Pourquoi un parcours HTTP COMPLET, et pas l'adaptateur seul.</b> Éprouver
 * {@code MinioEvidenceStorage} isolément prouverait que l'observation est créée, pas qu'elle
 * est BRANCHÉE dans la trace de la requête — c'est-à-dire pas ce que l'AC demande. Cette
 * story a déjà rencontré la distinction deux fois ({@code appShell.spec.ts} de la 2.7, le
 * câblage de {@code main.ts} en T3/T4) et elle a mordu les deux fois. Le test monte donc
 * l'application entière, un vrai MinIO, un vrai versement multipart, et compare
 * l'identifiant de trace vu DANS le stockage à celui vu dans l'écriture d'audit.
 *
 * <p><b>Le seul double est l'antivirus</b>, et il est nommé : {@code application.properties}
 * de test pointe ClamAV vers un hôte injoignable et l'analyse échoue FERMÉE (Story 1.8), si
 * bien qu'aucun versement n'atteindrait jamais le stockage. Le remplacer par un verdict
 * propre est ce qui rend le parcours parcourable ; tout le reste est réel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Import({CapturingEmailVerificationSender.Config.class,
         StorageTracingIntegrationTest.SansAntivirus.class,
         StorageTracingIntegrationTest.AvecTraceur.class})
class StorageTracingIntegrationTest {

    private static final String BUCKET = "escrow-evidence";

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.registerDatabase(registry, StorageTracingIntegrationTest.class);
        registry.add("escrow.storage.endpoint", MINIO::getS3URL);
        registry.add("escrow.storage.access-key", MINIO::getUserName);
        registry.add("escrow.storage.secret-key", MINIO::getPassword);
        registry.add("escrow.storage.bucket", () -> BUCKET);
    }

    /**
     * Le seau doit exister avant le premier versement — en production c'est le service
     * d'amorçage du compose qui s'en charge, jamais l'application.
     */
    @BeforeEach
    void creerLeSeau() {
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(MINIO.getS3URL()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO.getUserName(), MINIO.getPassword())))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .build()) {
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
            } catch (RuntimeException dejaLa) {
                // Le conteneur vit pour toute la classe : le seau survit d'un test à l'autre.
            }
        }
    }

    /**
     * Antivirus neutralisé, et lui seul. {@code @Primary} plutôt qu'une exclusion de bean :
     * le bean réel reste construit, donc rien de la configuration de production n'est
     * court-circuité au-delà du verdict lui-même.
     */
    @TestConfiguration
    static class SansAntivirus {
        @Bean
        @Primary
        MalwareScanGateway scannerPropre() {
            return content -> ScanVerdict.clean();
        }
    }

    /** Collecte le nom de chaque observation ET la trace dans laquelle elle s'est produite. */
    static final class TraceurDObservations implements ObservationHandler<Observation.Context> {
        private final Tracer tracer;
        final List<String[]> vues = new CopyOnWriteArrayList<>();

        TraceurDObservations(Tracer tracer) {
            this.tracer = tracer;
        }

        @Override
        public boolean supportsContext(Observation.Context context) {
            return true;
        }

        @Override
        public void onStop(Observation.Context context) {
            // Lu à l'ARRÊT, pendant que la portée de l'observation est encore active :
            // c'est le seul instant où « la trace courante » est celle de CETTE observation.
            var span = tracer.currentSpan();
            vues.add(new String[] {context.getName(), span == null ? null : span.context().traceId()});
        }
    }

    @TestConfiguration
    static class AvecTraceur {
        @Bean
        TraceurDObservations traceurDObservations(Tracer tracer) {
            return new TraceurDObservations(tracer);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CapturingEmailVerificationSender verificationCodes;

    @Autowired
    private TraceurDObservations traceur;

    private ListAppender<ILoggingEvent> auditCapture;
    private ch.qos.logback.classic.Logger auditLogger;

    @BeforeEach
    void collecterLAudit() {
        auditLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AuditService.class);
        auditCapture = new ListAppender<>();
        auditCapture.setContext(auditLogger.getLoggerContext());
        auditCapture.start();
        auditLogger.addAppender(auditCapture);
        auditLogger.setLevel(Level.INFO);
        traceur.vues.clear();
    }

    @AfterEach
    void relacher() {
        auditLogger.detachAppender(auditCapture);
        auditCapture.stop();
    }

    /** Verse une pièce sur une transaction neuve, par le vrai parcours HTTP. */
    private void verserUnePreuve() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8);
        String acheteur = VerifiedAccounts.createVerified(
                mvc, objectMapper, verificationCodes, "acheteur-" + run + "@corp.example");
        String vendeur = "vendeur-" + run + "@corp.example";
        VerifiedAccounts.createVerified(mvc, objectMapper, verificationCodes, vendeur);

        var creation = mvc.perform(post("/api/v1/escrow")
                        .header("Authorization", "Bearer " + acheteur)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellerEmail\":\"" + vendeur + "\",\"amount\":1000.00,"
                                + "\"currency\":\"USD\",\"description\":\"fixture tracing\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        long txId = objectMapper.readTree(creation.getResponse().getContentAsString()).get("id").asLong();

        // La fenêtre de versement n'est ouverte qu'à partir de FUNDS_LOCKED
        // (`EscrowState.allowsEvidenceMutation`, FR-8/AD-2) : une transaction fraîchement
        // créée naît INITIATED et refuse la preuve en 409. Le financement n'est donc pas un
        // détail de montage, c'est la règle métier qui ouvre le parcours chaud — et la
        // découvrir par un 409 valait mieux que de la contourner en écrivant l'état en base.
        mvc.perform(post("/api/v1/escrow/{id}/event", txId)
                        .header("Authorization", "Bearer " + acheteur)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"PAY_FUNDS\"}"))
                .andExpect(status().isOk());

        // PNG minimal : le validateur de contenu (Story 1.8) inspecte les octets de tête,
        // un fichier « texte renommé .png » serait refusé avant d'atteindre le stockage.
        byte[] png = new byte[] {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'
        };
        traceur.vues.clear();
        auditCapture.list.clear();

        mvc.perform(multipart("/api/v1/escrow/{id}/evidence", txId)
                        .file(new MockMultipartFile("files", "preuve.png", "image/png", png))
                        .header("Authorization", "Bearer " + acheteur))
                .andExpect(status().isCreated());
    }

    @Test
    void leFranchissementDuStockageObjetEstOBSERVE() throws Exception {
        verserUnePreuve();

        // POSITIVE. Sans l'observation posée dans l'adaptateur, cette liste ne contient
        // aucune entrée de stockage : le SDK AWS ne s'annonce pas tout seul.
        assertThat(traceur.vues)
                .as("le versement doit produire une observation du franchissement du stockage")
                .anyMatch(vue -> MinioEvidenceStorage.STORAGE_OBSERVATION.equals(vue[0]));
    }

    @Test
    void leStockageEtLEcritureDAuditPartagentLA_MEME_TRACE() throws Exception {
        verserUnePreuve();

        String traceDuStockage = traceur.vues.stream()
                .filter(vue -> MinioEvidenceStorage.STORAGE_OBSERVATION.equals(vue[0]))
                .map(vue -> vue[1])
                .findFirst()
                .orElse(null);

        assertThat(auditCapture.list)
                .as("le versement doit avoir écrit en audit — sinon la comparaison porterait sur du vide")
                .isNotEmpty();
        String traceDeLAudit = auditCapture.list.get(0).getMDCPropertyMap().get("traceId");

        // LE CŒUR DE L'AC2. Deux points du parcours aussi éloignés que possible — l'écriture
        // d'audit, en base, et l'écrit réseau vers le stockage objet — doivent porter le même
        // identifiant. C'est ce qui rend la trace CONSULTABLE d'un bout à l'autre, et c'est
        // aussi ce qui la relie aux logs, comme l'AC l'exige.
        //
        // Appariée : les deux sont non nuls. Sans cette moitié, deux `null` seraient « égaux »
        // et le test passerait sur une trace inexistante — le motif exact que cette story a
        // démasqué trois fois.
        assertThat(traceDuStockage).as("trace vue au stockage").isNotNull().isNotBlank();
        assertThat(traceDeLAudit).as("trace vue à l'audit").isNotNull().isNotBlank();
        assertThat(traceDuStockage).isEqualTo(traceDeLAudit);
    }
}
