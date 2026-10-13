package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Uses the real minimum of 600,000 iterations on purpose: the tests must prove the locked parameter, not a cheaper one. */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();
    // A throw-away value that exists only inside this test; it is not a credential of the application.
    private static final char[] PASSWORD = "correct horse battery staple".toCharArray();
    private static final String STORED = new PasswordHasher().hash(PASSWORD);

    @Test
    void storedFormatHasFourPartsWithTheLockedParameters() {
        String[] parts = STORED.split("\\$");
        assertEquals(4, parts.length);
        assertEquals("pbkdf2_sha256", parts[0]);
        assertEquals("600000", parts[1]);
        assertEquals(16, Base64.getDecoder().decode(parts[2]).length, "16-byte salt");
        assertEquals(32, Base64.getDecoder().decode(parts[3]).length, "256-bit key");
    }

    @Test
    void theCorrectPasswordVerifiesAndAWrongOneDoesNot() {
        assertTrue(hasher.verify(PASSWORD, STORED));
        assertFalse(hasher.verify("wrong".toCharArray(), STORED));
        assertFalse(hasher.verify(new char[0], STORED));
        assertFalse(hasher.verify("Correct horse battery staple".toCharArray(), STORED), "case matters");
    }

    @Test
    void theSameConfiguredPasswordGivesADifferentHashEachTime() {
        String other = hasher.hash(PASSWORD);
        assertNotEquals(STORED, other, "fresh random salt");
        assertTrue(hasher.verify(PASSWORD, other));
    }

    @Test
    void theHashNeverContainsThePassword() {
        assertFalse(STORED.contains("correct horse"));
    }

    @Test
    void verifyDoesNotModifyTheCallersPassword() {
        char[] copy = PASSWORD.clone();
        hasher.verify(copy, STORED);
        assertEquals(new String(PASSWORD), new String(copy));
    }

    @Test
    void malformedStoredValuesAreNeverAMatch() {
        for (String bad : new String[] {null, "", "plain", "a$b$c$d", "pbkdf2_sha256$x$AAAA$AAAA", "pbkdf2_sha256$600000$AAAA",
                "pbkdf2_sha256$600000$!!!$AAAA", "pbkdf2_sha256$0$AAAA$AAAA", "pbkdf2_sha256$600000$$", "md5$600000$AAAA$AAAA"}) {
            assertFalse(hasher.verify(PASSWORD, bad), String.valueOf(bad));
            assertTrue(hasher.needsRehash(bad), "malformed values are replaced: " + bad);
        }
    }

    @Test
    void iterationsBelowTheMinimumAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordHasher(599_999));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(PASSWORD, 1000));
        assertThrows(IllegalArgumentException.class, () -> hasher.hash(PASSWORD, 0));
    }

    @Test
    void needsRehashWhenTheStoredCountIsLowerThanConfigured() {
        assertFalse(hasher.needsRehash(STORED));
        assertFalse(new PasswordHasher(600_000).needsRehash(STORED));
        assertTrue(new PasswordHasher(700_000).needsRehash(STORED));
        assertFalse(new PasswordHasher(600_000).needsRehash(hasher.hash(PASSWORD, 650_000)), "higher is fine");
    }

    @Test
    void theExplicitIterationOverloadIsRecordedInTheHashAndVerifies() {
        String higher = hasher.hash(PASSWORD, 610_000);
        assertEquals("610000", higher.split("\\$")[1]);
        assertTrue(hasher.verify(PASSWORD, higher));
    }

    @Test
    void constantTimeEqualsComparesContents() {
        assertTrue(PasswordHasher.constantTimeEquals(new byte[] {1, 2, 3}, new byte[] {1, 2, 3}));
        assertFalse(PasswordHasher.constantTimeEquals(new byte[] {1, 2, 3}, new byte[] {1, 2, 4}));
        assertFalse(PasswordHasher.constantTimeEquals(new byte[] {1, 2, 3}, new byte[] {1, 2}));
    }
}
