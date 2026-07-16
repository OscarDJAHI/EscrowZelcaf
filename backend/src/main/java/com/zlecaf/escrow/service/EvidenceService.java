package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.storage.EvidenceNotFoundException;
import com.zlecaf.escrow.service.storage.EvidenceStorage;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.ConflictException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates evidence deposit: server-authoritative membership, state-window
 * guard, content validation and size bounds, then storage, persistence and an
 * atomic audit entry — all inside one transaction. A {@code files[]} batch is
 * all-or-nothing: every file is validated before any is written, so a single
 * rejection leaves the store and the database untouched. Designed as a reusable
 * service (not a controller method) so Epic 2's composite opening can reuse it.
 */
@Service
public class EvidenceService {

    /** Per-file business limit (bytes), arbitrated here, not by the container. */
    static final long MAX_FILE_SIZE = 10_485_760L;

    /** Deposit is permitted only while the transaction sits in this window. */
    private static final Set<EscrowState> UPLOAD_WINDOW =
            EnumSet.of(EscrowState.FUNDS_LOCKED, EscrowState.SHIPPED, EscrowState.DISPUTED);

    private final EscrowTransactionRepository transactions;
    private final EvidenceFileRepository evidenceFiles;
    private final TransactionAccess access;
    private final EvidenceContentValidator validator;
    private final EvidenceStorage storage;
    private final AuditService auditService;

    public EvidenceService(EscrowTransactionRepository transactions,
                           EvidenceFileRepository evidenceFiles,
                           TransactionAccess access,
                           EvidenceContentValidator validator,
                           EvidenceStorage storage,
                           AuditService auditService) {
        this.transactions = transactions;
        this.evidenceFiles = evidenceFiles;
        this.access = access;
        this.validator = validator;
        this.storage = storage;
        this.auditService = auditService;
    }

