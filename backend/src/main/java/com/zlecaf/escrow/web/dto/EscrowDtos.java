package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.EscrowEvent;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Escrow transaction request/response payloads. */
public final class EscrowDtos {

    private EscrowDtos() {}

    public record CreateEscrowRequest(
            @Email @NotBlank String sellerEmail,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotBlank @Size(min = 3, max = 3) String currency,
            String description) {}

    public record EventRequest(@NotNull EscrowEvent event) {}

    public record TransactionDto(
            Long id,
            Long buyerId,
            Long sellerId,
            String buyerEmail,
            String sellerEmail,
            BigDecimal amount,
            String currency,
            EscrowState state,
            String description,
            Instant createdAt,
            Instant updatedAt) {}

    public record AuditLogDto(
            Long id,
            String previousState,
            String nextState,
            Long actionBy,
            Instant timestamp) {
        public static AuditLogDto from(AuditLog a) {
            return new AuditLogDto(a.getId(), a.getPreviousState(), a.getNextState(),
                    a.getActionBy(), a.getTimestamp());
        }
    }

    /** Detail view: the transaction plus its immutable audit trail. */
    public record TransactionDetailDto(TransactionDto transaction, List<AuditLogDto> auditLogs) {}
}
