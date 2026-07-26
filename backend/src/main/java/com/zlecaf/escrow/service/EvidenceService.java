package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.ErrorCode;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.EvidenceStatus;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.domain.UploaderType;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.EvidenceFileRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.scan.MalwareScanner;
import com.zlecaf.escrow.service.scan.ScanVerdict;
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
import java.util.Collections;
import java.util.List;

/**
 * Orchestrates evidence deposit: server-authoritative membership, state-window
 * guard, content validation and size bounds, then storage, persistence and an
 * atomic audit entry — all inside one transaction. A {@code files[]} batch is
 * all-or-nothing: every file is validated before any is written, so a single
 * rejection leaves the store and the database untouched. Designed as a reusable
 * service (not a controller method) so Epic 2's composite opening can reuse it.
 *
 * <p><strong>Analyse antivirus depuis la Story 1.8.</strong> Aucun octet
 * non analysé n'atteint {@link EvidenceStorage#store} : le scan est dans la passe
 * de validation d'{@link #ingest}, le tronc commun des trois routes d'upload, donc
 * toute brique d'upload future en hérite sans rétrofit. La politique est
 * fail-closed : détection -> rejet 400 audité, scanner injoignable -> 502
 * transitoire, jamais un dépôt non analysé.
 */
@Service
public class EvidenceService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceService.class);

    /**
     * Per-file business limit (bytes), arbitrated here, not by the container.
     *
     * <p>{@code public} depuis la Story 1.7 : l'adaptateur de stockage en dérive son
     * plafond de matérialisation (limite métier + marge d'enveloppe). Recopier la
     * valeur là-bas ferait diverger les deux au premier relèvement — les dépôts
     * passeraient et les téléchargements des pièces devenues trop grosses
     * échoueraient en 502, longtemps après la modification qui l'aurait causé.
     */
    public static final long MAX_FILE_SIZE = 10_485_760L;

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
    private final MalwareScanner scanner;
    private final AuditService auditService;

    public EvidenceService(EscrowTransactionRepository transactions,
                           EvidenceFileRepository evidenceFiles,
                           TransactionAccess access,
                           EvidenceContentValidator validator,
                           EvidenceStorage storage,
                           MalwareScanner scanner,
                           AuditService auditService) {
        this.transactions = transactions;
        this.evidenceFiles = evidenceFiles;
        this.access = access;
        this.validator = validator;
        this.storage = storage;
        // Dépendance OBLIGATOIRE (Story 1.8, NFR-P7) : ni Optional, ni @Nullable, ni
        // défaut « pas de scan ». Un dépôt sans scanner câblé ne doit même pas
        // pouvoir démarrer, c'est la garantie que porte le type.
        this.scanner = scanner;
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
        // Reject an empty or over-cap batch BEFORE taking the row lock or reading
        // any bytes — the original (pre-refactor) precedence and the memory guard.
        requireDepositableBatch(files);
        // Pessimistic lock: serialises the deposit against concurrent state
        // transitions, so the window check below cannot be invalidated by a
        // RELEASE/REFUND committing between the read and this transaction's commit.
        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction " + txId + " not found"));

        ParticipantRole role = access.resolveRole(actor, tx);   // 403 if not a party
        requireUploadWindow(tx.getState());                     // 409 if outside window

        // Human attribution: the acting user owns the row and the audit entry.
        Attribution attribution =
                new Attribution(toUploaderType(role), actor.userId(), null, actor.userId(), role);
        return ingest(tx, files, comment, clientCapturedAt, attribution);
    }

    /**
     * Deposits one or more files on behalf of a machine partner (Story 3.2). The
     * caller ({@code PartnerEvidenceService}) has already authenticated the request
     * by HMAC signature; here the <em>company</em> behind the key is authorised as a
     * party and the same reused ingestion core writes the evidence with a
     * {@code CARRIER_PARTNER} attribution ({@code partner_company_id} set,
     * {@code uploaded_by_user_id} null, audit actor/role null). No ingestion rule is
     * duplicated: validation, storage, rollback cleanup and the MANDATORY audit are
     * exactly those of the user path.
     *
     * @throws NotFoundException   transaction unknown (404)
     * @throws com.zlecaf.escrow.web.ApiExceptions.ForbiddenException company not a party (403)
     * @throws ConflictException   deposit window closed for the current state (409)
     * @throws BadRequestException empty batch, bad file type/size, or bad
     *                             {@code clientCapturedAt} (400)
     */
    @Transactional
    public List<EvidenceFile> depositAsPartner(Long companyId, Long txId, List<MultipartFile> files,
                                               String comment, String clientCapturedAt) {
        requireDepositableBatch(files); // gate before the lock (PartnerEvidenceService also gates before hashing)
        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction " + txId + " not found"));

        access.requireCompanyParticipant(tx, companyId);       // 403 if company not a party
        requireUploadWindow(tx.getState());                     // 409 if outside window

        Attribution attribution =
                new Attribution(UploaderType.CARRIER_PARTNER, null, companyId, null, null);
        return ingest(tx, files, comment, clientCapturedAt, attribution);
    }

    /**
     * Reusable ingestion core shared by the user ({@link #deposit}) and partner
     * ({@link #depositAsPartner}) paths, parameterised only by {@link Attribution}.
     * The caller has already loaded the (locked) transaction and enforced its
     * membership and state-window rules; everything the two paths have in common —
     * batch cardinality, all-or-nothing validation, opaque storage, persistence,
     * rollback cleanup and the MANDATORY {@code EVIDENCE_ADDED} audit — lives here so
     * no ingestion rule is ever duplicated.
     */
    private List<EvidenceFile> ingest(EscrowTransaction tx, List<MultipartFile> files,
                                      String comment, String clientCapturedAt, Attribution attribution) {
        Long txId = tx.getId();
        requireDepositableBatch(files); // defense-in-depth: both callers already gate this

        String capturedAt = normalizeCapturedAt(clientCapturedAt); // 400 if not ISO-8601

        // --- Pass 1: validate every file (type + size + malware scan). No writes
        // happen here, so any rejection aborts the whole batch before it can touch
        // storage. ---
        List<byte[]> contents = new ArrayList<>(files.size());
        List<String> mimeTypes = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            byte[] bytes = read(file);
            if (bytes.length == 0) {
                throw new BadRequestException(ErrorCode.EVIDENCE_INVALID, "Uploaded file is empty");
            }
            if (bytes.length > MAX_FILE_SIZE) {
                throw new BadRequestException(ErrorCode.EVIDENCE_INVALID,
                        "File exceeds the maximum allowed size of " + MAX_FILE_SIZE + " bytes");
            }
            String mime = validator.validate(bytes, file.getOriginalFilename(), file.getContentType());
            requireCleanContent(tx, file, bytes, attribution);
            contents.add(bytes);
            mimeTypes.add(mime);
        }

        // --- Pass 2: store, persist and audit each file. Any failure rolls the
        // whole transaction back (business row + audit commit together). Object
        // storage is NOT transactional, so a store() that succeeded before a later
        // failure would leave an orphaned binary; a rollback-time cleanup deletes
        // exactly the keys written by this transaction. ---
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
            evidence.setUploadedByUserId(attribution.uploadedByUserId());
            evidence.setUploaderType(attribution.uploaderType());
            evidence.setPartnerCompanyId(attribution.partnerCompanyId());
            evidence.setOriginalFilename(sanitizeFilename(files.get(i).getOriginalFilename()));
            evidence.setMimeType(mime);
            evidence.setSizeBytes((long) bytes.length);
            evidence.setStorageKey(storageKey);
            evidence.setComment(comment);
            evidence.setStatus(EvidenceStatus.ACTIVE);
            evidence = evidenceFiles.save(evidence);

            auditService.recordEvidenceAdded(txId, attribution.auditActorId(), attribution.auditRole(),
                    tx.getState(), evidence.getId(), sha256Hex(bytes), capturedAt);

            saved.add(evidence);
        }
        return saved;
    }

    /**
     * Attribution carrier that lets one ingestion core write either a human or a
     * partner row: the user path passes the acting user (row owner + audit actor);
     * the partner path passes the company id with null user/audit-actor/role, which
     * the {@code ck_evidence_attribution} CHECK (V3) requires for {@code CARRIER_PARTNER}.
     */
    private record Attribution(UploaderType uploaderType, Long uploadedByUserId,
                               Long partnerCompanyId, Long auditActorId, ParticipantRole auditRole) {}

    /**
     * Analyse un fichier déjà validé (non vide, sous la limite de taille, type réel
     * sur la whitelist) et rejette le lot entier si le moteur déclenche
     * (Story 1.8, NFR-P7).
     *
     * <p><b>Pourquoi ici, et nulle part ailleurs.</b> Les gardes qui précèdent
     * coûtent quelques microsecondes et éliminent déjà le vide, l'oversize et les
     * types hors whitelist : le scanner ne reçoit donc que des JPEG/PNG/PDF bien
     * formés. La passe 1 est de plus le dernier endroit où RIEN n'est encore écrit,
     * ce qui donne le tout-ou-rien du lot gratuitement. Et surtout on est en amont
     * de {@link EvidenceStorage#store}, donc en amont de l'enveloppe AES-GCM de la
     * Story 1.7 : après chiffrement, les octets ne sont plus analysables.
     *
     * <p>Le scan vit dans le tronc commun d'{@link #ingest} et non dans un
     * controller : les trois routes d'upload existantes (preuve utilisateur, litige
     * composite, dépôt partenaire signé) en héritent d'office — et toute brique
     * d'upload créée plus tard aussi, sans rétrofit.
     *
     * @throws BadRequestException contenu malveillant détecté (400,
     *                             {@code EVIDENCE_MALWARE_DETECTED})
     * @throws com.zlecaf.escrow.service.scan.MalwareScanUnavailableException aucun
     *                             verdict n'a pu être obtenu (502,
     *                             {@code SCAN_UNAVAILABLE}) — jamais un repli
     *                             « on stocke sans analyser »
     */
    private void requireCleanContent(EscrowTransaction tx, MultipartFile file, byte[] bytes,
                                     Attribution attribution) {
        ScanVerdict verdict = scanner.scan(bytes);
        if (!verdict.infected()) {
            return;
        }
        String safeName = sanitizeFilename(file.getOriginalFilename());
        // Audité AVANT de lever : recordEvidenceRejectedByScan est en REQUIRES_NEW,
        // donc l'entrée commite dans sa propre transaction et survit au rollback que
        // l'exception ci-dessous déclenche. Une panne de scanner, elle, n'est PAS
        // auditée (WARN seulement) : l'auditer inonderait une table append-only à
        // rétention >= 5 ans (AD-25) au premier incident d'infrastructure.
        auditService.recordEvidenceRejectedByScan(tx.getId(), attribution.auditActorId(),
                attribution.auditRole(), tx.getState(), safeName, sha256Hex(bytes), verdict.signature());
        log.warn("Malware detected on evidence ingestion for transaction {} (file '{}', signature '{}'): "
                        + "deposit rejected, nothing stored",
                tx.getId(), forLog(safeName), verdict.signature());
        // Le message nomme le fichier — sans quoi le déposant d'un lot de vingt
        // pièces ne saurait pas laquelle retirer — mais TAIT le nom de signature :
        // le rendre transformerait l'endpoint en banc d'essai d'évasion.
        throw new BadRequestException(ErrorCode.EVIDENCE_MALWARE_DETECTED,
                (safeName == null ? "Un fichier du dépôt" : "Le fichier « " + safeName + " »")
                        + " a été refusé : contenu malveillant détecté.");
    }

    /**
     * Neutralise les caractères de contrôle d'un nom de fichier avant de le faire
     * entrer dans une ligne de journal. {@link #sanitizeFilename} garantit un
     * basename borné, pas l'absence de retour chariot : sans ce filtre, un nom
     * hostile fabriquerait de fausses lignes de log autour d'un rejet de sécurité,
     * c'est-à-dire à l'endroit précis où l'on relira les traces après incident.
     */
    private static String forLog(String name) {
        if (name == null) {
            return null;
        }
        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            safe.append(c >= 0x20 && c != 0x7F ? c : '?');
        }
        return safe.toString();
    }

    /**
     * Rejects an empty batch or one over {@link PlatformLimits#MAX_FILES_PER_DEPOSIT}
     * BEFORE any byte buffering. Exposed so the partner path
     * ({@code PartnerEvidenceService}) can enforce the cap ahead of signature
     * hashing, closing a pre-auth amplification vector where a known (public)
     * key-id would otherwise force the server to read and SHA-256 an unbounded
     * number of file parts before the cap was ever consulted.
     *
     * @throws BadRequestException empty batch or more than the permitted file count (400).
     */
    public static void requireDepositableBatch(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one file is required");
        }
        if (files.size() > PlatformLimits.MAX_FILES_PER_DEPOSIT) {
            throw new BadRequestException(ErrorCode.TOO_MANY_FILES,
                    "A deposit accepts at most " + PlatformLimits.MAX_FILES_PER_DEPOSIT + " files");
        }
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
                .orElseThrow(() -> new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction " + txId + " not found"));
        access.resolveRole(actor, tx);   // 403 if not a party; role ignored for a read
        // Hard cap the read (unsorted Pageable → LIMIT only; ordering stays from
        // the method name). Query DESC so the LIMIT keeps the MOST RECENT rows —
        // an ASC limit would keep the oldest and silently drop the recent tail —
        // then reverse in memory (≤ MAX_LIST_RESULTS elements) to restore the
        // contract's created_at asc, id asc order. Bounds memory without
        // paginating: still a JSON array.
        List<EvidenceFile> recent = new ArrayList<>(evidenceFiles.findByTransactionIdOrderByCreatedAtDescIdDesc(
                txId, PageRequest.of(0, PlatformLimits.MAX_LIST_RESULTS)));
        Collections.reverse(recent);
        return recent.stream().map(EvidenceDto::from).toList();
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
     * the web layer.
     *
     * <p><strong>Plus de streaming depuis la Story 1.7.</strong> L'adaptateur
     * matérialise l'objet pour le déchiffrer : GCM n'authentifie qu'au tag final,
     * un flux rendu au fil de l'eau serait du clair non authentifié. Le flux rendu
     * ici est donc adossé à un tampon mémoire, borné par la limite métier de
     * taille de pièce — d'où le maintien de la borne, et le report au ledger de la
     * question du plafond mémoire sous téléchargements concurrents.
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
                .orElseThrow(() -> new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction " + txId + " not found"));
        ParticipantRole role = access.resolveRole(actor, tx);   // 403 if not a party
        EvidenceFile evidence = evidenceFiles.findByIdAndTransactionId(evidenceId, txId)
                .orElseThrow(() -> new NotFoundException("Evidence " + evidenceId + " not found"));
        // Read every metadata field BEFORE opening the stream. size_bytes/mime_type
        // are nullable at the schema level, so size_bytes is kept boxed (Long) and
        // never unboxed here — a null size is a valid piece, carried through to the
        // controller which then omits the Content-Length header.
        String filename = evidence.getOriginalFilename();
        String contentType = evidence.getMimeType();
        Long sizeBytes = evidence.getSizeBytes();
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
            // Synchronous window: if the audit write throws in-method the transaction
            // rolls back and the stream is never handed to the controller. Close it
            // here and rethrow. The commit-time hook below is registered ONLY once
            // the audit has succeeded, so this catch and that hook are mutually
            // exclusive — the stream is never closed twice.
            try { content.close(); } catch (IOException ignored) { /* best-effort */ }
            throw e;
        }
        // The audit succeeded, but the stream now outlives this method: the writable
        // transaction commits in the proxy AFTER download() returns, still holding
        // the open stream, and a commit failure there is out of reach of any
        // in-method catch. Register a completion hook that closes the stream on any
        // non-committed outcome so a failed commit cannot leak a live storage
        // connection. On a successful commit the web layer owns and closes the
        // stream, so the hook leaves it alone.
        registerStreamCloseOnAbort(content);
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
                .orElseThrow(() -> new NotFoundException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction " + txId + " not found"));

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
            throw new ConflictException(ErrorCode.EVIDENCE_FLOOR_VIOLATION,
                    "Withdrawal would leave the dispute without evidence");
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
            throw new ConflictException(windowCode(state),
                    "Evidence cannot be deposited while the transaction is " + state);
        }
    }

    private void requireWithdrawWindow(EscrowState state) {
        if (!state.allowsEvidenceMutation()) {
            throw new ConflictException(windowCode(state),
                    "Evidence cannot be withdrawn while the transaction is " + state);
        }
    }

    /**
     * Why the window is shut, told apart. {@code allowsEvidenceMutation()} is false
     * for INITIATED — where the window has not opened <em>yet</em> and will — and for
     * the terminal states, where it is shut forever. Both are permanent for the
     * request at hand, but only one leaves the caller anything to wait for, and the
     * client is entitled to say so. The message is unchanged either way.
     */
    private static ErrorCode windowCode(EscrowState state) {
        return state.isTerminal() ? ErrorCode.TRANSACTION_TERMINAL : ErrorCode.WINDOW_CLOSED;
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

    /**
     * Registers a completion hook that closes an open storage stream if the
     * transaction does NOT commit. The download transaction is writable (it audits
     * access), so its commit happens in the proxy AFTER {@code download} returns,
     * still holding the open stream; a commit failure there is out of reach of any
     * in-method {@code try/catch}. On a successful commit the web layer owns and
     * consumes/closes the stream, so this hook closes it only on the abort path
     * ({@code status != STATUS_COMMITTED}). Best-effort: an {@link IOException} on
     * close is swallowed, since the transaction outcome is already decided.
     */
    private void registerStreamCloseOnAbort(InputStream content) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    content.close();
                } catch (IOException ignored) {
                    // best-effort: the transaction did not commit, nothing owns the stream
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
            // The bytes, not the file, are the problem: the identical upload may well
            // succeed on a retry. Hence a TRANSIENT code under a 400 — the clearest
            // case of why the code cannot be derived from the status.
            throw new BadRequestException(ErrorCode.FILE_READ_ERROR, "Could not read the uploaded file");
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
