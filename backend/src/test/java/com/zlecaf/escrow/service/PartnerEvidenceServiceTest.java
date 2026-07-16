package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.PartnerKeyNonce;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration & anti-replay proof with every collaborator mocked: no database,
 * no HTTP. Pins the auth+ingest+nonce sequence — the happy path saves the nonce
 * and deposits under the key's company, a probed-seen nonce is a 401 with no
 * deposit, and a lost INSERT race (DataIntegrityViolationException) is a 401.
 */
class PartnerEvidenceServiceTest {

    private static final String KEY_ID = "partner-key-1";
    private static final String SIGNATURE = "deadbeef";
    private static final String TIMESTAMP = "1700000000";
    private static final String NONCE = "nonce-abc";
    private static final Long TX_ID = 42L;
    private static final Long COMPANY_ID = 7L;

    private PartnerHmacKeyRepository keyRepository;
    private PartnerKeyNonceRepository nonceRepository;
    private PartnerSignatureVerifier signatureVerifier;
    private EvidenceService evidenceService;
    private PartnerEvidenceService service;

    private final List<MultipartFile> files = List.of(
            new MockMultipartFile("files", "proof.pdf", "application/pdf", "bytes".getBytes()));

    @BeforeEach
    void setUp() {
        keyRepository = mock(PartnerHmacKeyRepository.class);
        nonceRepository = mock(PartnerKeyNonceRepository.class);
        signatureVerifier = mock(PartnerSignatureVerifier.class);
        evidenceService = mock(EvidenceService.class);
        service = new PartnerEvidenceService(keyRepository, nonceRepository, signatureVerifier, evidenceService);
    }

    private PartnerHmacKey activeKey() {
        PartnerHmacKey k = new PartnerHmacKey();
        k.setKeyId(KEY_ID);
        k.setCompanyId(COMPANY_ID);
        k.setSecretKey("partner-shared-secret-32bytes-min!!!");
        k.setActive(true);
        return k;
    }

    @Test
    @DisplayName("Happy path: deposits under the key's company and consumes the nonce")
    void happyPathDepositsAndSavesNonce() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        when(nonceRepository.existsByKeyIdAndNonce(KEY_ID, NONCE)).thenReturn(false);
        List<EvidenceFile> ingested = List.of(new EvidenceFile());
        when(evidenceService.depositAsPartner(eq(COMPANY_ID), eq(TX_ID), anyList(), any(), any()))
                .thenReturn(ingested);

