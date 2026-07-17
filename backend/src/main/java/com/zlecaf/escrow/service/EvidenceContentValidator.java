package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Validates evidence content by its <em>real</em> type, sniffed from the bytes
 * with Apache Tika — never trusting the client-declared extension or
 * Content-Type alone. Reusable as-is by the composite dispute opening of Epic 2:
 * no validation rule ever lives in a controller.
 *
 * <p>A file is accepted only if all three agree and the real type is on the
 * whitelist {@code image/jpeg, image/png, application/pdf}: the sniffed content
 * type, the filename extension, and the declared Content-Type. Any mismatch, or
 * a real type off the whitelist, is a {@link BadRequestException} (400).
 */
@Component
public class EvidenceContentValidator {

    private static final Set<String> ALLOWED_TYPES =
            Set.of("image/jpeg", "image/png", "application/pdf");

    private final Tika tika = new Tika();

    /**
     * @return the validated real MIME type (guaranteed on the whitelist).
     * @throws BadRequestException if empty, off-whitelist, or the sniffed type,
     *         the extension and the declared Content-Type are not all consistent.
     */
    public String validate(byte[] content, String originalFilename, String declaredContentType) {
        if (content == null || content.length == 0) {
            throw new BadRequestException(ErrorCode.EVIDENCE_INVALID, "Uploaded file is empty");
        }

        String realType = tika.detect(content);
        if (!ALLOWED_TYPES.contains(realType)) {
            throw new BadRequestException(ErrorCode.EVIDENCE_INVALID,
                    "File type not allowed: " + realType + " (only JPEG, PNG and PDF are accepted)");
        }

        String extensionType = mimeForExtension(originalFilename);
        if (extensionType == null || !extensionType.equals(realType)) {
            throw new BadRequestException(ErrorCode.EVIDENCE_INVALID,
                    "Filename extension is inconsistent with the actual file content");
        }

        String declared = normalize(declaredContentType);
        if (declared == null || !declared.equals(realType)) {
            throw new BadRequestException(ErrorCode.EVIDENCE_INVALID,
                    "Declared Content-Type is inconsistent with the actual file content");
        }

        return realType;
    }

    private String mimeForExtension(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String ext = filename.substring(dot + 1).toLowerCase();
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            default -> null;
        };
    }

    private String normalize(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        int semi = contentType.indexOf(';');
        String base = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return base.trim().toLowerCase();
    }
}
