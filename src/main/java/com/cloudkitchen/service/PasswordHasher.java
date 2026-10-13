package com.cloudkitchen.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2WithHmacSHA256 password hashing (SDD section 12.1). Stored form:
 * {@code pbkdf2_sha256$<iterations>$<saltBase64>$<hashBase64>} with a 16-byte random salt and a 256-bit key, so the
 * iteration count can be raised later and old hashes are recognised by {@link #needsRehash}.
 */
public final class PasswordHasher {

    public static final int MIN_ITERATIONS = 600_000;
    private static final String PREFIX = "pbkdf2_sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final int iterations;
    private final SecureRandom random = new SecureRandom();

    public PasswordHasher() {
        this(MIN_ITERATIONS);
    }

    public PasswordHasher(int iterations) {
        this.iterations = requireIterations(iterations);
    }

    /** Hashes with this hasher's configured iteration count. */
    public String hash(char[] password) {
        return hash(password, iterations);
    }

    /** Hashes with an explicit iteration count (at least {@value #MIN_ITERATIONS}). */
    public String hash(char[] password, int iterationCount) {
        requireIterations(iterationCount);
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] key = derive(password, salt, iterationCount);
        try {
            return PREFIX + '$' + iterationCount + '$' + Base64.getEncoder().encodeToString(salt) + '$'
                    + Base64.getEncoder().encodeToString(key);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** True if the password matches; a malformed stored value is simply not a match. */
    public boolean verify(char[] password, String stored) {
        Parsed parsed = parse(stored);
        if (parsed == null) {
            return false;
        }
        byte[] actual = derive(password, parsed.salt, parsed.iterations);
        try {
            return constantTimeEquals(actual, parsed.key);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    /** True if the stored hash is malformed or uses fewer iterations than currently configured. */
    public boolean needsRehash(String stored) {
        Parsed parsed = parse(stored);
        return parsed == null || parsed.iterations < iterations;
    }

    /** Compares without leaking where the first difference is. */
    public static boolean constantTimeEquals(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private static int requireIterations(int value) {
        if (value < MIN_ITERATIONS) {
            throw new IllegalArgumentException("iterations must be at least " + MIN_ITERATIONS);
        }
        return value;
    }

    private static byte[] derive(char[] password, byte[] salt, int iterationCount) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterationCount, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is not available", e);
        } finally {
            spec.clearPassword();
        }
    }

    private static Parsed parse(String stored) {
        if (stored == null) {
            return null;
        }
        String[] parts = stored.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return null;
        }
        try {
            int count = Integer.parseInt(parts[1]);
            if (count < 1) {
                return null;
            }
            byte[] salt = Base64.getDecoder().decode(parts[2].getBytes(StandardCharsets.US_ASCII));
            byte[] key = Base64.getDecoder().decode(parts[3].getBytes(StandardCharsets.US_ASCII));
            return salt.length == 0 || key.length == 0 ? null : new Parsed(count, salt, key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private record Parsed(int iterations, byte[] salt, byte[] key) {
    }
}
