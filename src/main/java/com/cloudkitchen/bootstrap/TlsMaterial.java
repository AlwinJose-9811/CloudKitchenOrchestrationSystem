package com.cloudkitchen.bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A self-signed certificate and key for the embedded database server, created once and reused (Phase 8, P8-D5).
 *
 * <p>Without a configured certificate MariaDB 11.4 generates an RSA 4096 key pair at <em>every</em> start; the search for
 * the primes takes a random time (mean 7.7 s, up to 18 s measured, 0.3 s with a certificate) and sometimes outlasts the 30 s
 * that MariaDB4j waits for the server. Switching TLS off does not help: the MariaDB 11.4 client that MariaDB4j runs for the
 * first-start statements refuses a server without TLS. The certificate is created with the JDK's own {@code keytool}
 * (started from {@code java.home}), so nothing has to be installed. The application itself connects without TLS
 * ({@code sslMode=DISABLED}) over the loopback interface; the certificate only lets the server and its own client start.
 * If anything fails, {@link #ensure} returns empty and the server starts as before.
 */
final class TlsMaterial {

    private static final Logger LOG = LoggerFactory.getLogger(TlsMaterial.class);
    private static final String ALIAS = "ck-embedded-db";
    private static final Duration RENEW_BEFORE_EXPIRY = Duration.ofDays(30);

    /** Paths of the PEM files handed to the server as {@code --ssl-cert} and {@code --ssl-key}. */
    record PemFiles(Path cert, Path key) {
    }

    private TlsMaterial() {
    }

    static Optional<PemFiles> ensure(Path tlsDir) {
        return ensure(tlsDir, 3650);
    }

    /** {@code validityDays} is a parameter only so that a test can create a certificate that is about to expire. */
    static Optional<PemFiles> ensure(Path tlsDir, int validityDays) {
        Path cert = tlsDir.resolve("server-cert.pem");
        Path key = tlsDir.resolve("server-key.pem");
        try {
            if (Files.isRegularFile(cert) && Files.isRegularFile(key) && validForAWhile(cert) && keyBelongsTo(key, cert)) {
                return Optional.of(new PemFiles(cert, key));
            }
            create(tlsDir, cert, key, validityDays);
            return Optional.of(new PemFiles(cert, key));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            LOG.warn("No certificate for the embedded database could be created ({}); the server will generate its own at every start, which can be slow",
                    e.toString());
            return Optional.empty();
        }
    }

    private static boolean validForAWhile(Path cert) {
        try (var in = Files.newInputStream(cert)) {
            X509Certificate parsed = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            return parsed.getNotAfter().toInstant().isAfter(Instant.now().plus(RENEW_BEFORE_EXPIRY));
        } catch (IOException | GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * The key must be the private half of the certificate: the two files are written one after the other, so a crash between
     * them (or a file replaced by hand) could leave a pair the server would refuse to start with.
     */
    private static boolean keyBelongsTo(Path key, Path cert) {
        try {
            String body = Files.readString(key, StandardCharsets.US_ASCII).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            X509Certificate certificate;
            try (var in = Files.newInputStream(cert)) {
                certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
            }
            PrivateKey privateKey = KeyFactory.getInstance(certificate.getPublicKey().getAlgorithm())
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            byte[] probe = new byte[32];
            new SecureRandom().nextBytes(probe);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(probe);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(probe);
            return verifier.verify(signature);
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    private static void create(Path dir, Path cert, Path key, int validityDays) throws IOException, GeneralSecurityException {
        Files.createDirectories(dir);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException noPosix) {
            // e.g. Windows (unverified): the directory sits in the application's own data directory
        }
        Path keystore = Files.createTempFile(dir, "ck-", ".p12");
        Files.delete(keystore); // keytool creates the file itself
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        String password = HexFormat.of().formatHex(random);
        try {
            runKeytool(keystore, password, validityDays);
            KeyStore store = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(keystore)) {
                store.load(in, password.toCharArray());
            }
            PrivateKey privateKey = (PrivateKey) store.getKey(ALIAS, password.toCharArray());
            X509Certificate certificate = (X509Certificate) store.getCertificate(ALIAS);
            writePem(key, "PRIVATE KEY", privateKey.getEncoded(), true);
            writePem(cert, "CERTIFICATE", certificate.getEncoded(), false);
            LOG.info("Created a self-signed certificate for the embedded database in {}", dir);
        } finally {
            Files.deleteIfExists(keystore);
        }
    }

    /** Runs the JDK's own keytool; the temporary password travels in the environment, not on the command line. */
    private static void runKeytool(Path keystore, String password, int validityDays) throws IOException {
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Path executable = Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
        if (!Files.isExecutable(executable)) {
            throw new IOException("keytool is not available at " + executable);
        }
        ProcessBuilder builder = new ProcessBuilder(executable.toString(), "-genkeypair", "-alias", ALIAS, "-keyalg", "EC",
                "-keysize", "256", "-sigalg", "SHA256withECDSA", "-dname", "CN=localhost", "-validity", String.valueOf(validityDays),
                "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass:env", "CK_KEYTOOL_PASSWORD",
                "-keypass:env", "CK_KEYTOOL_PASSWORD");
        builder.environment().put("CK_KEYTOOL_PASSWORD", password);
        builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD); // its output is not needed and not logged
        Process process = builder.start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("keytool did not finish within 60 s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for keytool", e);
        }
        if (process.exitValue() != 0) {
            throw new IOException("keytool failed with exit code " + process.exitValue());
        }
    }

    private static void writePem(Path target, String type, byte[] der, boolean secret) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        Path temp = Files.createTempFile(target.getParent(), "ck-", ".tmp");
        try {
            if (secret) {
                try {
                    Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException noPosix) {
                    // e.g. Windows (unverified): the file sits in the application's own data directory
                }
            }
            Files.writeString(temp, pem, StandardCharsets.US_ASCII);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
