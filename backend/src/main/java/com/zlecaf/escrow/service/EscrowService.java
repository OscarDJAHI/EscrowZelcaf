package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.*;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
import com.zlecaf.escrow.service.scan.MalwareScanUnavailableException;
import com.zlecaf.escrow.web.ApiExceptions;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.NotFoundException;
import com.zlecaf.escrow.web.dto.EscrowDtos.*;
import com.zlecaf.escrow.web.dto.EvidenceDtos.EvidenceDto;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the escrow lifecycle. Every mutation runs in a database
 * transaction; state changes take a pessimistic row lock so that concurrent
 * event submissions on the same transaction are serialised, and each attempt —
 * accepted or rejected — is written to the immutable audit trail.
 */
@Service
public class EscrowService {

    private final EscrowTransactionRepository transactions;
    private final UserRepository users;
    private final AuditLogRepository auditLogs;
    private final EscrowStateMachine stateMachine;
    private final AuditService auditService;
    private final TransactionAccess transactionAccess;
    private final EvidenceService evidenceService;
    private final ApplicationEventPublisher events;

    public EscrowService(EscrowTransactionRepository transactions,
                         UserRepository users,
                         AuditLogRepository auditLogs,
                         EscrowStateMachine stateMachine,
                         AuditService auditService,
                         TransactionAccess transactionAccess,
                         EvidenceService evidenceService,
                         ApplicationEventPublisher events) {
        this.transactions = transactions;
        this.users = users;
        this.auditLogs = auditLogs;
        this.stateMachine = stateMachine;
        this.auditService = auditService;
        this.transactionAccess = transactionAccess;
        this.evidenceService = evidenceService;
        this.events = events;
    }

    /** Buyer initiates an escrow contract against a seller identified by email. */
    @Transactional
    public TransactionDto create(AuthPrincipal actor, CreateEscrowRequest request) {
        User buyer = users.findById(actor.userId())
                .orElseThrow(() -> new NotFoundException("Acting user not found"));
        User seller = users.findByEmail(request.sellerEmail().trim().toLowerCase())
                .orElseThrow(() -> new BadRequestException("No seller registered with that email"));
        if (seller.getId().equals(buyer.getId())) {
            throw new BadRequestException("Buyer and seller must be different users");
        }

        EscrowTransaction tx = new EscrowTransaction();
        tx.setBuyerId(buyer.getId());
        tx.setSellerId(seller.getId());
        tx.setAmount(request.amount());
        tx.setCurrency(request.currency().toUpperCase());
        tx.setDescription(request.description());
        tx.setState(EscrowState.INITIATED);
        tx = transactions.save(tx);

        // Genesis audit entry: NONE -> INITIATED.
        auditService.recordSuccess(tx.getId(), buyer.getId(), ParticipantRole.BUYER,
                null, null, EscrowState.INITIATED);

        publishAfterCommit(tx, null, EscrowState.INITIATED, null, buyer.getId());
        return toDto(tx, Map.of(buyer.getId(), buyer, seller.getId(), seller));
    }

    /**
     * Applies an event to the state machine under a pessimistic lock. On an
     * illegal or unauthorised transition the attempt is logged and the call is
     * rejected without mutating financial state.
     *
     * <p>{@code OPEN_DISPUTE} is intentionally NOT accepted here: a dispute may
     * only be opened through the composite {@code POST /{id}/dispute} endpoint,
     * which enforces the mandatory-evidence invariant. Allowing it on this
     * generic path would let a caller move a transaction to {@code DISPUTED}
     * with no evidence and no comment, defeating that invariant.
     */
    @Transactional
    public TransactionDto applyEvent(AuthPrincipal actor, Long txId, EscrowEvent event) {
        if (event == EscrowEvent.OPEN_DISPUTE) {
            throw new BadRequestException(
                    "Opening a dispute requires evidence; use POST /api/v1/escrow/{id}/dispute");
        }

        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(ApiExceptions::transactionNotFound);

        ParticipantRole role = transactionAccess.resolveRole(actor, tx);
        EscrowState previous = tx.getState();

        EscrowState next;
        try {
            next = stateMachine.determineNextState(previous, event, role);
        } catch (TransitionException ex) {
            // Durably record the rejected attempt (separate transaction), then reject.
            auditService.recordFailure(txId, actor.userId(), role, event, previous, ex.getMessage());
            throw ex;
        }

        tx.setState(next);
        transactions.save(tx);

        auditService.recordSuccess(txId, actor.userId(), role, event, previous, next);
        publishAfterCommit(tx, previous, next, event, actor.userId());

        return toDto(tx, resolveParties(tx));
    }

