package com.zlecaf.escrow.web.dto;

import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.UploaderType;

import java.time.Instant;

/** Evidence response payloads. The opaque storage key is never exposed. */
public final class EvidenceDtos {

    private EvidenceDtos() {}

    public record EvidenceDto(
            Long id,
            Long transactionId,
            Long uploadedByUserId,
            UploaderType uploaderType,
            String originalFilename,
            String mimeType,
            Long sizeBytes,
            String comment,
            EvidenceStatus status,
            Instant createdAt) {

        public static EvidenceDto from(EvidenceFile e) {
            return new EvidenceDto(
                    e.getId(),
                    e.getTransactionId(),
                    e.getUploadedByUserId(),
                    e.getUploaderType(),
                    e.getOriginalFilename(),
                    e.getMimeType(),
                    e.getSizeBytes(),
                    e.getComment(),
                    e.getStatus(),
                    e.getCreatedAt());
        }
    }
}
