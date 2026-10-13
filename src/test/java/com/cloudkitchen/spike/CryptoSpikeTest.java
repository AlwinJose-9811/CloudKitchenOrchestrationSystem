package com.cloudkitchen.spike;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.junit.jupiter.api.Test;

/** Phase 1 spike S-16: PBKDF2WithHmacSHA256 is available and its cost at 600,000 iterations is measured here. */
class CryptoSpikeTest {

    private static final int ITERATIONS = 600_000;

    private static byte[] derive(char[] password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return factory.generateSecret(new PBEKeySpec(password, salt, ITERATIONS, 256)).getEncoded();
    }

    @Test
    void pbkdf2With600000IterationsWorksAndItsCostIsMeasured() throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        char[] password = "correct horse battery staple".toCharArray();
        derive(password, salt); // warm-up so the measurement is not dominated by class loading
        long start = System.nanoTime();
        byte[] first = derive(password, salt);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("SPIKE-RECORD S-16 PBKDF2WithHmacSHA256 iterations=" + ITERATIONS + " time_ms=" + millis);
        assertEquals(32, first.length);
        assertArrayEquals(first, derive(password, salt), "same input, same salt: same hash");
        byte[] otherSalt = salt.clone();
        otherSalt[0] ^= 1;
        assertFalse(MessageDigest.isEqual(first, derive(password, otherSalt)), "a different salt changes the hash");
        assertFalse(MessageDigest.isEqual(first, derive("wrong".toCharArray(), salt)));
        assertTrue(MessageDigest.isEqual(first, first.clone()), "constant-time comparison accepts equal arrays");
        assertTrue(millis < 10_000, "a login must not take 10 s on a developer machine, took " + millis + " ms");
    }

    @Test
    void parametersBelowTheMinimumAreRejectedByTheKeySpecWhenInvalid() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new PBEKeySpec("x".toCharArray(), new byte[16], 0, 256));
        assertEquals(StandardCharsets.UTF_8.name(), "UTF-8");
    }
}
