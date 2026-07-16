package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

/**
 * Verifies the HMAC-SHA256 signature and freshness of a machine partner deposit
 * (Story 3.2). Authentication of the {@code /api/v1/partner/**} route is carried
 * entirely by this check — there is no JWT. The signed material is a
 * deterministic <em>canonical string</em> (not the raw multipart body, which is
 * fragile across boundaries and buffering): it binds the key-id, transaction id,
 * timestamp, nonce, a per-file content digest and the optional comment /
 * capture time. The digest — lowercase-hex SHA-256 of each file's bytes, joined
 * by {@code ,} in request order — fully covers the uploaded content and is
 * reconstructible server-side after parsing.
 */
@Component
public class PartnerSignatureVerifier {

    /** Generic 401 reason: never reveals which specific check failed. */
    private static final String AUTH_FAILED = "Invalid partner credentials";

    private final long toleranceSeconds;

    public PartnerSignatureVerifier(
            @Value("${escrow.partner.timestamp-tolerance-seconds:300}") long toleranceSeconds) {
        this.toleranceSeconds = toleranceSeconds;
    }

    /**
     * Builds the canonical signing string: UTF-8, fields joined by {@code \n} (LF)
     * in this exact, frozen order — {@code keyId}, {@code transactionId} (decimal),
     * {@code timestamp} (the raw header string), {@code nonce}, {@code contentDigest},
     * {@code comment ?? ""}, {@code clientCapturedAt ?? ""}. Static and side-effect
     * free so it can be reproduced by partners and pinned by unit tests.
     */
    public static String canonicalString(String keyId, Long transactionId, String timestamp,
                                         String nonce, List<MultipartFile> files,
                                         String comment, String clientCapturedAt) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add(keyId);
        joiner.add(String.valueOf(transactionId));
        joiner.add(timestamp);
        joiner.add(nonce);
        joiner.add(contentDigest(files));
        joiner.add(comment == null ? "" : comment);
        joiner.add(clientCapturedAt == null ? "" : clientCapturedAt);
        return joiner.toString();
    }

    /**
     * Verifies the request against {@code key}: recomputes the canonical string,
     * checks the signature in constant time, then checks the timestamp is within
     * {@code toleranceSeconds} of server time. Any failure is a {@code 401}.
     *
     * @throws UnauthorizedException signature mismatch, or timestamp unparseable /
     *                               outside the accepted window (401).
     */
    public void verify(PartnerHmacKey key, String signature, String timestamp, String nonce,
                       Long txId, List<MultipartFile> files, String comment, String clientCapturedAt) {
        // One generic reason for every failure below so the client cannot tell a
        // bad signature from a stale timestamp (which would confirm a captured
        // signature is otherwise valid).
        String canonical = canonicalString(key.getKeyId(), txId, timestamp, nonce, files, comment, clientCapturedAt);
        if (!HmacSigner.verify(canonical, key.getSecretKey(), signature)) {
            throw new UnauthorizedException(AUTH_FAILED);
        }
        long parsed;
        try {
            parsed = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException | NullPointerException e) {
            throw new UnauthorizedException(AUTH_FAILED);
        }
        if (Math.abs(nowEpochSeconds() - parsed) > toleranceSeconds) {
            throw new UnauthorizedException(AUTH_FAILED);
        }
    }

    /**
     * Server clock as epoch seconds. Isolated as a seam so a deterministic test can
     * override "now" without freezing the whole JVM clock.
     */
    protected long nowEpochSeconds() {
        return Instant.now().getEpochSecond();
    }

    /** Lowercase-hex SHA-256 of each file's bytes, joined by {@code ,} in request order. */
    private static String contentDigest(List<MultipartFile> files) {
        StringJoiner joiner = new StringJoiner(",");
        for (MultipartFile file : files) {
            joiner.add(sha256Hex(readBytes(file)));
        }
        return joiner.toString();
    }

    private static byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // The bytes cannot be read, so the signature cannot be reconstructed:
            // treat as an authentication failure rather than leaking an I/O 500.
            throw new UnauthorizedException(AUTH_FAILED);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JRE algorithm; its absence is unrecoverable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
