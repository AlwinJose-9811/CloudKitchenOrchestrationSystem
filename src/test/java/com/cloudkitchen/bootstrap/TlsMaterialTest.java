package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.TestDatabase;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Phase 8 defect P8-D5: the certificate that spares the embedded server its RSA 4096 key generation at every start. */
class TlsMaterialTest {

    private static Path scratch() throws Exception {
        Path parent = Path.of("data", "test").toAbsolutePath();
        Files.createDirectories(parent);
        return Files.createTempDirectory(parent, "tls-test-");
    }

    private static X509Certificate parse(Path pem) throws Exception {
        String body = Files.readString(pem, StandardCharsets.US_ASCII).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(body)));
    }

    @Test
    void aCertificateAndKeyAreCreatedOnceAndReused() throws Exception {
        Path dir = scratch();
        try {
            Path tls = dir.resolve("tls");
            Optional<TlsMaterial.PemFiles> first = TlsMaterial.ensure(tls);
            assertTrue(first.isPresent(), "created");
            X509Certificate certificate = parse(first.get().cert());
            assertEquals("EC", certificate.getPublicKey().getAlgorithm());
            assertEquals(256, ((java.security.interfaces.ECPublicKey) certificate.getPublicKey()).getParams().getOrder().bitLength(), "P-256");
            assertTrue(certificate.getNotAfter().toInstant().isAfter(Instant.now().plus(3000, ChronoUnit.DAYS)), "valid for about ten years");
            String key = Files.readString(first.get().key(), StandardCharsets.US_ASCII);
            assertTrue(key.startsWith("-----BEGIN PRIVATE KEY-----\n") && key.endsWith("-----END PRIVATE KEY-----\n"), "PKCS#8 PEM");
            if (Files.getFileStore(tls).supportsFileAttributeView("posix")) {
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(first.get().key())), "the key is private to the owner");
            }
            try (var listing = Files.list(tls)) {
                assertEquals(2, listing.count(), "no keystore or temporary file is left behind");
            }
            String certText = Files.readString(first.get().cert());
            Optional<TlsMaterial.PemFiles> second = TlsMaterial.ensure(tls);
            assertEquals(first, second);
            assertEquals(certText, Files.readString(second.get().cert()), "the second call reuses the files instead of creating new ones");
            assertEquals(key, Files.readString(second.get().key()));
        } finally {
            TestDatabase.deleteTree(dir);
        }
    }

    @Test
    void anUnreadableCertificateIsReplaced() throws Exception {
        Path dir = scratch();
        try {
            Path tls = dir.resolve("tls");
            TlsMaterial.PemFiles files = TlsMaterial.ensure(tls).orElseThrow();
            Files.writeString(files.cert(), "not a certificate\n");
            TlsMaterial.PemFiles again = TlsMaterial.ensure(tls).orElseThrow();
            assertEquals("EC", parse(again.cert()).getPublicKey().getAlgorithm(), "replaced by a valid certificate");
        } finally {
            TestDatabase.deleteTree(dir);
        }
    }

    @Test
    void whenNothingCanBeCreatedTheResultIsEmptyAndNothingIsThrown() throws Exception {
        Path dir = scratch();
        try {
            Path blocker = dir.resolve("file-in-the-way");
            Files.writeString(blocker, "x");
            assertFalse(TlsMaterial.ensure(blocker.resolve("tls")).isPresent(), "a directory cannot be created below a file: the server falls back to its own certificate");
        } finally {
            TestDatabase.deleteTree(dir);
        }
    }

    @Test
    void aKeyThatDoesNotBelongToTheCertificateIsReplacedTogetherWithIt() throws Exception {
        Path dir = scratch();
        try {
            Path tls = dir.resolve("tls");
            Path other = dir.resolve("other-tls");
            TlsMaterial.PemFiles files = TlsMaterial.ensure(tls).orElseThrow();
            TlsMaterial.PemFiles stranger = TlsMaterial.ensure(other).orElseThrow();
            String certBefore = Files.readString(files.cert());
            // a crash between the two writes, or a file replaced by hand: the key of another certificate
            Files.copy(stranger.key(), files.key(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            TlsMaterial.PemFiles again = TlsMaterial.ensure(tls).orElseThrow();
            assertTrue(!Files.readString(again.cert()).equals(certBefore), "a new pair was created");
            assertEquals(Files.readString(stranger.key()).equals(Files.readString(again.key())), false, "not the stranger's key");
            assertEquals(parse(again.cert()).getPublicKey().getAlgorithm(), "EC");
            assertEquals(second(tls), Files.readString(again.cert()), "and the new pair is reused from then on");
        } finally {
            TestDatabase.deleteTree(dir);
        }
    }

    private static String second(Path tls) throws Exception {
        return Files.readString(TlsMaterial.ensure(tls).orElseThrow().cert());
    }

    @Test
    void aCertificateThatIsAboutToExpireIsRenewed() throws Exception {
        Path dir = scratch();
        try {
            Path tls = dir.resolve("tls");
            TlsMaterial.PemFiles shortLived = TlsMaterial.ensure(tls, 10).orElseThrow();
            Instant expiry = parse(shortLived.cert()).getNotAfter().toInstant();
            assertTrue(expiry.isBefore(Instant.now().plus(11, ChronoUnit.DAYS)), "created with ten days");
            TlsMaterial.PemFiles renewed = TlsMaterial.ensure(tls).orElseThrow();
            assertTrue(parse(renewed.cert()).getNotAfter().toInstant().isAfter(Instant.now().plus(3000, ChronoUnit.DAYS)), "renewed for about ten years");
        } finally {
            TestDatabase.deleteTree(dir);
        }
    }

    @Test
    void theDirectoryAndTheKeyAreOnlyReadableByTheOwner() throws Exception {
        Path dir = scratch();
        try {
            Path tls = dir.resolve("tls");
            TlsMaterial.PemFiles files = TlsMaterial.ensure(tls).orElseThrow();
            if (Files.getFileStore(tls).supportsFileAttributeView("posix")) {
                assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tls)));
                assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(files.key())));
            }
        } finally {
            TestDatabase.deleteTree(dir);
        }
    }
}
