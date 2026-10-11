package com.cloudkitchen.bootstrap;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.dao.jdbc.JdbcMenuItemDao;
import com.cloudkitchen.dao.jdbc.JdbcOrderDao;
import com.cloudkitchen.dao.jdbc.JdbcStaffUserDao;
import com.cloudkitchen.dao.jdbc.JdbcStationDao;
import com.cloudkitchen.dao.jdbc.JdbcStatusHistoryDao;
import com.cloudkitchen.dao.jdbc.JdbcStockDao;
import com.cloudkitchen.db.DataSourceFactory;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.NoOpOrderEventLog;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.pipeline.FeedSimulator;
import com.cloudkitchen.pipeline.InFlightRegistry;
import com.cloudkitchen.pipeline.MetricsRegistry;
import com.cloudkitchen.pipeline.OrderOrchestrator;
import com.cloudkitchen.pipeline.OrphanSweeper;
import com.cloudkitchen.pipeline.PipelineSettings;
import com.cloudkitchen.pipeline.RecoveryService;
import com.cloudkitchen.pipeline.ShutdownCoordinator;
import com.cloudkitchen.service.DashboardService;
import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.service.OrderQueryService;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.OrderValidator;
import com.cloudkitchen.service.PasswordHasher;
import com.cloudkitchen.service.RoutingService;
import com.cloudkitchen.service.StockService;
import com.cloudkitchen.service.SubmissionHook;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The manual composition of the whole application (SDD 2.4): pool, schema, first accounts, services, the station
 * workers, the simulator, recovery, the sweeper and the shutdown coordinator. Creating it either yields a running
 * application that refuses orders until recovery has re-queued the unfinished ones, or throws after cleaning up what
 * was already started.
 *
 * <p>Lifecycle: {@link #create} builds everything and starts recovery on the {@code recovery-loader} thread; when that
 * has handed every recoverable order back, the sweeper starts and the application begins to accept orders
 * ({@link #awaitReady}). {@link #close} (called by the servlet listener) and the JVM hook installed here both run
 * {@link ShutdownCoordinator#shutdown()}, which executes its steps once whoever calls first.
 */
public final class AppContext implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AppContext.class);

    private final HikariDataSource pool;
    private final Database database;
    private final OrderQueryService orderQueries;
    private final MetricsRegistry metrics;
    private final OrderService orderService;
    private final OrderOrchestrator orchestrator;
    private final FeedSimulator simulator;
    private final RecoveryService recovery;
    private final OrphanSweeper sweeper;
    private final DashboardService dashboard;
    private final MenuService menu;
    private final StockService stock;
    private final ShutdownCoordinator shutdown;
    private final CountDownLatch ready;

    private AppContext(HikariDataSource pool, Database database, OrderQueryService orderQueries, MetricsRegistry metrics,
                       OrderService orderService, OrderOrchestrator orchestrator, FeedSimulator simulator,
                       RecoveryService recovery, OrphanSweeper sweeper, DashboardService dashboard, MenuService menu,
                       StockService stock, ShutdownCoordinator shutdown, CountDownLatch ready) {
        this.pool = pool;
        this.database = database;
        this.orderQueries = orderQueries;
        this.metrics = metrics;
        this.orderService = orderService;
        this.orchestrator = orchestrator;
        this.simulator = simulator;
        this.recovery = recovery;
        this.sweeper = sweeper;
        this.dashboard = dashboard;
        this.menu = menu;
        this.stock = stock;
        this.shutdown = shutdown;
        this.ready = ready;
    }

    /** As {@link #create(AppConfig, String, Clock, AutoCloseable, boolean)} with the JVM hook and no extra resource. */
    public static AppContext create(AppConfig config, String jdbcUrl, Clock clock) throws CloudKitchenException {
        return create(config, jdbcUrl, clock, null, true);
    }

    /**
     * @param ownedResource a resource whose life this context ends last (an embedded database started by the caller of a
     *                      standalone run); {@code null} when the caller owns it (the embedded launcher stops it itself)
     * @param installJvmHook whether to register the JVM shutdown hook that runs the same coordinator
     * @throws CloudKitchenException if the database cannot be reached or a script fails
     */
    public static AppContext create(AppConfig config, String jdbcUrl, Clock clock, AutoCloseable ownedResource, boolean installJvmHook)
            throws CloudKitchenException {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(clock, "clock");
        HikariDataSource pool = DataSourceFactory.create(config, jdbcUrl);
        ShutdownCoordinator coordinator = new ShutdownCoordinator(
                Duration.ofSeconds(config.shutdownDrainSeconds() + config.shutdownAwaitSeconds() + 10L));
        OrderOrchestrator[] started = new OrderOrchestrator[1];
        try {
            Database database = new Database(pool, clock, config.txRetryMax(), config.txRetryBackoffMs());
            var applied = new SchemaInitializer(database, clock).apply();
            LOG.info("Database schema is current (scripts applied by this start: {})", applied);
            int created = new UserSeeder(database, new JdbcStaffUserDao(clock), new PasswordHasher(config.pbkdf2Iterations())).seedIfEmpty(config);
            LOG.info("Initial accounts created by this start: {}", created);

            MetricsRegistry metrics = new MetricsRegistry();
            InFlightRegistry inFlight = new InFlightRegistry();
            OrderEventLog events = new NoOpOrderEventLog(); // the file log arrives in Phase 6
            JdbcOrderDao orderDao = new JdbcOrderDao(clock, Duration.ofMillis(config.workerLeaseMarginMs()));
            JdbcStockDao stockDao = new JdbcStockDao(clock);
            JdbcMenuItemDao menuDao = new JdbcMenuItemDao(clock);
            JdbcStationDao stationDao = new JdbcStationDao();
            RoutingService routing = new RoutingService();
            routing.refresh(database.withConnection(stationDao::findAllActive));

            OrderOrchestrator orchestrator = OrderOrchestrator.from(config, routing, clock, events, metrics);
            started[0] = orchestrator;
            OrderService orderService = new OrderService(database, orderDao, stockDao, new JdbcStatusHistoryDao(), menuDao,
                    new OrderValidator(), routing, orchestrator, inFlight, metrics, events, clock, SubmissionHook.NO_OP,
                    OrderService.Settings.from(config));
            orchestrator.start(orderService);

            OrderQueryService queries = new OrderQueryService(database, orderDao);
            MenuService menu = new MenuService(database, menuDao, stockDao, stationDao);
            StockService stock = new StockService(database, stockDao);
            FeedSimulator simulator = new FeedSimulator(orderService, FeedSimulator.randomGenerator(routing, menu, new Random()),
                    config.simulatorDefaultRatePerMinute(), config.simulatorMaxRatePerMinute(), clock, events);
            RecoveryService recovery = new RecoveryService(orderService, orchestrator, events, RecoveryService.SnapshotStep.NONE);
            OrphanSweeper sweeper = new OrphanSweeper(orderService, orchestrator, recovery, OrphanSweeper.Settings.from(config), clock, events);
            DashboardService dashboard = new DashboardService(queries, stock, orderService, orchestrator::stationViews, metrics::view,
                    simulator::isRunning, simulator::getRate, recovery::isRecovering);

            registerShutdownSteps(coordinator, config, orderService, recovery, simulator, sweeper, orchestrator, events, pool, ownedResource);
            if (installJvmHook) {
                coordinator.installJvmHook();
            }
            CountDownLatch ready = new CountDownLatch(1);
            AppContext app = new AppContext(pool, database, queries, metrics, orderService, orchestrator, simulator, recovery,
                    sweeper, dashboard, menu, stock, coordinator, ready);
            recovery.start(() -> {
                if (coordinator.isStarted()) {
                    return; // the application is already shutting down: start nothing
                }
                sweeper.start();
                orderService.setAccepting(true);
                if (config.simulatorAutostart()) {
                    simulator.start(config.simulatorDefaultRatePerMinute());
                }
                LOG.info("Cloud Kitchen is accepting orders");
                ready.countDown();
            });
            return app;
        } catch (CloudKitchenException | RuntimeException | Error e) {
            coordinator.register("stop the workers after a failed start", () -> {
                if (started[0] != null) {
                    started[0].shutdownGracefully(Duration.ZERO, Duration.ofSeconds(2));
                }
                return null;
            });
            coordinator.register("close the pool after a failed start", () -> {
                pool.close();
                return null;
            });
            coordinator.shutdown();
            throw e;
        }
    }

    /** The ordered steps of SDD 7.6; each is bounded by its own timeout. */
    private static void registerShutdownSteps(ShutdownCoordinator coordinator, AppConfig config, OrderService orderService,
                                              RecoveryService recovery, FeedSimulator simulator, OrphanSweeper sweeper, OrderOrchestrator orchestrator,
                                              OrderEventLog events, HikariDataSource pool, AutoCloseable ownedResource) {
        coordinator.register("stop recovery", () -> {
            recovery.stop(); // first: nothing the recovery thread still starts can outlive the later steps
            return null;
        });
        coordinator.register("stop accepting orders", () -> {
            orderService.setAccepting(false);
            return null;
        });
        coordinator.register("stop the feed simulator", () -> simulator.shutdown() ? null
                : "the simulator thread is still inside a blocked submission; it was interrupted and ends when that call returns, the shutdown went on");
        coordinator.register("stop the sweeper and the worker monitor", () -> {
            sweeper.stop();
            if (orchestrator.monitor() != null) {
                orchestrator.monitor().stop();
            }
            return null;
        });
        coordinator.register("drain and stop the workers", () -> {
            OrderOrchestrator.StopOutcome how = orchestrator.stop(Duration.ofSeconds(config.shutdownDrainSeconds()),
                    Duration.ofSeconds(config.shutdownAwaitSeconds()));
            return switch (how) {
                case DRAINED -> "queues drained and all workers ended";
                case STOPPED -> "drain time elapsed, workers stopped after their current order; queued orders stay recoverable in the database";
                case FORCED -> "workers were still busy after the drain and await times and were interrupted; unfinished orders stay recoverable in the database";
                case NOT_TERMINATED -> "some worker threads did not end even after the interrupt; unfinished orders stay recoverable in the database";
                case INTERRUPTED -> "the shutdown caller was interrupted while waiting; the workers were interrupted without waiting for them to end, so it is not known that they ended; unfinished orders stay recoverable in the database";
            };
        });
        // the final snapshot of in-flight orders is added by Phase 6 here
        coordinator.register("close the connection pool", () -> {
            pool.close();
            return null;
        });
        coordinator.register("close the event log", () -> {
            events.close();
            return null;
        });
        if (ownedResource != null) {
            coordinator.register("stop the embedded database", () -> {
                ownedResource.close();
                return null;
            });
        }
    }

    public Database database() {
        return database;
    }

    public OrderQueryService orderQueries() {
        return orderQueries;
    }

    public OrderService orders() {
        return orderService;
    }

    public OrderOrchestrator orchestrator() {
        return orchestrator;
    }

    public FeedSimulator simulator() {
        return simulator;
    }

    public MetricsRegistry metrics() {
        return metrics;
    }

    public RecoveryService recovery() {
        return recovery;
    }

    public OrphanSweeper sweeper() {
        return sweeper;
    }

    public DashboardService dashboard() {
        return dashboard;
    }

    public MenuService menu() {
        return menu;
    }

    public StockService stock() {
        return stock;
    }

    public ShutdownCoordinator shutdownCoordinator() {
        return shutdown;
    }

    /** Waits until recovery finished and the application accepts orders; {@code false} on timeout (or if recovery failed). */
    public boolean awaitReady(long timeout, TimeUnit unit) throws InterruptedException {
        return ready.await(timeout, unit);
    }

    /** Runs the shutdown coordinator (once, however often and from however many threads this is called). */
    @Override
    public void close() {
        shutdown.shutdown();
    }
}
