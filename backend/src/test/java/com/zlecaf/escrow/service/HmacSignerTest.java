package com.zlecaf.escrow.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HmacSignerTest {

    private static final String SECRET = "partner-shared-secret";
    private static final String PAYLOAD = "{\"transactionId\":42,\"newState\":\"FUNDS_LOCKED\"}";

    @Test
    void signIsDeterministicAndHex() {
        String a = HmacSigner.sign(PAYLOAD, SECRET);
        String b = HmacSigner.sign(PAYLOAD, SECRET);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(64).matches("[0-9a-f]+"); // SHA-256 -> 32 bytes -> 64 hex chars
    }

    @Test
    void knownVectorMatchesRfcExpectation() {
        // Independently reproducible: HMAC-SHA256("The quick brown fox jumps over the lazy dog", "key")
        String sig = HmacSigner.sign("The quick brown fox jumps over the lazy dog", "key");
        assertThat(sig).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void verifyAcceptsValidSignatureAndRejectsTampering() {
        String sig = HmacSigner.sign(PAYLOAD, SECRET);
        assertThat(HmacSigner.verify(PAYLOAD, SECRET, sig)).isTrue();
        assertThat(HmacSigner.verify(PAYLOAD, "wrong-secret", sig)).isFalse();
        assertThat(HmacSigner.verify(PAYLOAD + "tampered", SECRET, sig)).isFalse();
        assertThat(HmacSigner.verify(PAYLOAD, SECRET, "deadbeef")).isFalse();
    }
}
