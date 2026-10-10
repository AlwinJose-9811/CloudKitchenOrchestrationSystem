package com.cloudkitchen.testsupport;

import com.cloudkitchen.bootstrap.AppContext;
import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.exception.CloudKitchenException;
import java.time.Clock;
import java.util.Properties;

/**
 * Builds whole {@link AppContext} instances on the shared embedded test database: the real wiring (workers, monitor,
 * recovery, sweeper, simulator, shutdown coordinator) with short simulated delays. The first accounts are seeded from
 * random one-time test credentials, never from a file.
 */
public final class AppFixture {

    private AppFixture() {
    }

    /** The properties of a fast test configuration; {@code overrides} are alternating keys and values. */
    public static Properties properties(DbFixture db, String... overrides) {
        Properties p = db.properties();
        p.setProperty("worker.prep.ms", "0");
        p.setProperty("worker.pack.ms", "0");
        p.setProperty("worker.delivery.ms", "0");
        p.setProperty("shutdown.drain.seconds", "5");
        p.setProperty("shutdown.await.seconds", "5");
        p.setProperty("simulator.max.rate.per.minute", "6000");
        p.setProperty("bootstrap.admin.username", "admin");
        p.setProperty("bootstrap.admin.password", TestDatabase.randomHex(16));
        p.setProperty("bootstrap.staff.username", "staff");
        p.setProperty("bootstrap.staff.password", TestDatabase.randomHex(16));
        for (int i = 0; i < overrides.length; i += 2) {
            p.setProperty(overrides[i], overrides[i + 1]);
        }
        return p;
    }

    /** A running application context on the test database; the caller closes it. */
    public static AppContext start(DbFixture db, boolean jvmHook, String... overrides) throws CloudKitchenException {
        AppConfig config = AppConfig.fromProperties(properties(db, overrides));
        return AppContext.create(config, db.jdbcUrl(), Clock.systemUTC(), null, jvmHook);
    }
}
