package com.zlecaf.escrow.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zlecaf.escrow.domain.AuditLog;
import com.zlecaf.escrow.domain.EscrowEvent;
import com.zlecaf.escrow.domain.EscrowState;
import com.zlecaf.escrow.domain.EscrowTransaction;
import com.zlecaf.escrow.domain.ParticipantRole;
import com.zlecaf.escrow.repository.AuditLogRepository;
import com.zlecaf.escrow.repository.EscrowTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the immutable compliance audit trail. Successful transitions are logged
 * within the caller's transaction (atomic with the state change); rejected
 * attempts are logged in a <em>separate</em> transaction so the record survives
 * the rollback that the rejection triggers.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogs;
    private final EscrowTransactionRepository transactions;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository auditLogs, EscrowTransactionRepository transactions,
                        ObjectMapper objectMapper) {
        this.auditLogs = auditLogs;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    /** Log a committed transition; joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSuccess(Long transactionId, Long actorId, ParticipantRole actorRole,
                              EscrowEvent event, EscrowState previous, EscrowState next) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("outcome", "SUCCESS");
        // A null event denotes the genesis transition (contract creation).
        payload.put("event", event == null ? "CREATE" : event.name());
        payload.put("actorRole", actorRole.name());
        save(transactionId, actorId, previous, next, payload);
    }

    /**
     * Log a rejected transition in its own transaction so it is durably recorded
     * even though the caller is about to throw and roll back.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long transactionId, Long actorId, ParticipantRole actorRole,
                              EscrowEvent event, EscrowState current, String reason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("outcome", "REJECTED");
        payload.put("event", event.name());
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("reason", reason);
        // No state change on rejection: previous == next == current.
        save(transactionId, actorId, current, current, payload);
    }

    /**
     * Log an evidence deposit within the caller's transaction (atomic with the
     * {@code evidence_files} row). A deposit is not a state change, so
     * {@code previous == next == currentState}. The JSONB payload is schemaless:
     * no schema change is needed to carry the extra evidence context.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidenceAdded(Long transactionId, Long actorId, ParticipantRole actorRole,
                                    EscrowState currentState, Long evidenceId, String sha256,
                                    String clientCapturedAt) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "EVIDENCE_ADDED");
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("evidenceId", evidenceId);
        payload.put("sha256", sha256);
        if (clientCapturedAt != null && !clientCapturedAt.isBlank()) {
            payload.put("clientCapturedAt", clientCapturedAt);
        }
        save(transactionId, actorId, currentState, currentState, payload);
    }

    /**
     * Log an evidence withdrawal within the caller's transaction (atomic with the
     * {@code ACTIVE -> WITHDRAWN} flip on the {@code evidence_files} row). Like a
     * deposit, a withdrawal is not a state change, so
     * {@code previous == next == currentState}. The payload carries {@code evidenceId}
     * (which correlates with the {@code EVIDENCE_ADDED} entry that holds the
     * {@code sha256}) but no hash: withdrawal is a metadata-only operation and must
     * not read the storage binary to recompute one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidenceWithdrawn(Long transactionId, Long actorId, ParticipantRole actorRole,
                                        EscrowState currentState, Long evidenceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "EVIDENCE_WITHDRAWN");
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("evidenceId", evidenceId);
        save(transactionId, actorId, currentState, currentState, payload);
    }

    /**
     * Journalise le rejet d'une pièce par l'analyse antivirus à l'ingestion
     * (Story 1.8, NFR-P7), dans SA PROPRE transaction.
     *
     * <p><b>Pourquoi {@code REQUIRES_NEW} alors qu'AD-5 impose {@code MANDATORY}.</b>
     * AD-5 lie l'audit à l'action qu'il consigne pour qu'ils commitent ensemble. Ici
     * l'action <em>échoue</em> : la transaction de dépôt rollback, et un audit
     * {@code MANDATORY} s'en irait avec elle — l'exigence « l'événement est audité »
     * ne serait pas tenue. C'est exactement le motif de
     * {@link #recordFailure} : la story suit un précédent maison, elle n'invente pas
     * une exception à AD-5.
     *
     * <p>Aucun changement de schéma : l'action vit dans le {@code payload} JSONB
     * (AD-5, pas de colonne {@code action_type}). Le payload porte de quoi
     * reconnaître la pièce sans la conserver — nom <b>assaini</b> (basename, jamais
     * le chemin brut), empreinte SHA-256 des octets refusés, nom de signature — mais
     * <b>aucun octet du fichier</b> : rien n'est persisté d'un binaire infecté, ni
     * dans le stockage objet ni dans cette table à rétention ≥ 5 ans (AD-25).
     *
     * <p>Les accesseurs d'acteur sont ceux de {@code recordEvidenceAdded}, donc le
     * chemin partenaire l'utilise tel quel (acteur et rôle nuls, contexte porté par
     * la transaction).
     *
     * @param sanitizedFilename basename déjà assaini, {@code null} si le multipart
     *                          n'en portait aucun
     * @param signature         nom de la signature déclenchée : c'est ici qu'il sert
     *                          l'opérateur, la réponse HTTP le taisant délibérément
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEvidenceRejectedByScan(Long transactionId, Long actorId, ParticipantRole actorRole,
                                             EscrowState currentState, String sanitizedFilename,
                                             String sha256, String signature) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "EVIDENCE_REJECTED_MALWARE");
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("filename", sanitizedFilename);
        payload.put("sha256", sha256);
        payload.put("signature", signature);
        // État DURABLE, pas celui de l'appelant (revue 1.8). Sur la route de litige
        // composite, EscrowService bascule la transaction en DISPUTED AVANT de déposer
        // les pièces ; l'entité en mémoire porte donc DISPUTED, mais le rejet fait
        // rollback et la transaction reste dans son état antérieur. Auditer
        // l'instantané de l'appelant gravait un état jamais commité dans une table
        // append-only conservée >= 5 ans.
        //
        // Cette méthode étant en REQUIRES_NEW, sa transaction voit l'état COMMITÉ. Le
        // SELECT est non bloquant : sous MVCC Postgres, un lecteur simple n'attend pas
        // le SELECT ... FOR UPDATE que détient la transaction appelante.
        EscrowState durableState = transactions.findById(transactionId)
                .map(EscrowTransaction::getState)
                .orElse(currentState);
        // Un rejet n'est pas un changement d'état : previous == next.
        save(transactionId, actorId, durableState, durableState, payload);
    }

    /**
     * Log an evidence download within the caller's transaction (atomic with the
     * access authorization, MANDATORY so it never runs on its own). A download is
     * not a state change, so {@code previous == next == currentState}. The payload
     * carries {@code evidenceId} but no hash: download reads the binary to stream
     * it, not to attest it. Written only after a successful {@code storage.load},
     * so a storage failure rolls this back — no phantom download audit remains.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordEvidenceDownloaded(Long transactionId, Long actorId, ParticipantRole actorRole,
                                         EscrowState currentState, Long evidenceId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("action", "EVIDENCE_DOWNLOADED");
        payload.put("actorRole", actorRole == null ? null : actorRole.name());
        payload.put("evidenceId", evidenceId);
        save(transactionId, actorId, currentState, currentState, payload);
    }

    /**
     * Journalise un blocage anti-bruteforce (Story 1.3, AC1). Hors de toute
     * transaction metier : transaction_id, action_by et etats sont null (colonnes
     * nullables depuis V1) — le payload JSONB porte tout le contexte. REQUIRES_NEW :
     * l'appelant (filtre servlet) n'a aucune transaction en cours.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthRateLimited(String path, String clientIp) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", "AUTH_RATE_LIMITED");
        payload.put("path", path);
        payload.put("clientIp", clientIp);
        save(null, null, null, null, payload);
    }

    /**
     * Journalise un changement de mot de passe et/ou une révocation de sessions
     * (Story 1.6, revue). Hors de toute transaction métier : transaction_id et
     * états sont null, {@code action_by} porte le compte concerné et le payload
     * JSONB le contexte. MANDATORY : l'appelant est {@code AuthService}, déjà
     * transactionnel — l'audit DOIT retomber avec l'opération qu'il décrit (AD-5),
     * jamais survivre à son rollback.
     *
     * <p>Sur un produit à rétention 5 ans, « le mot de passe de ce compte a changé »
     * et « toutes ses sessions ont été tuées » sont exactement les événements à
     * reconstituer après incident ; ils ne laissaient aucune trace.
     *
     * @param event {@code PASSWORD_CHANGED} ou {@code SESSIONS_REVOKED}
     * @param reason origine de la révocation (logout, changement de mot de passe…)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordAccountSecurityEvent(String event, Long userId, String reason) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", event);
        payload.put("userId", userId);
        payload.put("reason", reason);
        save(null, userId, null, null, payload);
    }

    private void save(Long transactionId, Long actorId, EscrowState previous, EscrowState next, ObjectNode payload) {
        AuditLog logEntry = new AuditLog();
        logEntry.setTransactionId(transactionId);
        logEntry.setActionBy(actorId);
        logEntry.setPreviousState(previous == null ? null : previous.name());
        logEntry.setNextState(next == null ? null : next.name());
        logEntry.setPayload(payload);
        auditLogs.save(logEntry);

        // Story 11.4 (AC1) — « la recherche par cet identifiant restitue toutes les lignes
        // d'une même requête, ÉCRITURE D'AUDIT COMPRISE ».
        //
        // Avant cette story, ce service n'émettait AUCUN log : il persistait des lignes et
        // se taisait. L'écriture d'audit était donc invisible à toute recherche par
        // identifiant de corrélation, et l'AC restait intenable quel que soit le format des
        // logs. Une ligne ici suffit, parce que les six méthodes publiques du service
        // convergent toutes vers ce point.
        //
        // Placée APRÈS `auditLogs.save(...)` : on journalise ce qui est écrit, pas ce qu'on
        // s'apprête à écrire. Sur le chemin `recordFailure` (REQUIRES_NEW), la ligne est
        // émise pour une écriture qui survivra au rollback de l'appelant — c'est
        // précisément ce que cette propagation garantit.
        //
        // AUCUNE donnée sensible : identifiants techniques et noms d'états, jamais le
        // `payload` — il porte des motifs de rejet en texte libre, et un log n'est pas
        // l'endroit où les faire ressortir. Le `traceId` est ajouté par le MDC, pas ici.
        log.info("audit entry written transactionId={} actorId={} previousState={} nextState={}",
                transactionId, actorId, previous, next);
    }
}
