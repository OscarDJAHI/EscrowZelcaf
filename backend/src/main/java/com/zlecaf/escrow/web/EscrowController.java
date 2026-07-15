package com.zlecaf.escrow.web;

import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.EscrowService;
import com.zlecaf.escrow.web.dto.EscrowDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
}
