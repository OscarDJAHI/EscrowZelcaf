package com.zlecaf.escrow.web;

import com.zlecaf.escrow.service.PartnerEvidenceService;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Machine-partner deposit surface: a logistics partner (no interactive account)
 * pushes signed evidence here. The route is not JWT-protected; authentication is
 * carried entirely by the four {@code X-Escrow-*} headers, verified in
 * {@link PartnerEvidenceService}. This controller only adapts HTTP to the
 * service — all authority (signature, timestamp, nonce, membership, ingestion)
 * lives behind it.
 */
@RestController
@RequestMapping("/api/v1/partner/escrow")
public class PartnerEvidenceController {

    private final PartnerEvidenceService partnerEvidenceService;

    public PartnerEvidenceController(PartnerEvidenceService partnerEvidenceService) {
        this.partnerEvidenceService = partnerEvidenceService;
    }

    @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<EvidenceDto>> deposit(
            @PathVariable Long id,
            @RequestHeader("X-Escrow-Key-Id") String keyId,
            @RequestHeader("X-Escrow-Signature") String signature,
            @RequestHeader("X-Escrow-Timestamp") String timestamp,
            @RequestHeader("X-Escrow-Nonce") String nonce,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "comment", required = false) String comment,
            @RequestParam(value = "clientCapturedAt", required = false) String clientCapturedAt) {
        // A present-but-blank required header is rejected as a 400, symmetric to the
        // MissingRequestHeaderException an absent header produces (both mapped in the
        // GlobalExceptionHandler envelope).
        requireNonBlank(keyId, "X-Escrow-Key-Id");
        requireNonBlank(signature, "X-Escrow-Signature");
        requireNonBlank(timestamp, "X-Escrow-Timestamp");
        requireNonBlank(nonce, "X-Escrow-Nonce");

        List<EvidenceDto> body = partnerEvidenceService
                .deposit(keyId, signature, timestamp, nonce, id, files, comment, clientCapturedAt)
                .stream().map(EvidenceDto::from).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static void requireNonBlank(String value, String header) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Missing required header '" + header + "'");
        }
    }
}
