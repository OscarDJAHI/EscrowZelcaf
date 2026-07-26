package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.Role;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.scan.MalwareScanner;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito proof of the download stream lifecycle (report C). The writable
 * download transaction commits in the Spring proxy AFTER {@code download}
 * returns, still holding the open storage stream; a commit failure there cannot
 * be reached by any in-method {@code try/catch}. {@link EvidenceService#download}
 * therefore registers a {@link TransactionSynchronization} whose
 * {@code afterCompletion} closes the stream on any non-committed outcome and
 * leaves it open on a successful commit (the web layer then owns/closes it).
 *
 * <p>No database or Spring context: an active synchronization scope is faked with
 * {@link TransactionSynchronizationManager#initSynchronization()}, the registered
 * hook is retrieved from {@link TransactionSynchronizationManager#getSynchronizations()}
 * and its {@code afterCompletion(status)} is invoked directly.
 */
class EvidenceDownloadStreamLifecycleTest {

    private final EscrowTransactionRepository transactions = mock(EscrowTransactionRepository.class);
    private final EvidenceFileRepository evidenceFiles = mock(EvidenceFileRepository.class);
    private final TransactionAccess access = mock(TransactionAccess.class);
    private final EvidenceContentValidator validator = mock(EvidenceContentValidator.class);
    private final EvidenceStorage storage = mock(EvidenceStorage.class);
    // Story 1.8 : dépendance obligatoire du service. Le téléchargement ne l'appelle
    // jamais (le scan est à l'INGESTION), mais le constructeur l'exige — et c'est
    // exactement la garantie voulue : on ne peut pas construire un service qui
    // ingérerait sans scanner.
    private final MalwareScanner scanner = mock(MalwareScanner.class);
    private final AuditService auditService = mock(AuditService.class);

    private final EvidenceService service = new EvidenceService(
            transactions, evidenceFiles, access, validator, storage, scanner, auditService);

    private final AuthPrincipal actor = new AuthPrincipal(7L, "party@example.com", Role.BUYER);

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Stream that records whether it was closed by the completion hook. */
    private static final class CloseTrackingInputStream extends InputStream {
        boolean closed;

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            this.closed = true;
        }
    }

    /** Wires the collaborators so {@code download} reaches stream-open + audit, and returns the open stream. */
    private CloseTrackingInputStream arrangeDownload() {
        EscrowTransaction tx = new EscrowTransaction();
        tx.setState(EscrowState.FUNDS_LOCKED);
        when(transactions.findById(anyLong())).thenReturn(Optional.of(tx));
        when(access.resolveRole(any(), any())).thenReturn(ParticipantRole.BUYER);

        EvidenceFile evidence = new EvidenceFile();
        evidence.setId(99L);
        evidence.setTransactionId(42L);
        evidence.setOriginalFilename("receipt.pdf");
        evidence.setMimeType("application/pdf");
        evidence.setSizeBytes(3L);
        evidence.setStorageKey("42/abc");
        when(evidenceFiles.findByIdAndTransactionId(anyLong(), anyLong())).thenReturn(Optional.of(evidence));

        CloseTrackingInputStream stream = new CloseTrackingInputStream();
        when(storage.load("42/abc")).thenReturn(stream);
        // recordEvidenceDownloaded is a mock: succeeds (void, no-op).
        return stream;
    }

    private TransactionSynchronization registeredHook() {
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        assertThat(syncs).isNotEmpty();
        return syncs.get(0);
    }

    @Test
    @DisplayName("the registered hook closes the open stream when the transaction does NOT commit (rolled back)")
    void hookClosesStreamOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        CloseTrackingInputStream stream = arrangeDownload();

        EvidenceDownload d = service.download(actor, 42L, 99L);
        assertThat(d.content()).isSameAs(stream);

        TransactionSynchronization hook = registeredHook();
        assertThat(stream.closed).isFalse(); // not closed until completion

        hook.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(stream.closed).isTrue();
    }

    @Test
    @DisplayName("the registered hook leaves the stream open on a successful commit (the web layer owns it)")
    void hookLeavesStreamOpenOnCommit() {
        TransactionSynchronizationManager.initSynchronization();
        CloseTrackingInputStream stream = arrangeDownload();

        service.download(actor, 42L, 99L);

        TransactionSynchronization hook = registeredHook();
        hook.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);

        assertThat(stream.closed).isFalse();
    }
}