        List<EvidenceFile> result =
                service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, "comment", null);

        assertThat(result).isSameAs(ingested);
        verify(signatureVerifier).verify(any(), eq(SIGNATURE), eq(TIMESTAMP), eq(NONCE), eq(TX_ID), anyList(), any(), any());
        verify(evidenceService).depositAsPartner(eq(COMPANY_ID), eq(TX_ID), anyList(), eq("comment"), any());
        verify(nonceRepository).save(any(PartnerKeyNonce.class));
    }

    @Test
    @DisplayName("Unknown or inactive key -> 401 before any signature check or deposit")
    void unknownKeyIsUnauthorized() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, null, null))
                .isInstanceOf(UnauthorizedException.class);

        verify(evidenceService, never()).depositAsPartner(anyLong(), anyLong(), anyList(), any(), any());
        verify(nonceRepository, never()).save(any());
    }

    @Test
    @DisplayName("A nonce already seen for this key -> 401 and no deposit")
    void replayedNonceIsUnauthorized() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        when(nonceRepository.existsByKeyIdAndNonce(KEY_ID, NONCE)).thenReturn(true);

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, null, null))
                .isInstanceOf(UnauthorizedException.class);

        verify(evidenceService, never()).depositAsPartner(anyLong(), anyLong(), anyList(), any(), any());
        verify(nonceRepository, never()).save(any());
    }

    @Test
    @DisplayName("An over-cap batch is rejected (400) BEFORE the signature is verified (no pre-auth hashing)")
    void tooManyFilesRejectedBeforeSignatureCheck() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        // 21 files: one over MAX_FILES_PER_DEPOSIT (20).
        List<MultipartFile> overCap = java.util.stream.IntStream.range(0, 21)
                .mapToObj(i -> (MultipartFile) new MockMultipartFile("files", "f" + i + ".pdf", "application/pdf", "b".getBytes()))
                .toList();

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, overCap, null, null))
                .isInstanceOf(BadRequestException.class);

        // The verifier (which reads + SHA-256-hashes every file) must never run for an over-cap batch.
        verify(signatureVerifier, never()).verify(any(), any(), any(), any(), any(), anyList(), any(), any());
        verify(evidenceService, never()).depositAsPartner(anyLong(), anyLong(), anyList(), any(), any());
        verify(nonceRepository, never()).save(any());
    }

    @Test
    @DisplayName("A lost INSERT race on the nonce unique constraint -> 401 replay")
    void nonceInsertRaceIsUnauthorized() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        when(nonceRepository.existsByKeyIdAndNonce(KEY_ID, NONCE)).thenReturn(false);
        when(evidenceService.depositAsPartner(eq(COMPANY_ID), eq(TX_ID), anyList(), any(), any()))
                .thenReturn(List.of(new EvidenceFile()));
        // The DIVE carries a Hibernate ConstraintViolationException naming the nonce
        // unique constraint — the ONLY integrity violation interpreted as a replay.
        when(nonceRepository.save(any(PartnerKeyNonce.class)))
                .thenThrow(nonceConstraintViolation("uq_partner_key_nonces_key_nonce"));

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, null, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A DIVE naming the nonce unique constraint (any casing) is a 401 replay")
    void nonceConstraintCaseInsensitiveIsUnauthorized() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        when(nonceRepository.existsByKeyIdAndNonce(KEY_ID, NONCE)).thenReturn(false);
        when(evidenceService.depositAsPartner(eq(COMPANY_ID), eq(TX_ID), anyList(), any(), any()))
                .thenReturn(List.of(new EvidenceFile()));
        when(nonceRepository.save(any(PartnerKeyNonce.class)))
                .thenThrow(nonceConstraintViolation("UQ_PARTNER_KEY_NONCES_KEY_NONCE"));

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, null, null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A DIVE from a DIFFERENT constraint propagates (not masked as a 401)")
    void otherIntegrityViolationPropagates() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        when(nonceRepository.existsByKeyIdAndNonce(KEY_ID, NONCE)).thenReturn(false);
        when(evidenceService.depositAsPartner(eq(COMPANY_ID), eq(TX_ID), anyList(), any(), any()))
                .thenReturn(List.of(new EvidenceFile()));
        // A violation of some OTHER constraint must never be swallowed as an auth
        // failure — it must surface (→ 500), not a misleading 401.
        when(nonceRepository.save(any(PartnerKeyNonce.class)))
                .thenThrow(nonceConstraintViolation("fk_something_else"));

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A DIVE with no ConstraintViolationException in its chain propagates (not a 401)")
    void integrityViolationWithoutConstraintNamePropagates() {
        when(keyRepository.findByKeyIdAndActiveTrue(KEY_ID)).thenReturn(Optional.of(activeKey()));
        when(nonceRepository.existsByKeyIdAndNonce(KEY_ID, NONCE)).thenReturn(false);
        when(evidenceService.depositAsPartner(eq(COMPANY_ID), eq(TX_ID), anyList(), any(), any()))
                .thenReturn(List.of(new EvidenceFile()));
        when(nonceRepository.save(any(PartnerKeyNonce.class)))
                .thenThrow(new DataIntegrityViolationException("opaque, no Hibernate cause"));

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, NONCE, TX_ID, files, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .isNotInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("An over-long nonce (>255) is a 400 BEFORE any signature check or ingest")
    void overlongNonceRejectedBeforeSignatureCheck() {
        String longNonce = "n".repeat(256);

        assertThatThrownBy(() -> service.deposit(KEY_ID, SIGNATURE, TIMESTAMP, longNonce, TX_ID, files, null, null))
                .isInstanceOf(BadRequestException.class);

        // The guard runs first: no key lookup, no signature hashing, no ingest, no nonce save.
        verify(keyRepository, never()).findByKeyIdAndActiveTrue(anyString());
        verify(signatureVerifier, never()).verify(any(), any(), any(), any(), any(), anyList(), any(), any());
        verify(evidenceService, never()).depositAsPartner(anyLong(), anyLong(), anyList(), any(), any());
        verify(nonceRepository, never()).save(any());
    }

    /**
     * A {@link DataIntegrityViolationException} wrapping a Hibernate
     * {@link ConstraintViolationException} that names {@code constraintName} — the
     * shape Spring produces on a real Postgres unique-constraint conflict.
     */
    private static DataIntegrityViolationException nonceConstraintViolation(String constraintName) {
        return new DataIntegrityViolationException("insert failed",
                new ConstraintViolationException("could not execute statement", new SQLException(), constraintName));
    }
}
