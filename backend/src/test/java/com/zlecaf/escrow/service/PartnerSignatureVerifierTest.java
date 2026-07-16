package com.zlecaf.escrow.service;

import com.zlecaf.escrow.domain.PartnerHmacKey;
import com.zlecaf.escrow.web.ApiExceptions.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit proof of the signature/timestamp guard, with no Spring context. A fixed
 * "now" is injected by overriding {@link PartnerSignatureVerifier#nowEpochSeconds()}
 * so the ±tolerance window is deterministic. Every accepted case builds its own
 * expected signature via {@code HmacSigner.sign(canonicalString(...), secret)},
 * so the test and the production canonical string can never silently diverge.
 */
class PartnerSignatureVerifierTest {

    // >= 32 bytes, mirroring the V5 secret-length CHECK on partner_hmac_keys.
    private static final String SECRET = "partner-shared-secret-32bytes-min!!!";
    private static final long NOW = 1_700_000_000L;
    private static final long TOLERANCE = 300L;
    private static final String KEY_ID = "partner-key-1";
    private static final String NONCE = "nonce-abc-123";
    private static final Long TX_ID = 42L;
    private static final String COMMENT = "container sealed at gate";
    private static final String CAPTURED_AT = "2026-07-16T10:15:30+02:00";

    /** Verifier whose clock is pinned to {@link #NOW} for a deterministic window. */
    private PartnerSignatureVerifier verifier() {
        return new PartnerSignatureVerifier(TOLERANCE) {
            @Override
            protected long nowEpochSeconds() {
                return NOW;
            }
        };
    }

    private static PartnerHmacKey key() {
        PartnerHmacKey k = new PartnerHmacKey();
        k.setKeyId(KEY_ID);
        k.setCompanyId(7L);
        k.setSecretKey(SECRET);
        k.setActive(true);
        return k;
    }

    private static MockMultipartFile file(String name, String body) {
        return new MockMultipartFile("files", name, "application/pdf", body.getBytes());
    }

    private static String signFor(String timestamp, List<MultipartFile> files, String comment, String capturedAt) {
        return HmacSigner.sign(
                PartnerSignatureVerifier.canonicalString(KEY_ID, TX_ID, timestamp, NONCE, files, comment, capturedAt),
                SECRET);
    }

    @Test
    @DisplayName("A valid signature within the timestamp window is accepted")
    void validSignatureAccepted() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));
        String ts = String.valueOf(NOW);
        String sig = signFor(ts, files, COMMENT, CAPTURED_AT);

        assertThatCode(() -> verifier().verify(key(), sig, ts, NONCE, TX_ID, files, COMMENT, CAPTURED_AT))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Tampered file bytes break the signature -> 401")
    void tamperedFileBytesRejected() {
        List<MultipartFile> signed = List.of(file("proof.pdf", "original-bytes"));
        String ts = String.valueOf(NOW);
        String sig = signFor(ts, signed, COMMENT, CAPTURED_AT);

        // Same filename, different bytes: the content digest — and thus the signature — no longer matches.
        List<MultipartFile> tampered = List.of(file("proof.pdf", "TAMPERED-bytes"));
        assertThatThrownBy(() -> verifier().verify(key(), sig, ts, NONCE, TX_ID, tampered, COMMENT, CAPTURED_AT))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A tampered comment breaks the signature -> 401")
    void tamperedCommentRejected() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));
        String ts = String.valueOf(NOW);
        String sig = signFor(ts, files, COMMENT, CAPTURED_AT);

        assertThatThrownBy(() -> verifier().verify(key(), sig, ts, NONCE, TX_ID, files, "different comment", CAPTURED_AT))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A signature valid over a stale timestamp is still rejected on the window -> 401")
    void outOfWindowTimestampRejected() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));
        // Sign over the stale timestamp so the signature itself matches: only the
        // ±tolerance window may reject it, proving the timestamp guard runs.
        String staleTs = String.valueOf(NOW - (TOLERANCE + 1_000));
        String sig = signFor(staleTs, files, COMMENT, CAPTURED_AT);

        assertThatThrownBy(() -> verifier().verify(key(), sig, staleTs, NONCE, TX_ID, files, COMMENT, CAPTURED_AT))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("A timestamp exactly at each edge of the ±tolerance window is accepted")
    void timestampAtWindowBoundaryAccepted() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));

        // now + tolerance (future edge) and now - tolerance (past edge) both satisfy abs(diff) <= tolerance.
        for (String ts : List.of(String.valueOf(NOW + TOLERANCE), String.valueOf(NOW - TOLERANCE))) {
            String sig = signFor(ts, files, COMMENT, CAPTURED_AT);
            assertThatCode(() -> verifier().verify(key(), sig, ts, NONCE, TX_ID, files, COMMENT, CAPTURED_AT))
                    .as("timestamp %s is within the window", ts)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("A timestamp one second past either edge of the window is rejected -> 401")
    void timestampJustOutsideWindowRejected() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));

        // Sign over each just-out-of-window timestamp so only the window guard can reject it.
        for (String ts : List.of(String.valueOf(NOW + TOLERANCE + 1), String.valueOf(NOW - TOLERANCE - 1))) {
            String sig = signFor(ts, files, COMMENT, CAPTURED_AT);
            assertThatThrownBy(() -> verifier().verify(key(), sig, ts, NONCE, TX_ID, files, COMMENT, CAPTURED_AT))
                    .as("timestamp %s is outside the window", ts)
                    .isInstanceOf(UnauthorizedException.class);
        }
    }

    @Test
    @DisplayName("An unparseable timestamp is rejected -> 401")
    void unparseableTimestampRejected() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));
        String ts = "not-a-number";
        String sig = signFor(ts, files, COMMENT, CAPTURED_AT);

        assertThatThrownBy(() -> verifier().verify(key(), sig, ts, NONCE, TX_ID, files, COMMENT, CAPTURED_AT))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("canonicalString is deterministic and correctly shaped for a single file")
    void canonicalStringDeterministicSingleFile() {
        List<MultipartFile> files = List.of(file("proof.pdf", "the-bytes"));
        String a = PartnerSignatureVerifier.canonicalString(KEY_ID, TX_ID, "1700000000", NONCE, files, COMMENT, CAPTURED_AT);
        String b = PartnerSignatureVerifier.canonicalString(KEY_ID, TX_ID, "1700000000", NONCE, files, COMMENT, CAPTURED_AT);

        assertThat(a).isEqualTo(b);
        String[] parts = a.split("\n", -1);
        assertThat(parts).hasSize(7);
        assertThat(parts[0]).isEqualTo(KEY_ID);
        assertThat(parts[1]).isEqualTo("42");
        assertThat(parts[2]).isEqualTo("1700000000");
        assertThat(parts[3]).isEqualTo(NONCE);
        assertThat(parts[4]).matches("[0-9a-f]{64}"); // single-file digest = one sha256 hex
        assertThat(parts[5]).isEqualTo(COMMENT);
        assertThat(parts[6]).isEqualTo(CAPTURED_AT);
    }

    @Test
    @DisplayName("canonicalString joins per-file digests by ',' in request order for multiple files")
    void canonicalStringDeterministicMultiFile() {
        List<MultipartFile> files = List.of(file("a.pdf", "first-bytes"), file("b.pdf", "second-bytes"));
        String a = PartnerSignatureVerifier.canonicalString(KEY_ID, TX_ID, "1700000000", NONCE, files, null, null);
        String b = PartnerSignatureVerifier.canonicalString(KEY_ID, TX_ID, "1700000000", NONCE, files, null, null);

        assertThat(a).isEqualTo(b);
        String digest = a.split("\n", -1)[4];
        assertThat(digest).matches("[0-9a-f]{64},[0-9a-f]{64}"); // two hexes joined by a comma
        // Null comment / clientCapturedAt collapse to empty trailing fields.
        String[] parts = a.split("\n", -1);
        assertThat(parts[5]).isEmpty();
        assertThat(parts[6]).isEmpty();
    }
}
