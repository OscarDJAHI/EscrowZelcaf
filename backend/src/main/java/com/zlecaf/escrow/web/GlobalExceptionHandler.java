package com.zlecaf.escrow.web;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.service.TransitionException;
import com.zlecaf.escrow.service.storage.EvidenceStorageException;
import com.zlecaf.escrow.web.ApiExceptions.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * The single envelope builder for every branch below. {@code code} is the
     * stable machine token clients classify against (AD-10); {@code error} stays
     * the HTTP reason phrase and {@code message} stays human-facing text — the
     * addition is purely additive, nothing existing changes name or meaning.
     *
     * <p>{@link Map#of} throws on a null value, so a branch that forgot its code
     * would turn a clean business rejection into a 500. The {@code requireNonNull}
     * makes that failure loud and local instead.
     */
    private ResponseEntity<Map<String, Object>> body(HttpStatus status, ErrorCode code, String message) {
        Objects.requireNonNull(code, "code");
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "code", code.name(),
                "message", message));
    }

    @ExceptionHandler(TransitionException.class)
    public ResponseEntity<Map<String, Object>> onTransition(TransitionException ex) {
        // The code now drives the status fork the Reason enum used to drive: only
        // a role rejection is a 403, every other lifecycle rejection is a 409.
        HttpStatus status = ex.getCode() == ErrorCode.UNAUTHORIZED_TRANSITION
                ? HttpStatus.FORBIDDEN
                : HttpStatus.CONFLICT;
        return body(status, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> onNotFound(NotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(BadRequestException ex) {
        return body(HttpStatus.BAD_REQUEST, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Map<String, Object>> onConflict(ConflictException ex) {
        return body(HttpStatus.CONFLICT, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, Object>> onForbidden(ForbiddenException ex) {
        return body(HttpStatus.FORBIDDEN, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> onUnauthorized(UnauthorizedException ex) {
        // Partner signature auth failure (unknown/inactive key, bad signature,
        // stale timestamp, replayed nonce): 401 in the standard envelope. All six
        // failure sites carry the same opaque AUTH_FAILED code — telling them
        // apart would let a caller enumerate which key-ids exist and are active.
        return body(HttpStatus.UNAUTHORIZED, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> onOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        // A concurrent writer won the @Version race. Previously this escaped the
        // advice entirely and surfaced as an uncoded 500, which a client cannot
        // tell from a genuine server fault: it is a 409, and it is retryable.
        // No SQL, entity or SDK detail is leaked — a fixed, neutral message.
        return body(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION,
                "The transaction was modified concurrently; please retry");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> onMaxUploadSize(MaxUploadSizeExceededException ex) {
        // Container multipart cap breached: report as a client size error (400),
        // consistent with the service-arbitrated per-file limit — and with the same
        // EVIDENCE_INVALID code, since it is the same "this file is unacceptable"
        // verdict arbitrated one layer earlier.
        return body(HttpStatus.BAD_REQUEST, ErrorCode.EVIDENCE_INVALID,
                "Uploaded file exceeds the maximum permitted size");
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class})
    public ResponseEntity<Map<String, Object>> onMissingPart(Exception ex) {
        // A required multipart part, request parameter or request header is absent:
        // return the standard 400 envelope rather than Spring's default error body.
        return body(HttpStatus.BAD_REQUEST, ErrorCode.MISSING_REQUEST_PART, ex.getMessage());
    }

    @ExceptionHandler(EvidenceStorageException.class)
    public ResponseEntity<Map<String, Object>> onEvidenceStorage(EvidenceStorageException ex) {
        // Object store unreachable/failed (not a missing object): report a 502 in
        // the standard envelope. No SDK detail is leaked — a fixed, neutral message.
        return body(HttpStatus.BAD_GATEWAY, ErrorCode.STORAGE_UNAVAILABLE, "Stockage de preuves indisponible");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .findFirst().orElse("Validation failed");
        return body(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR, message);
    }
}
