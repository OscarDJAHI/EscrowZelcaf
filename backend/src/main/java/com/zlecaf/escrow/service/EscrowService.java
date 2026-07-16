package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.*;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import com.zlecaf.escrow.repository.UserRepository;
import com.zlecaf.escrow.security.AuthPrincipal;
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
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));

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
            throw new BadRequestException("A comment of at least 10 characters is required to open a dispute");
        }
        // Same shared file cap as a plain deposit, enforced BEFORE the state
        // transition (and before any byte buffering) so an over-plafond batch
        // never leaves a half-open dispute.
        if (files.size() > PlatformLimits.MAX_FILES_PER_DEPOSIT) {
            throw new BadRequestException(
                    "A deposit accepts at most " + PlatformLimits.MAX_FILES_PER_DEPOSIT + " files");
        }

        EscrowTransaction tx = transactions.findByIdForUpdate(txId)
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));

        ParticipantRole role = transactionAccess.resolveRole(actor, tx);
        EscrowState previous = tx.getState();

        EscrowState next;
        try {
            next = stateMachine.determineNextState(previous, EscrowEvent.OPEN_DISPUTE, role);
        } catch (TransitionException ex) {
            // Durably record the rejected attempt (separate transaction), then reject.
            auditService.recordFailure(txId, actor.userId(), role, EscrowEvent.OPEN_DISPUTE, previous, ex.getMessage());
            throw ex;
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
                .orElseThrow(() -> new NotFoundException("Transaction " + txId + " not found"));
        // Membership check: throws ForbiddenException for non-parties. The
        // resolved role is irrelevant for a read, so it is intentionally ignored.
        transactionAccess.resolveRole(actor, tx);
        // Hard cap the audit trail read (unsorted Pageable → LIMIT only; ordering
        // stays from the method name). Same bound as the evidence list.
        List<AuditLogDto> trail = auditLogs.findByTransactionIdOrderByTimestampAscIdAsc(
                        txId, PageRequest.of(0, PlatformLimits.MAX_LIST_RESULTS))
                .stream().map(AuditLogDto::from).toList();
        return new TransactionDetailDto(toDto(tx, resolveParties(tx)), trail);
    }

    @Transactional(readOnly = true)
    public List<TransactionDto> listForUser(AuthPrincipal actor) {
        List<EscrowTransaction> txs = transactions.findAllForParticipant(actor.userId());
        Map<Long, User> cache = new HashMap<>();
        return txs.stream().map(tx -> toDto(tx, resolveParties(tx, cache))).toList();
    }

    // --- helpers ---

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
