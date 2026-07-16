package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.EvidenceFile;
import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.domain.PartnerKeyNonce;
import com.zlecaf.escrow.repository.PartnerHmacKeyRepository;
import com.zlecaf.escrow.repository.PartnerKeyNonceRepository;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
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
            throw new UnauthorizedException(AUTH_FAILED);
        }

        return evidence;
    }

    /**
     * Single generic 401 reason for every partner authentication failure (unknown
     * or inactive key, bad signature, stale timestamp, replayed nonce). Keeping the
     * client-facing message uniform prevents key-id / key-status enumeration through
     * differentiated error text.
     */
    private static final String AUTH_FAILED = "Invalid partner credentials";
}
