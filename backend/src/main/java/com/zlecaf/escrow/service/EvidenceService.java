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
import com.zlecaf.escrow.web.ApiExceptions.ForbiddenException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    /** Per-file business limit (bytes), arbitrated here, not by the container. */
    static final long MAX_FILE_SIZE = 10_485_760L;

    /**
     * Dispute evidence floor (FR-6): a DISPUTED transaction must keep at least this
     * many ACTIVE pieces, so a withdrawal that would drop the count to (or below)
     * it is refused. Bounded to DISPUTED — outside a dispute there is no evidence
     * dossier to protect and withdrawal is free, even down to zero.
     */
    static final long MIN_ACTIVE_EVIDENCE_IN_DISPUTE = 1;

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
        // Batch cardinality cap, enforced BEFORE any byte buffering (Pass 1) so a
        // hostile over-plafond batch cannot exhaust memory.
        if (files.size() > PlatformLimits.MAX_FILES_PER_DEPOSIT) {
            throw new BadRequestException(
                    "A deposit accepts at most " + PlatformLimits.MAX_FILES_PER_DEPOSIT + " files");
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
        // whole transaction back (business row + audit commit together). Object
        // storage is NOT transactional, so a store() that succeeded before a later
        // failure would leave an orphaned binary; a rollback-time cleanup deletes
        // exactly the keys written by this transaction. ---
        UploaderType uploaderType = toUploaderType(role);
        List<String> storedKeys = new ArrayList<>(files.size());
        registerRollbackCleanup(storedKeys);
        List<EvidenceFile> saved = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            byte[] bytes = contents.get(i);
            String mime = mimeTypes.get(i);

            String storageKey = storage.store(txId, bytes, mime);
            // Track BEFORE the DB write that may fail: if save/audit throws, this
            // key is already queued for rollback cleanup.
            storedKeys.add(storageKey);

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
        // Hard cap the read (unsorted Pageable → LIMIT only; ordering stays from
        // the method name). Bounds memory without paginating: still a JSON array.
        return evidenceFiles.findByTransactionIdOrderByCreatedAtAscIdAsc(
                        txId, PageRequest.of(0, PlatformLimits.MAX_LIST_RESULTS))
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
     * <p><strong>Why writable.</strong> The download is audited ({@code
     * EVIDENCE_DOWNLOADED}) atomically with the access authorization, so the
     * transaction is {@code @Transactional} (writable) — an {@code INSERT} in a
     * {@code readOnly} connection would fail. The audit is written only AFTER a
     * successful {@code storage.load}, so a storage failure (502) rolls the whole
     * thing back and no phantom download-audit remains.
     *
     * @throws NotFoundException transaction or piece unknown, or binary absent (404)
     * @throws com.zlecaf.escrow.web.ApiExceptions.ForbiddenException non-party (403)
     * @throws com.zlecaf.escrow.service.storage.EvidenceStorageException storage failure (502)
     */
    @Transactional
    public EvidenceDownload download(AuthPrincipal actor, Long txId, Long evidenceId) {
        EscrowTransaction tx = transactions.findById(txId)
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
        ParticipantRole role = access.resolveRole(actor, tx);   // 403 if not a party
        EvidenceFile evidence = evidenceFiles.findByIdAndTransactionId(evidenceId, txId)
                .orElseThrow(() -> new NotFoundException("Evidence " + evidenceId + " not found"));
        // Read every metadata field BEFORE opening the stream. size_bytes/mime_type
        // are nullable at the schema level; any failure here (e.g. a null size_bytes
        // unboxing) must surface before a live storage stream exists, so it can never
        // leak an unclosed connection.
        String filename = evidence.getOriginalFilename();
        String contentType = evidence.getMimeType();
        long sizeBytes = evidence.getSizeBytes();
        InputStream content;
        try {
            content = storage.load(evidence.getStorageKey());
        } catch (EvidenceNotFoundException e) {
            throw new NotFoundException("Evidence binary not found for " + evidenceId);
        }
        // Audited only after a successful load: an EvidenceStorageException (502)
        // thrown above never reaches here, so no download audit is written and the
        // transaction rolls back cleanly.
        try {
            auditService.recordEvidenceDownloaded(txId, actor.userId(), role, tx.getState(), evidence.getId());
        } catch (RuntimeException e) {
            // The stream is already open; if the audit write fails the transaction
            // rolls back and the stream is never handed to the controller. Close it
            // here so a failed download cannot leak a live storage connection.
            try { content.close(); } catch (IOException ignored) { /* best-effort */ }
            throw e;
        }
        return new EvidenceDownload(content, filename, contentType, sizeBytes);
    }

    /**
     * Logically withdraws one evidence piece the actor deposited: {@code ACTIVE ->
     * WITHDRAWN}, never a physical delete (AD-4, the row stays visible in the list).
     * A metadata-only operation — the storage binary is never read, so withdrawal
     * does not depend on object-store availability.
     *
     * <p>Server-authoritative, in strict order: take the transaction under a
     * {@code PESSIMISTIC_WRITE} lock (404 if unknown), resolve membership (403 for
     * a non-party, before any piece lookup), then the <em>sealed</em>
     * {@code findByIdAndTransactionId} query (404) so a foreign or unknown piece is
     * indistinguishable — the anti-IDOR guard, never a manual comparison over an
     * unsealed {@code findById}. Only one's own piece may be withdrawn (403), the
     * deposit window is reused (409 outside it, Story 2.2 lock), and an
     * already-withdrawn piece is a 409.
     *
     * <p><strong>Why the dispute floor holds under a race.</strong> Two concurrent
     * withdrawals on the same transaction contend on the <em>same</em> locked row:
     * the second blocks until the first commits, then recounts
     * {@code countByTransactionIdAndStatus(txId, ACTIVE)} on the committed state and
     * sees the decremented total — so the last-remaining-ACTIVE check cannot be
     * invalidated by a TOCTOU window. The audit entry commits atomically with the
     * status flip.
     *
     * @throws NotFoundException  transaction or piece unknown/foreign (404)
     * @throws ForbiddenException non-party, or not the piece's owner (403)
     * @throws ConflictException  withdrawal window closed, piece already withdrawn,
     *                            or the dispute floor would be breached (409)
     */
    @Transactional
    public EvidenceDto withdraw(AuthPrincipal actor, Long txId, Long evidenceId) {
        // Pessimistic lock: serialises this withdrawal against concurrent ones (and
        // state transitions) on the same transaction, so the floor count below is
        // read on committed state and cannot be undercut by a lost update.
        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));

        ParticipantRole role = access.resolveRole(actor, tx);   // 403 if not a party

        // Sealed anti-IDOR lookup: a foreign or unknown piece is a 404, never a findById.
        EvidenceFile evidence = evidenceFiles.findByIdAndTransactionId(evidenceId, txId)
                .orElseThrow(() -> new NotFoundException("Evidence " + evidenceId + " not found"));

        // Own-piece guard: a null uploader (partner upload) is never the actor's.
        if (evidence.getUploadedByUserId() == null
                || !evidence.getUploadedByUserId().equals(actor.userId())) {
            throw new ForbiddenException("You can only withdraw your own evidence");
        }

        requireWithdrawWindow(tx.getState());                   // 409 if outside window

        if (evidence.getStatus() == EvidenceStatus.WITHDRAWN) {
            throw new ConflictException("Evidence is already withdrawn");
        }

        // Dispute floor (FR-6): only in DISPUTED, count ACTIVE across all parties
        // behind the row lock; refuse a withdrawal that would leave no evidence.
        // The recount-after-the-lock guarantee assumes READ COMMITTED (Postgres
        // default): the loser, unblocked once the winner commits, takes a fresh
        // snapshot and sees the decremented total.
        if (tx.getState() == EscrowState.DISPUTED
                && evidenceFiles.countByTransactionIdAndStatus(txId, EvidenceStatus.ACTIVE)
                        <= MIN_ACTIVE_EVIDENCE_IN_DISPUTE) {
            throw new ConflictException("Withdrawal would leave the dispute without evidence");
        }

        evidence.setStatus(EvidenceStatus.WITHDRAWN);
        evidence.setWithdrawnAt(java.time.Instant.now());       // server time, authoritative
        evidence.setWithdrawnByUserId(actor.userId());
        evidence = evidenceFiles.save(evidence);

        auditService.recordEvidenceWithdrawn(txId, actor.userId(), role, tx.getState(), evidence.getId());

        return EvidenceDto.from(evidence);
    }

    private void requireUploadWindow(EscrowState state) {
        if (!state.allowsEvidenceMutation()) {
            throw new ConflictException(
                    "Evidence cannot be deposited while the transaction is " + state);
        }
    }

    private void requireWithdrawWindow(EscrowState state) {
        if (!state.allowsEvidenceMutation()) {
            throw new ConflictException(
                    "Evidence cannot be withdrawn while the transaction is " + state);
        }
    }

    /**
     * Registers a rollback-only cleanup that deletes the binaries stored by this
     * transaction if it does not commit. {@code storedKeys} is read at completion
     * time, so keys added after registration are still cleaned up. Deletion is
     * best-effort: an orphaned object is a tolerated leak, but a throw here would
     * mask the real rollback cause, so every failure is swallowed and logged.
     */
    private void registerRollbackCleanup(List<String> storedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                for (String key : storedKeys) {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Failed to clean up orphaned evidence object {} after rollback", key, e);
                    }
                }
            }
        });
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
