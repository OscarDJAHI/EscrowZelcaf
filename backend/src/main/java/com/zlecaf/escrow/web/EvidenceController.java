package com.zlecaf.escrow.web;

import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EvidenceService;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
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
}
