package com.cloudkitchen.snapshot;

import com.cloudkitchen.exception.ConfigurationException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 over the exact bytes of a snapshot (SDD 9.4, FR-SER-03). The key is the UTF-8 bytes of
 * {@code snapshot.hmac.secret}; the constructor rejects a missing, short (under 32 characters) or placeholder secret,
 * so an application with a bad key does not start. The signature file holds the 64 lowercase hexadecimal characters
 * of the MAC, optionally followed by one line break; anything else is "malformed" and never verifies. Verification
 * compares the full MACs with {@link MessageDigest#isEqual}, which takes the same time wherever the first difference
 * is. A valid HMAC proves that the holder of the key wrote these bytes and that they are unchanged; it does
 * <em>not</em> encrypt them. The key is never logged and never part of {@link #toString()}.
 */
public final class HmacSigner {

    static final int MIN_SECRET_CHARS = 32;
    private static final String ALGORITHM = "HmacSHA256";
    private static final int HEX_LENGTH = 64;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final byte[] key;

    private HmacSigner(byte[] key) {
        this.key = key;
    }

    /** Known-answer tests need the exact key bytes of the RFC vectors, which are shorter than a configured secret may be. */
    static HmacSigner ofKeyBytesForTest(byte[] key) {
        return new HmacSigner(key.clone());
    }

    public HmacSigner(String secret) {
        if (secret == null || secret.length() < MIN_SECRET_CHARS) {
            throw new ConfigurationException("snapshot.hmac.secret must be at least " + MIN_SECRET_CHARS + " characters");
        }
        if (secret.startsWith("CHANGE_ME")) {
            throw new ConfigurationException("snapshot.hmac.secret still has the placeholder value from the example file");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] sign(byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    public String signHex(byte[] data) {
        return toHex(sign(data));
    }

    /** Constant-time comparison of the MAC of {@code data} with {@code expected}. */
    public boolean verify(byte[] data, byte[] expected) {
        return expected != null && MessageDigest.isEqual(sign(data), expected);
    }

    /** {@code false} for a malformed signature text (wrong length, upper case, non-hex) and for a mismatch. */
    public boolean verifyHex(byte[] data, String signatureText) {
        byte[] expected = parseHex(signatureText);
        return expected != null && verify(data, expected);
    }

    static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = HEX[(bytes[i] >> 4) & 0xF];
            out[2 * i + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    /** The bytes of a well-formed signature text, or {@code null}. */
    static byte[] parseHex(String text) {
        if (text == null) {
            return null;
        }
        String hex = text.endsWith("\r\n") ? text.substring(0, text.length() - 2) : text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        if (hex.length() != HEX_LENGTH) {
            return null;
        }
        byte[] out = new byte[HEX_LENGTH / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = digit(hex.charAt(2 * i));
            int lo = digit(hex.charAt(2 * i + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static int digit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    @Override
    public String toString() {
        return "HmacSigner[HmacSHA256]";
    }
}
