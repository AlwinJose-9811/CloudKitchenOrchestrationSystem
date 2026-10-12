package com.cloudkitchen.bootstrap;

import java.nio.file.Path;

/**
 * Test helper: a separate JVM that starts the embedded database for the given settings, prints {@code READY}, and waits to be
 * stopped or killed. Used by {@link DatabaseOwnershipTest} to get a real application process that can be killed with SIGKILL.
 * Arguments: base dir, data dir, tmp dir, socket, port, database, user, password.
 */
public final class DatabaseHolderMain {

    private DatabaseHolderMain() {
    }

    public static void main(String[] args) throws Exception {
        DbBootstrap.Settings settings = new DbBootstrap.Settings(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]),
                Integer.parseInt(args[4]), args[5], args[6], args[7]);
        DbBootstrap database = DbBootstrap.start(settings);
        Runtime.getRuntime().addShutdownHook(new Thread(database::close, "holder-shutdown-hook"));
        System.out.println("READY");
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
