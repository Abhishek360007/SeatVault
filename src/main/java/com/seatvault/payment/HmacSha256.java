package com.seatvault.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 as Razorpay uses it: lowercase hex of HMAC(secret, message), compared
 * in constant time so a forged signature can't be found byte-by-byte from response
 * timings.
 */
public final class HmacSha256 {

    private HmacSha256() {
    }

    public static String hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /** True if {@code signature} equals HMAC(secret, message); false for any null input. */
    public static boolean matches(String secret, String message, String signature) {
        if (secret == null || secret.isBlank() || message == null || signature == null) {
            return false;
        }
        byte[] expected = hex(secret, message).getBytes(StandardCharsets.UTF_8);
        byte[] actual = signature.trim().toLowerCase().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
