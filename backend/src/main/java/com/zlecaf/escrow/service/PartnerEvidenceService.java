package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.PartnerKeyNonce;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.web.ApiExceptions.BadRequestException;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Orchestrates a signed machine-partner deposit (Story 3.2) atomically: resolve
 * the active key, verify the HMAC signature and timestamp, reject a replayed
 * nonce, ingest the evidence (reusing {@link EvidenceService}), then consume the
 * nonce — all in one transaction. Authentication is carried entirely by the
 * signature; the route is not JWT-protected.
 *
 * <p><strong>Atomicity of auth + deposit + nonce.</strong> The nonce is inserted
 * only after a successful ingest, so a rejected deposit (bad file, 403, 409)
 * never consumes the nonce and a legitimate retry with the same nonce stays
 * possible. Two concurrent requests carrying the same {@code (key-id, nonce)}
 * are arbitrated by the {@code uq_partner_key_nonces_key_nonce} constraint: the
 * loser's INSERT throws {@link DataIntegrityViolationException}, mapped to a 401
 * replay rejection.
 */
@Service
public class PartnerEvidenceService {

    private final PartnerHmacKeyRepository keyRepository;
    private final PartnerKeyNonceRepository nonceRepository;
    private final PartnerSignatureVerifier signatureVerifier;
    private final EvidenceService evidenceService;

    public PartnerEvidenceService(PartnerHmacKeyRepository keyRepository,
                                  PartnerKeyNonceRepository nonceRepository,
                                  PartnerSignatureVerifier signatureVerifier,
                                  EvidenceService evidenceService) {
        this.keyRepository = keyRepository;
        this.nonceRepository = nonceRepository;
        this.signatureVerifier = signatureVerifier;
        this.evidenceService = evidenceService;
    }

    /**
     * Authenticates and ingests a partner deposit.
     *
     * @return the persisted {@link EvidenceFile} rows (status {@code ACTIVE}).
     * @throws UnauthorizedException unknown/inactive key, bad signature, stale
     *                               timestamp, or replayed nonce (401).
     * @throws com.zlecaf.escrow.web.ApiExceptions.ForbiddenException company not a party (403)
     * @throws com.zlecaf.escrow.web.ApiExceptions.NotFoundException transaction unknown (404)
     * @throws com.zlecaf.escrow.web.ApiExceptions.ConflictException deposit window closed (409)
     * @throws com.zlecaf.escrow.web.ApiExceptions.BadRequestException invalid batch/file (400)
     */
    @Transactional
    public List<EvidenceFile> deposit(String keyId, String signature, String timestamp, String nonce,
                                      Long txId, List<MultipartFile> files, String comment,
                                      String clientCapturedAt) {
        // Fail-fast on a nonce that cannot fit the nonce column (VARCHAR(255)):
        // reject it BEFORE any signature verification or ingest, so an over-long
        // nonce never drives the (expensive) per-file hashing and never reaches a
        // flush-time truncation surfacing as a misleading 401/500.
        if (nonce != null && nonce.length() > MAX_NONCE_LENGTH) {
            throw new BadRequestException("Nonce exceeds the maximum allowed length");
        }

        // Only ACTIVE keys resolve, so revocation (active=false) is immediate. The
        // 401 message is deliberately generic (see AUTH_FAILED): distinguishing
        // "unknown key" from "bad signature" would let an attacker enumerate which
        // key-ids exist and are active.
        PartnerHmacKey key = keyRepository.findByKeyIdAndActiveTrue(keyId)
                .orElseThrow(() -> new UnauthorizedException(AUTH_FAILED));

        // Cap the batch BEFORE the verifier reads and SHA-256-hashes every file:
        // the key-id is a public identifier, so hashing must not be reachable by an
        // over-cap batch carrying a bogus signature.
        EvidenceService.requireDepositableBatch(files);

        signatureVerifier.verify(key, signature, timestamp, nonce, txId, files, comment, clientCapturedAt);

        // Fast replay probe. The unique constraint below is the real arbiter under a race.
        if (nonceRepository.existsByKeyIdAndNonce(keyId, nonce)) {
            throw new UnauthorizedException(AUTH_FAILED);
        }

        List<EvidenceFile> evidence =
                evidenceService.depositAsPartner(key.getCompanyId(), txId, files, comment, clientCapturedAt);

        // Consume the nonce only after a successful ingest, in the SAME transaction.
        // IDENTITY generation forces an immediate INSERT, so a lost race surfaces the
        // constraint violation here (not deferred to commit) — mapped to a 401 replay.
        try {
            nonceRepository.save(new PartnerKeyNonce(keyId, nonce));
        } catch (DataIntegrityViolationException e) {
            // ONLY the nonce unique-constraint conflict is a replay (401). Any other
            // integrity violation (a bug, an unrelated constraint) must NOT be masked
            // as an auth failure — it propagates and surfaces as a 500.
            if (isNonceReplay(e)) {
                throw new UnauthorizedException(AUTH_FAILED);
            }
            throw e;
        }

        return evidence;
    }

    /** {@code partner_key_nonces.nonce} column width (VARCHAR(255)). */
    private static final int MAX_NONCE_LENGTH = 255;

    /** Constraint whose violation identifies a genuine nonce replay (case-insensitive). */
    private static final String NONCE_UNIQUE_CONSTRAINT = "uq_partner_key_nonces_key_nonce";

    /**
     * True iff the integrity violation was caused by the nonce unique constraint.
     * Walks the ENTIRE cause chain for a Hibernate {@link ConstraintViolationException}
     * naming {@code uq_partner_key_nonces_key_nonce} (case-insensitively). The whole
     * chain is scanned — not stopped at the first {@code ConstraintViolationException}
     * — so a genuine nonce replay wrapped behind an unrelated integrity cause is still
     * recognised. A missing chain or null/other constraint name is treated as NOT a
     * replay (fail-safe: the violation propagates → 500); the real-DB concurrency test
     * proves a genuine nonce conflict IS detected here.
     */
    private static boolean isNonceReplay(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException cve
                    && NONCE_UNIQUE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Single generic 401 reason for every partner authentication failure (unknown
     * or inactive key, bad signature, stale timestamp, replayed nonce). Keeping the
     * client-facing message uniform prevents key-id / key-status enumeration through
     * differentiated error text.
     */
    private static final String AUTH_FAILED = "Invalid partner credentials";
}
