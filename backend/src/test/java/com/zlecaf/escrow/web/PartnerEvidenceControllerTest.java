package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.service.PartnerEvidenceService;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Web-layer proof of {@code POST /api/v1/partner/escrow/{id}/evidence} without a
 * database or security context: the controller is stood up with a mocked
 * {@link PartnerEvidenceService} and the {@link GlobalExceptionHandler} advice.
 * Covers what only the HTTP surface can prove — multipart binding, the four
 * required {@code X-Escrow-*} headers, EvidenceDto serialization, and the
 * service-exception → status mapping (201 / 400 / 401 / 404 / 409) in the envelope.
 */
class PartnerEvidenceControllerTest {

    private PartnerEvidenceService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PartnerEvidenceService.class);
        mvc = standaloneSetup(new PartnerEvidenceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("201: a fully-signed request returns the CARRIER_PARTNER EvidenceDto list")
    void validRequestReturns201WithBody() throws Exception {
        when(service.deposit(anyString(), anyString(), anyString(), anyString(), eq(42L), anyList(), any(), any()))
                .thenReturn(List.of(partnerEvidence()));

        mvc.perform(signed(multipart("/api/v1/partner/escrow/42/evidence").file(pdf())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(99))
                .andExpect(jsonPath("$[0].uploaderType").value("CARRIER_PARTNER"))
                .andExpect(jsonPath("$[0].uploadedByUserId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("400: a missing X-Escrow-Signature header is a clean bad request (never a 500)")
    void missingHeaderIs400() throws Exception {
        // All headers except the signature: the required-header binding rejects it.
        mvc.perform(multipart("/api/v1/partner/escrow/42/evidence").file(pdf())
                        .header("X-Escrow-Key-Id", "k")
                        .header("X-Escrow-Timestamp", "1700000000")
                        .header("X-Escrow-Nonce", "n"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("401: an UnauthorizedException (bad signature/key/nonce/timestamp) maps to 401")
    void unauthorizedMapsTo401() throws Exception {
        when(service.deposit(anyString(), anyString(), anyString(), anyString(), anyLong(), anyList(), any(), any()))
                .thenThrow(new UnauthorizedException("Invalid signature"));

        mvc.perform(signed(multipart("/api/v1/partner/escrow/42/evidence").file(pdf())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("404: a non-party company is served the SAME opaque 404 as an unknown transaction (Story 1.10)")
    void nonPartyCompanyMapsTo404() throws Exception {
        // L'anti-enumeration ne s'arrete pas au canal humain : une signature HMAC
        // valide autorise a agir sur SES transactions, jamais a cartographier l'espace
        // des identifiants de la plateforme. Meme statut, meme code, meme message que
        // le test « transaction inconnue » juste en dessous.
        when(service.deposit(anyString(), anyString(), anyString(), anyString(), anyLong(), anyList(), any(), any()))
                .thenThrow(ApiExceptions.transactionNotFound());

        mvc.perform(signed(multipart("/api/v1/partner/escrow/42/evidence").file(pdf())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Transaction not found"));
    }

    @Test
    @DisplayName("404: a NotFoundException (unknown transaction) maps to 404")
    void notFoundMapsTo404() throws Exception {
        when(service.deposit(anyString(), anyString(), anyString(), anyString(), anyLong(), anyList(), any(), any()))
                .thenThrow(new NotFoundException("Transaction 42 not found"));

        mvc.perform(signed(multipart("/api/v1/partner/escrow/42/evidence").file(pdf())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("409: a ConflictException (deposit window closed / terminal state) maps to 409")
    void conflictMapsTo409() throws Exception {
        when(service.deposit(anyString(), anyString(), anyString(), anyString(), anyLong(), anyList(), any(), any()))
                .thenThrow(new ConflictException("Deposits are locked for this transaction state"));

        mvc.perform(signed(multipart("/api/v1/partner/escrow/42/evidence").file(pdf())))
                .andExpect(status().isConflict());
    }

    // --- helpers ---

    private static MockMultipartFile pdf() {
        return new MockMultipartFile("files", "receipt.pdf", "application/pdf", "pdf-bytes".getBytes());
    }

    /** Attaches the four required X-Escrow-* headers to a multipart builder. */
    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
            org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder builder) {
        return builder
                .header("X-Escrow-Key-Id", "partner-key-1")
                .header("X-Escrow-Signature", "deadbeef")
                .header("X-Escrow-Timestamp", "1700000000")
                .header("X-Escrow-Nonce", "nonce-abc");
    }

    private static EvidenceFile partnerEvidence() {
        EvidenceFile e = new EvidenceFile();
        e.setId(99L);
        e.setTransactionId(42L);
        e.setUploadedByUserId(null);
        e.setUploaderType(UploaderType.CARRIER_PARTNER);
        e.setPartnerCompanyId(7L);
        e.setOriginalFilename("receipt.pdf");
        e.setMimeType("application/pdf");
        e.setSizeBytes(1234L);
        e.setComment("delivered");
        e.setStatus(EvidenceStatus.ACTIVE);
        e.setCreatedAt(Instant.parse("2026-07-16T10:00:00Z"));
        return e;
    }
}
