package com.zlecaf.escrow.web;

import com.zlecaf.escrow.service.TransitionException;
import com.zlecaf.escrow.service.storage.EvidenceStorageException;
import com.zlecaf.escrow.web.ApiExceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message));
    }

    @ExceptionHandler(TransitionException.class)
    public ResponseEntity<Map<String, Object>> onTransition(TransitionException ex) {
        HttpStatus status = ex.getReason() == TransitionException.Reason.UNAUTHORIZED
                ? HttpStatus.FORBIDDEN
                : HttpStatus.CONFLICT;
        return body(status, ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> onConflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> onForbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> onUnauthorized(UnauthorizedException ex) {
        // Partner signature auth failure (unknown/inactive key, bad signature,
        // stale timestamp, replayed nonce): 401 in the standard envelope.
        return body(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> onMaxUploadSize(MaxUploadSizeExceededException ex) {
        // Container multipart cap breached: report as a client size error (400),
        // consistent with the service-arbitrated per-file limit.
        return body(HttpStatus.BAD_REQUEST, "Uploaded file exceeds the maximum permitted size");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> onMissingPart(Exception ex) {
        // A required multipart part, request parameter or request header is absent:
        // return the standard 400 envelope rather than Spring's default error body.
        return body(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler(EvidenceStorageException.class)
    public ResponseEntity<Map<String, Object>> onEvidenceStorage(EvidenceStorageException ex) {
        // Object store unreachable/failed (not a missing object): report a 502 in
        // the standard envelope. No SDK detail is leaked — a fixed, neutral message.
        return body(HttpStatus.BAD_GATEWAY, "Stockage de preuves indisponible");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, message);
    }
}
