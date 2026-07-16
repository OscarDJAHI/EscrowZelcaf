package com.zlecaf.escrow.web;

import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EvidenceDownload;
import com.zlecaf.escrow.service.EvidenceService;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Thin multipart surface for depositing evidence. All authority — membership,
 * state window, content validation, size bounds — lives in {@link EvidenceService}
 * and its reusable collaborators; this controller only adapts HTTP to the service.
 */
@RestController
@RequestMapping("/api/v1/escrow")
public class EvidenceController {

    private final EvidenceService evidenceService;

    public EvidenceController(EvidenceService evidenceService) {
        this.evidenceService = evidenceService;
    }

    @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<EvidenceDto>> deposit(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable Long id,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "comment", required = false) String comment,
            @RequestParam(value = "clientCapturedAt", required = false) String clientCapturedAt) {
        List<EvidenceDto> body = evidenceService.deposit(actor, id, files, comment, clientCapturedAt)
                .stream().map(EvidenceDto::from).toList();
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}/evidence")
    public List<EvidenceDto> list(@AuthenticationPrincipal AuthPrincipal actor, @PathVariable Long id) {
        return evidenceService.list(actor, id);
    }

    /**
     * Streams the original binary of one evidence piece back to a party. All
     * authority — membership, sealed anti-IDOR lookup, storage access — lives in
     * {@link EvidenceService}; this controller only wires the response. The binary
     * is always served as an <strong>attachment</strong> (a download, never an
     * in-browser render): {@link ContentDisposition} RFC 5987 encoding neutralises
     * header injection and forces a download of stored content rather than letting
     * the browser render it (anti-XSS, NFR-2). The service owns the storage stream;
     * Spring consumes it after this method returns.
     */
    @GetMapping("/{id}/evidence/{evidenceId}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable Long id,
            @PathVariable Long evidenceId) {
        EvidenceDownload d = evidenceService.download(actor, id, evidenceId);
        // The service has already opened a live storage stream. If building the
        // response throws (e.g. an unparseable stored content-type), close it here:
        // Spring only closes the stream once it owns the InputStreamResource.
        try {
            ContentDisposition cd = ContentDisposition.attachment()
                    .filename(d.filename(), StandardCharsets.UTF_8)
                    .build();
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                    .contentType(MediaType.parseMediaType(d.contentType()))
                    .contentLength(d.sizeBytes())
                    .body(new InputStreamResource(d.content()));
        } catch (RuntimeException ex) {
            closeQuietly(d.content());
            throw ex;
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // best-effort: already unwinding a failure
        }
    }
}