    /**
     * Deposits one or more files against a transaction the actor is party to.
     *
     * @return the persisted {@link EvidenceFile} rows (status {@code ACTIVE}).
     * @throws NotFoundException   transaction unknown (404)
     * @throws com.zlecaf.escrow.web.ApiExceptions.ForbiddenException non-party (403)
     * @throws ConflictException   deposit window closed for the current state (409)
     * @throws BadRequestException empty batch, bad file type/size, or bad
     *                             {@code clientCapturedAt} (400)
     */
    @Transactional
    public List<EvidenceFile> deposit(AuthPrincipal actor, Long txId, List<MultipartFile> files,
                                      String comment, String clientCapturedAt) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one file is required");
        }

        // Pessimistic lock: serialises the deposit against concurrent state
        // transitions, so the window check below cannot be invalidated by a
        // RELEASE/REFUND committing between the read and this transaction's commit.
        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));

        ParticipantRole role = access.resolveRole(actor, tx);   // 403 if not a party
        requireUploadWindow(tx.getState());                     // 409 if outside window

        String capturedAt = normalizeCapturedAt(clientCapturedAt); // 400 if not ISO-8601

        // --- Pass 1: validate every file (type + size). No writes happen here,
        // so any rejection aborts the whole batch before it can touch storage. ---
        List<byte[]> contents = new ArrayList<>(files.size());
        List<String> mimeTypes = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            byte[] bytes = read(file);
            if (bytes.length == 0) {
                throw new BadRequestException("Uploaded file is empty");
            }
            if (bytes.length > MAX_FILE_SIZE) {
                throw new BadRequestException(
                        "File exceeds the maximum allowed size of " + MAX_FILE_SIZE + " bytes");
            }
            String mime = validator.validate(bytes, file.getOriginalFilename(), file.getContentType());
            contents.add(bytes);
            mimeTypes.add(mime);
        }

        // --- Pass 2: store, persist and audit each file. Any failure rolls the
        // whole transaction back (business row + audit commit together). ---
        UploaderType uploaderType = toUploaderType(role);
        List<EvidenceFile> saved = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            byte[] bytes = contents.get(i);
            String mime = mimeTypes.get(i);

            String storageKey = storage.store(txId, bytes, mime);

            EvidenceFile evidence = new EvidenceFile();
            evidence.setTransactionId(txId);
            evidence.setUploadedByUserId(actor.userId());
            evidence.setUploaderType(uploaderType);
            evidence.setPartnerCompanyId(null);
            evidence.setOriginalFilename(sanitizeFilename(files.get(i).getOriginalFilename()));
            evidence.setMimeType(mime);
            evidence.setSizeBytes((long) bytes.length);
            evidence.setStorageKey(storageKey);
            evidence.setComment(comment);
            evidence.setStatus(EvidenceStatus.ACTIVE);
            evidence = evidenceFiles.save(evidence);

            auditService.recordEvidenceAdded(txId, actor.userId(), role, tx.getState(),
                    evidence.getId(), sha256Hex(bytes), capturedAt);

            saved.add(evidence);
        }
        return saved;
    }

    /**
     * Lists every evidence row of a transaction the actor is party to, sorted by
     * server-time {@code created_at} ascending. A pure, non-locking read: same
     * membership check as {@link #deposit} and {@code EscrowService.getDetail}
     * (403 for non-parties, resolved role intentionally ignored), no storage
     * access, WITHDRAWN rows included (AD-4).
     *
     * @throws NotFoundException transaction unknown (404)
     * @throws com.zlecaf.escrow.web.ApiExceptions.ForbiddenException non-party (403)
     */
    @Transactional(readOnly = true)
    public List<EvidenceDto> list(AuthPrincipal actor, Long txId) {
        EscrowTransaction tx = transactions.findById(txId)
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
        access.resolveRole(actor, tx);   // 403 if not a party; role ignored for a read
        return evidenceFiles.findByTransactionIdOrderByCreatedAtAscIdAsc(txId)
                .stream().map(EvidenceDto::from).toList();
    }

    /**
     * Opens the original binary of one evidence piece for a party to download.
     * Server-authoritative, in strict order: load the transaction (404), resolve
     * membership (403, before any piece lookup), then the <em>sealed</em>
     * {@code findByIdAndTransactionId} query (404) so a foreign or unknown piece
     * is indistinguishable — the anti-IDOR guard, never a manual comparison over
     * an unsealed {@code findById}. No status filter: a WITHDRAWN piece is still
     * downloadable (restitution is not masking). The stream comes only from the
     * {@link EvidenceStorage} port; a missing object translates to a 404. The
     * returned {@link InputStream} outlives this transaction and is consumed by
     * the web layer, so it is never read into memory here.
     *
     * @throws NotFoundException transaction or piece unknown, or binary absent (404)
     * @throws com.zlecaf.escrow.web.ApiExceptions.ForbiddenException non-party (403)
     */
    @Transactional(readOnly = true)
    public EvidenceDownload download(AuthPrincipal actor, Long txId, Long evidenceId) {
        EscrowTransaction tx = transactions.findById(txId)
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
        access.resolveRole(actor, tx);   // 403 if not a party; role ignored for a read
        EvidenceFile evidence = evidenceFiles.findByIdAndTransactionId(evidenceId, txId)
                .orElseThrow(() -> new NotFoundException("Evidence " + evidenceId + " not found"));
        // Read every metadata field BEFORE opening the stream. size_bytes/mime_type
        // are nullable at the schema level; any failure here (e.g. a null size_bytes
        // unboxing) must surface before a live storage stream exists, so it can never
        // leak an unclosed connection.
        String filename = evidence.getOriginalFilename();
        String contentType = evidence.getMimeType();
        long sizeBytes = evidence.getSizeBytes();
        try {
            InputStream content = storage.load(evidence.getStorageKey());
            return new EvidenceDownload(content, filename, contentType, sizeBytes);
        } catch (EvidenceNotFoundException e) {
            throw new NotFoundException("Evidence binary not found for " + evidenceId);
        }
    }

    private void requireUploadWindow(EscrowState state) {
        if (!UPLOAD_WINDOW.contains(state)) {
            throw new ConflictException(
                    "Evidence cannot be deposited while the transaction is " + state);
        }
    }

    private String normalizeCapturedAt(String clientCapturedAt) {
        if (clientCapturedAt == null || clientCapturedAt.isBlank()) {
            return null;
        }
        try {
            // Parsed for validation only: clientCapturedAt is audit metadata and
            // never orders anything — created_at (server time) is the sole key.
            OffsetDateTime.parse(clientCapturedAt);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("clientCapturedAt must be a valid ISO-8601 timestamp");
        }
        return clientCapturedAt;
    }

    private byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("Could not read the uploaded file");
        }
    }

    private UploaderType toUploaderType(ParticipantRole role) {
        return switch (role) {
            case BUYER -> UploaderType.BUYER;
            case SELLER -> UploaderType.SELLER;
            case ADMIN -> UploaderType.ADMIN;
        };
    }

    /** Column cap for {@code original_filename} (VARCHAR(255)). */
    private static final int MAX_FILENAME_LENGTH = 255;

    /** Basename only: strips any path separators so a hostile name is inert metadata. */
    private String sanitizeFilename(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        basename = basename.trim();
        if (basename.isEmpty()) {
            return null;
        }
        // Keep the tail so the extension survives; the column is VARCHAR(255) and
        // an over-length name must not surface as an unhandled 500 at flush.
        if (basename.length() > MAX_FILENAME_LENGTH) {
            basename = basename.substring(basename.length() - MAX_FILENAME_LENGTH);
        }
        return basename;
    }

    private String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JRE algorithm; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
