package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.ConfigurationException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** SDD 9.4, FR-SER-03: a known-answer test, key validation, and the rejection of every modified, wrong-key or malformed signature. */
class HmacSignerTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef-test-key";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void matchesTheRfc4231KnownAnswers() {
        // RFC 4231 test case 2
        HmacSigner jefe = HmacSigner.ofKeyBytesForTest(bytes("Jefe"));
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", jefe.signHex(bytes("what do ya want for nothing?")));
        // RFC 4231 test case 1: key = 20 bytes of 0x0b, data = "Hi There"
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", HmacSigner.ofKeyBytesForTest(key).signHex(bytes("Hi There")));
    }

    @Test
    void producesTheJavaMacOfTheUtf8KeyForAFixedInput() throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(bytes(KEY), "HmacSHA256"));
        byte[] expected = mac.doFinal(bytes("snapshot bytes"));
        assertEquals(HmacSigner.toHex(expected), new HmacSigner(KEY).signHex(bytes("snapshot bytes")));
    }

    @Test
    void verifiesTheRightSignatureAndRejectsEveryOneBitChange() {
        HmacSigner signer = new HmacSigner(KEY);
        byte[] data = bytes("some serialised orders");
        String signature = signer.signHex(data);
        assertTrue(signer.verifyHex(data, signature));
        assertTrue(signer.verifyHex(data, signature + "\n"));
        assertTrue(signer.verifyHex(data, signature + "\r\n"));
        for (int i = 0; i < data.length; i++) {
            byte[] changed = data.clone();
            changed[i] ^= 1;
            assertFalse(signer.verifyHex(changed, signature), "changed byte " + i);
        }
        for (int i = 0; i < signature.length(); i++) {
            char c = signature.charAt(i);
            char other = c == '0' ? '1' : '0';
            assertFalse(signer.verifyHex(data, signature.substring(0, i) + other + signature.substring(i + 1)), "changed digit " + i);
        }
    }

    @Test
    void aDifferentKeyDoesNotVerify() {
        byte[] data = bytes("orders");
        String signature = new HmacSigner(KEY).signHex(data);
        assertFalse(new HmacSigner(KEY + "x").verifyHex(data, signature));
    }

    @Test
    void malformedSignatureTextNeverVerifiesAndNeverThrows() {
        HmacSigner signer = new HmacSigner(KEY);
        byte[] data = bytes("orders");
        String good = signer.signHex(data);
        for (String bad : new String[] {null, "", "abc", good.substring(1), good + "0", good.toUpperCase(), good.replace(good.charAt(0), 'z'),
                good + "\n\n", " " + good, good + " "}) {
            assertFalse(signer.verifyHex(data, bad), String.valueOf(bad));
        }
        assertNull(HmacSigner.parseHex("not hex"));
        assertFalse(signer.verify(data, null));
        assertFalse(signer.verify(data, new byte[31]));
    }

    @Test
    void aMissingShortOrPlaceholderSecretIsRefusedAtStartup() {
        assertThrows(ConfigurationException.class, () -> new HmacSigner(null));
        assertThrows(ConfigurationException.class, () -> new HmacSigner("too short"));
        assertThrows(ConfigurationException.class, () -> new HmacSigner("CHANGE_ME_at_least_32_characters_long_secret"));
    }

    @Test
    void theKeyIsNotPartOfTheStringForm() {
        assertFalse(new HmacSigner(KEY).toString().contains(KEY));
    }
}
