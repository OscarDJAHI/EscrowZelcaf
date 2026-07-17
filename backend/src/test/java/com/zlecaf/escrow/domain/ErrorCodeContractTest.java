package com.zlecaf.escrow.domain;

import com.zlecaf.escrow.domain.ErrorCode.Retryability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the authoritative error vocabulary. The offline replay queue (AD-10)
 * mirrors these names and this partition on the client; nothing in the type
 * system connects the two copies, so a rename or a reclassification here would
 * otherwise drift the client into silently misclassifying rejections — retrying
 * a permanently doomed request forever, or dropping a recoverable one.
 *
 * <p>This test is that connection. It is meant to fail on any change, so that the
 * change is a deliberate, coordinated act rather than an accident.
 */
class ErrorCodeContractTest {

    /** The nine permanent codes AD-10 names: the queue must never retry these. */
    private static final Set<ErrorCode> AD10_PERMANENT = EnumSet.of(
            ErrorCode.DISPUTE_ALREADY_RESOLVED,
            ErrorCode.TRANSACTION_TERMINAL,
            ErrorCode.WINDOW_CLOSED,
            ErrorCode.EVIDENCE_INVALID,
            ErrorCode.NOT_A_PARTY,
            ErrorCode.TRANSACTION_NOT_FOUND,
            ErrorCode.EVIDENCE_FLOOR_VIOLATION,
            ErrorCode.COMMENT_TOO_SHORT,
            ErrorCode.TOO_MANY_FILES);

    /** Every code the queue is allowed to replay. Exhaustive by construction below. */
    private static final Set<ErrorCode> TRANSIENT = EnumSet.of(
            ErrorCode.CONCURRENT_MODIFICATION,
            ErrorCode.FILE_READ_ERROR,
            ErrorCode.STORAGE_UNAVAILABLE);

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every code declares a retryability — there is no implicit default")
    void everyCodeIsClassified(ErrorCode code) {
        assertThat(code.retryability()).isNotNull();
    }

    @Test
    @DisplayName("The nine codes AD-10 calls permanent are PERMANENT")
    void ad10PermanentCodesArePermanent() {
        assertThat(AD10_PERMANENT).hasSize(9);
        assertThat(AD10_PERMANENT).allSatisfy(code ->
                assertThat(code.retryability()).isEqualTo(Retryability.PERMANENT));
    }

    @Test
    @DisplayName("Exactly CONCURRENT_MODIFICATION, FILE_READ_ERROR and STORAGE_UNAVAILABLE are TRANSIENT")
    void transientPartitionIsExact() {
        Set<ErrorCode> actual = Arrays.stream(ErrorCode.values())
                .filter(c -> c.retryability() == Retryability.TRANSIENT)
                .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ErrorCode.class)));
        // Exact, not a superset: a new code silently landing in TRANSIENT would make
        // the client retry something it has never been taught to retry.
        assertThat(actual).isEqualTo(TRANSIENT);
    }

    @Test
    @DisplayName("FILE_READ_ERROR is TRANSIENT although it is served under a 400 — the code is not the status")
    void codeIsIndependentOfStatus() {
        // The whole reason this enum exists: EVIDENCE_INVALID and FILE_READ_ERROR are
        // both 400s, yet one must never be retried and the other should be. A client
        // classifying on the HTTP status cannot tell them apart.
        assertThat(ErrorCode.FILE_READ_ERROR.retryability()).isEqualTo(Retryability.TRANSIENT);
        assertThat(ErrorCode.EVIDENCE_INVALID.retryability()).isEqualTo(Retryability.PERMANENT);
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("Every code name is SCREAMING_SNAKE_CASE and leaks no internal detail")
    void namesAreStableTokens(ErrorCode code) {
        assertThat(code.name()).matches("[A-Z][A-Z0-9]*(_[A-Z0-9]+)*");
    }

    @Test
    @DisplayName("The partner auth failures share ONE opaque code: no key-id enumeration oracle")
    void partnerAuthHasExactlyOneCode() {
        // Anti-enumeration is the point: unknown key, inactive key, bad signature,
        // stale timestamp and replayed nonce all answer AUTH_FAILED. Any code named
        // after a specific check would hand an attacker the oracle the uniform 401
        // message deliberately withholds.
        assertThat(Arrays.stream(ErrorCode.values())
                .map(ErrorCode::name)
                .filter(n -> n.contains("KEY") || n.contains("SIGNATURE")
                        || n.contains("NONCE") || n.contains("TIMESTAMP")))
                .isEmpty();
        assertThat(ErrorCode.AUTH_FAILED.retryability()).isEqualTo(Retryability.PERMANENT);
    }
}
