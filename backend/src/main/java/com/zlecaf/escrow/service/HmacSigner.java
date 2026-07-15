package com.zlecaf.escrow.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * HMAC-SHA256 helper for signing outbound webhook payloads. Partners verify the
 * {@code X-Escrow-Signature} header against the same shared secret, guaranteeing
 * authenticity and integrity of every state-change notification.
 */
public final class HmacSigner {

    private static final String ALGO = "HmacSHA256";

    private HmacSigner() {}

    /** Returns the lowercase hex HMAC-SHA256 of {@code payload} under {@code secret}. */
    public static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGO);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGO));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Unable to compute HMAC signature", e);
        }
    }

    /** Constant-time comparison to guard signature verification against timing attacks. */
    public static boolean verify(String payload, String secret, String providedSignature) {
        String expected = sign(payload, secret);
        return constantTimeEquals(expected, providedSignature);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