    /**
     * Opens a dispute as a single atomic act: transitions the transaction to
     * {@code DISPUTED} <em>and</em> attaches the mandatory evidence within one
     * transaction. Either both happen or neither does — a rejected file, an
     * unreachable object store or an illegal transition rolls the whole thing
     * back (no {@code DISPUTED} state, no {@code evidence_files} row, no success
     * audit). All file validation, storage, the {@code EVIDENCE_ADDED} audit and
     * the rollback-time object cleanup are reused verbatim from
     * {@link EvidenceService#deposit}; nothing is duplicated here.
     */
    @Transactional
    public DisputeOpenedDto openDispute(AuthPrincipal actor, Long txId, List<MultipartFile> files,
                                        String comment, String clientCapturedAt) {
        // Composite pre-conditions, enforced BEFORE any state change so a bad
        // request never leaves a half-open dispute: at least one file and a
        // substantive comment (>= 10 characters after trim).
        if (files == null || files.isEmpty()) {
            throw new BadRequestException("At least one evidence file is required to open a dispute");
        }
        if (comment == null || comment.trim().length() < 10) {
            throw new BadRequestException(ErrorCode.COMMENT_TOO_SHORT,
                    "A comment of at least 10 characters is required to open a dispute");
        }
        // Same shared file cap as a plain deposit, enforced BEFORE the state
        // transition (and before any byte buffering) so an over-plafond batch
        // never leaves a half-open dispute.
        if (files.size() > PlatformLimits.MAX_FILES_PER_DEPOSIT) {
            throw new BadRequestException(ErrorCode.TOO_MANY_FILES,
                    "A deposit accepts at most " + PlatformLimits.MAX_FILES_PER_DEPOSIT + " files");
        }

        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(ApiExceptions::transactionNotFound);

        ParticipantRole role = transactionAccess.resolveRole(actor, tx);
        EscrowState previous = tx.getState();

        EscrowState next;
        try {
            next = stateMachine.determineNextState(previous, EscrowEvent.OPEN_DISPUTE, role);
        } catch (TransitionException ex) {
            // Durably record the rejected attempt (separate transaction), then reject.
            auditService.recordFailure(txId, actor.userId(), role, EscrowEvent.OPEN_DISPUTE, previous, ex.getMessage());
            throw refineTerminalRejection(txId, ex);
        }

        tx.setState(next);
        transactions.save(tx);
        auditService.recordSuccess(txId, actor.userId(), role, EscrowEvent.OPEN_DISPUTE, previous, next);

        // Reuse Epic 1 ingestion wholesale: validation, storage, evidence rows,
        // the EVIDENCE_ADDED audit and rollback-time object cleanup. Called AFTER
        // the transition so any rejection here rolls the transition back too within
        // this single unit of work (DISPUTED is already inside deposit's upload
        // window, so the deposit itself is legal).
        List<EvidenceFile> evidence;
        try {
            evidence = evidenceService.deposit(actor, txId, files, comment, clientCapturedAt);
        } catch (MalwareScanUnavailableException ex) {
            // Panne d'analyse : NON auditée (revue 1.8). C'est l'invariant que la
            // Story 1.8 pose explicitement — auditer une indisponibilité inonderait
            // une table append-only à rétention >= 5 ans (AD-25) dès le premier
            // incident d'infrastructure, une ligne par tentative. Le `catch
            // (RuntimeException)` d'origine avalait cette exception comme les autres
            // et écrivait la ligne que l'invariant interdit — en y persistant, via
            // ex.getMessage(), l'hôte et le port de clamd que GlobalExceptionHandler
            // prend soin de tenir hors de la réponse HTTP.
            //
            // Le rejet d'un fichier INFECTÉ reste audité, lui : par
            // EvidenceService.requireCleanContent, en REQUIRES_NEW, avant que
            // l'exception ne remonte ici.
            throw ex;
        } catch (RuntimeException ex) {
            // A deposit-stage failure (bad file 400, storage down 502) rolls the
            // whole composite back — including the recordSuccess above. Durably
            // record the rejected attempt in a separate transaction (REQUIRES_NEW)
            // so this leaves the same audit trail as an illegal/unauthorized
            // transition, rather than vanishing silently.
            auditService.recordFailure(txId, actor.userId(), role, EscrowEvent.OPEN_DISPUTE, previous,
                    "Evidence deposit failed: " + ex.getMessage());
            throw ex;
        }

        publishAfterCommit(tx, previous, next, EscrowEvent.OPEN_DISPUTE, actor.userId());

        return new DisputeOpenedDto(toDto(tx, resolveParties(tx)),
                evidence.stream().map(EvidenceDto::from).toList());
    }

