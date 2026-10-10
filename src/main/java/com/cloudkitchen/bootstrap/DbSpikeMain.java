package com.cloudkitchen.bootstrap;

import com.cloudkitchen.exception.CloudKitchenException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/** TEMPORARY Phase 1 spike: start the hardened embedded database under ./data, then stop it. Replaced by Launcher wiring. */
public final class DbSpikeMain {

    private DbSpikeMain() {
    }

    public static void main(String[] args) throws CloudKitchenException {
        Path data = Path.of("data").toAbsolutePath();
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        DbBootstrap.Settings settings = new DbBootstrap.Settings(data.resolve("runtime"), data.resolve("spike-db"),
                data.resolve("spike-tmp"), data.resolve("spike-run").resolve("db.sock"), 3307, "ckdb", "ck_app",
                HexFormat.of().formatHex(random));
        try (DbBootstrap db = DbBootstrap.start(settings)) {
            System.out.println("SPIKE started on " + db.host() + ":" + db.port());
        }
        System.out.println("SPIKE stopped cleanly");
    }
}
