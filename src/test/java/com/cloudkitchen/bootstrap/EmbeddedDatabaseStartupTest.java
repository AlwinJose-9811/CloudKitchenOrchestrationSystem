package com.cloudkitchen.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.testsupport.TestDatabase;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 defect P8-D5. A start time cannot be asserted without becoming a flaky test, so the test asserts the cause: the
 * server is started with the certificate of {@link TlsMaterial}, so it does not generate an RSA 4096 key pair, and it still
 * offers TLS (the MariaDB 11.4 client used for the first-start statements insists on that).
 */
class EmbeddedDatabaseStartupTest {

    @Test
    void theServerIsStartedWithAPreparedCertificateAndStillOffersTls() throws Exception {
        DbBootstrap.Settings settings = TestDatabase.newSettings("tls");
        try (DbBootstrap server = DbBootstrap.start(settings)) {
            List<ProcessHandle> processes = TestDatabase.serverProcessesForPort(settings.port());
            assertEquals(1, processes.size());
            String commandLine = processes.get(0).info().commandLine().orElse("");
            String tls = settings.dataDir().getParent().resolve("tls").toString();
            assertTrue(commandLine.contains("--ssl-cert=" + tls) && commandLine.contains("--ssl-key=" + tls), commandLine);
            assertTrue(Files.isRegularFile(settings.dataDir().getParent().resolve("tls").resolve("server-cert.pem")));
            String url = "jdbc:mysql://" + server.host() + ":" + server.port() + "/" + server.database() + "?sslMode=DISABLED";
            try (Connection c = DriverManager.getConnection(url, settings.appUser(), settings.appPassword());
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SHOW VARIABLES LIKE 'have_ssl'")) {
                rs.next();
                assertEquals("YES", rs.getString(2));
            }
        } finally {
            TestDatabase.deleteTree(settings.dataDir().getParent());
        }
    }
}