    @Transactional(readOnly = true)
    public TransactionDetailDto getDetail(AuthPrincipal actor, Long txId) {
        EscrowTransaction tx = transactions.findById(txId)
                .orElseThrow(ApiExceptions::transactionNotFound);
        // Membership check: a non-party gets the very same 404 the findById above
        // would have produced (Story 1.10), so the two cases are indistinguishable.
        // The resolved role is irrelevant for a read, so it is intentionally ignored.
        transactionAccess.resolveRole(actor, tx);
        // Hard cap the audit trail read (unsorted Pageable → LIMIT only; ordering
        // stays from the method name). Query DESC so the LIMIT keeps the MOST
        // RECENT rows — an ASC limit would keep the oldest and silently drop the
        // recent tail — then reverse in memory (≤ MAX_LIST_RESULTS elements) to
        // restore the causal timestamp asc, id asc order. Same bound as the
        // evidence list.
        List<AuditLog> recent = new ArrayList<>(auditLogs.findByTransactionIdOrderByTimestampDescIdDesc(
                txId, PageRequest.of(0, PlatformLimits.MAX_LIST_RESULTS)));
        Collections.reverse(recent);
        List<AuditLogDto> trail = recent.stream().map(AuditLogDto::from).toList();
        return new TransactionDetailDto(toDto(tx, resolveParties(tx)), trail);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> listForUser(AuthPrincipal actor) {
        List<EscrowTransaction> txs = transactions.findAllForParticipant(actor.userId());
        Map<Long, User> cache = new HashMap<>();
        return txs.stream().map(tx -> toDto(tx, resolveParties(tx, cache))).toList();
    }

    // --- helpers ---

    /**
     * Sharpens a terminal-state dispute rejection into {@code DISPUTE_ALREADY_RESOLVED}
     * when the audit trail proves this transaction was disputed before.
     *
     * <p>The state machine is pure, so it can only see that the transaction is over —
     * not <em>why</em>. That distinction matters to the caller: a dispute replayed
     * onto an arbitrated dispute is a different story from one replayed onto a
     * transaction that simply completed on delivery, and RELEASED covers both. Only
     * the durable audit trail can tell them apart, and reading it needs the
     * repository — which is why the fork lives in the state machine and the
     * refinement lives here.
     *
     * <p>The rejection path already writes to {@code audit_logs} (the
     * {@code recordFailure} above), so an indexed SELECT on the same table adds no
     * new dependency and no meaningful cost. Every other rejection passes through
     * untouched, message included.
     */
    private TransitionException refineTerminalRejection(Long txId, TransitionException ex) {
        if (ex.getCode() != ErrorCode.TRANSACTION_TERMINAL
                || !auditLogs.existsByTransactionIdAndNextState(txId, EscrowState.DISPUTED.name())) {
            return ex;
        }
        return new TransitionException(ErrorCode.DISPUTE_ALREADY_RESOLVED, ex.getMessage());
    }

    private void publishAfterCommit(EscrowTransaction tx, EscrowState previous, EscrowState next,
                                    EscrowEvent event, Long actorId) {
        events.publishEvent(new StateChangedEvent(
                tx.getId(), previous, next, event, actorId,
                tx.getAmount(), tx.getCurrency(), Instant.now()));
    }

    private Map<Long, User> resolveParties(EscrowTransaction tx) {
        return resolveParties(tx, new HashMap<>());
    }

    private Map<Long, User> resolveParties(EscrowTransaction tx, Map<Long, User> cache) {
        cache.computeIfAbsent(tx.getBuyerId(), id -> users.findById(id).orElse(null));
        cache.computeIfAbsent(tx.getSellerId(), id -> users.findById(id).orElse(null));
        return cache;
    }

    private TransactionDto toDto(EscrowTransaction tx, Map<Long, User> parties) {
        User buyer = parties.get(tx.getBuyerId());
        User seller = parties.get(tx.getSellerId());
        return new TransactionDto(
                tx.getId(),
                tx.getBuyerId(),
                tx.getSellerId(),
                buyer == null ? null : buyer.getEmail(),
                seller == null ? null : seller.getEmail(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getState(),
                tx.getDescription(),
                tx.getCreatedAt(),
                tx.getUpdatedAt());
    }
}
