package com.zlecaf.escrow.web;

import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EscrowService;
import com.zlecaf.escrow.web.dto.EscrowDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/escrow")
public class EscrowController {

    private final EscrowService escrowService;

    public EscrowController(EscrowService escrowService) {
        this.escrowService = escrowService;
    }

    @PostMapping
    public ResponseEntity<TransactionDto> create(@AuthenticationPrincipal AuthPrincipal actor,
                                                 @Valid @RequestBody CreateEscrowRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(escrowService.create(actor, request));
    }

    @GetMapping
    public List<TransactionDto> list(@AuthenticationPrincipal AuthPrincipal actor) {
        return escrowService.listForUser(actor);
    }

    @GetMapping("/{id}")
    public TransactionDetailDto get(@AuthenticationPrincipal AuthPrincipal actor,
                                    @PathVariable Long id) {
        return escrowService.getDetail(actor, id);
    }

    @PostMapping("/{id}/event")
    public TransactionDto event(@AuthenticationPrincipal AuthPrincipal actor,
                                @PathVariable Long id,
                                @Valid @RequestBody EventRequest request) {
        return escrowService.applyEvent(actor, id, request.event());
    }

    /**
     * Opens a dispute with mandatory evidence in one atomic multipart request.
     * Thin surface: all authority — composite atomicity, membership, transition
     * legality and file validation — lives in {@link EscrowService#openDispute}
     * (and, reused, {@code EvidenceService}). {@code comment} is required here
     * (a missing part is a clean 400); the >= 10-char rule is enforced in the
     * service. Returns {@code 200} because the primary act is a transition on an
     * existing escrow resource.
     */
    @PostMapping(value = "/{id}/dispute", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DisputeOpenedDto openDispute(
            @AuthenticationPrincipal AuthPrincipal actor,
            @PathVariable Long id,
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam("comment") String comment,
            @RequestParam(value = "clientCapturedAt", required = false) String clientCapturedAt) {
        return escrowService.openDispute(actor, id, files, comment, clientCapturedAt);
    }
}
