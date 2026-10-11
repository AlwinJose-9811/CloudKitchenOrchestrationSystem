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
import com.cloudkitchen.exception.SnapshotWriteException;
import com.cloudkitchen.io.CsvReportExporter;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.NioOrderEventLog;
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
import com.cloudkitchen.service.ReportService;
import com.cloudkitchen.service.PasswordHasher;
import com.cloudkitchen.service.RoutingService;
import com.cloudkitchen.service.StockService;
import com.cloudkitchen.service.SubmissionHook;
import com.cloudkitchen.snapshot.HmacSigner;
import com.cloudkitchen.snapshot.SnapshotService;
import com.cloudkitchen.snapshot.SnapshotTask;
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
    private final NioOrderEventLog eventLog;
    private final SnapshotService snapshots;
    private final SnapshotTask snapshotTask;
    private final ReportService reports;
    private final CountDownLatch ready;

    private AppContext(HikariDataSource pool, Database database, OrderQueryService orderQueries, MetricsRegistry metrics,
                       OrderService orderService, OrderOrchestrator orchestrator, FeedSimulator simulator,
                       RecoveryService recovery, OrphanSweeper sweeper, DashboardService dashboard, MenuService menu,
                       StockService stock, ShutdownCoordinator shutdown, NioOrderEventLog eventLog, SnapshotService snapshots,
                       SnapshotTask snapshotTask, ReportService reports, CountDownLatch ready) {
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
        this.eventLog = eventLog;
        this.snapshots = snapshots;
        this.snapshotTask = snapshotTask;
        this.reports = reports;
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
        NioOrderEventLog[] openedLog = new NioOrderEventLog[1];
        try {
            Database database = new Database(pool, clock, config.txRetryMax(), config.txRetryBackoffMs());
            var applied = new SchemaInitializer(database, clock).apply();
            LOG.info("Database schema is current (scripts applied by this start: {})", applied);
            int created = new UserSeeder(database, new JdbcStaffUserDao(clock), new PasswordHasher(config.pbkdf2Iterations())).seedIfEmpty(config);
            LOG.info("Initial accounts created by this start: {}", created);

            MetricsRegistry metrics = new MetricsRegistry();
            InFlightRegistry inFlight = new InFlightRegistry();
            NioOrderEventLog events = new NioOrderEventLog(config.logOrdersPath(), clock);
            openedLog[0] = events;
            JdbcOrderDao orderDao = new JdbcOrderDao(clock, Duration.ofMillis(config.workerLeaseMarginMs()));
            JdbcStockDao stockDao = new JdbcStockDao(clock);
            JdbcStatusHistoryDao historyDao = new JdbcStatusHistoryDao();
            JdbcMenuItemDao menuDao = new JdbcMenuItemDao(clock);
            JdbcStationDao stationDao = new JdbcStationDao();
            RoutingService routing = new RoutingService();
            routing.refresh(database.withConnection(stationDao::findAllActive));

            OrderOrchestrator orchestrator = OrderOrchestrator.from(config, routing, clock, events, metrics);
            started[0] = orchestrator;
            OrderService orderService = new OrderService(database, orderDao, stockDao, historyDao, menuDao,
                    new OrderValidator(), routing, orchestrator, inFlight, metrics, events, clock, SubmissionHook.NO_OP,
                    OrderService.Settings.from(config));
            orchestrator.start(orderService);

            OrderQueryService queries = new OrderQueryService(database, orderDao);
            MenuService menu = new MenuService(database, menuDao, stockDao, stationDao);
            StockService stock = new StockService(database, stockDao);
            FeedSimulator simulator = new FeedSimulator(orderService, FeedSimulator.randomGenerator(routing, menu, new Random()),
                    config.simulatorDefaultRatePerMinute(), config.simulatorMaxRatePerMinute(), clock, events);
            SnapshotService snapshots = new SnapshotService(database, orderDao, historyDao, config.snapshotDir(),
                    new HmacSigner(config.snapshotHmacSecret()), config.snapshotMaxBytes(), config.snapshotRetainCount(), clock, events);
            SnapshotTask snapshotTask = new SnapshotTask(snapshots::writeAndPrune, config.snapshotIntervalSeconds(), clock);
            ReportService reports = new ReportService(database, orderDao, new CsvReportExporter(config.reportExportDir()));
            RecoveryService recovery = new RecoveryService(orderService, orchestrator, events, snapshots::recoverMissingOrders);
            OrphanSweeper sweeper = new OrphanSweeper(orderService, orchestrator, recovery, OrphanSweeper.Settings.from(config), clock, events);
            DashboardService dashboard = new DashboardService(queries, stock, orderService, orchestrator::stationViews, metrics::view,
                    simulator::isRunning, simulator::getRate, recovery::isRecovering);

            registerShutdownSteps(coordinator, config, orderService, recovery, simulator, sweeper, snapshotTask, snapshots, orchestrator, events, pool, ownedResource);
            if (installJvmHook) {
                coordinator.installJvmHook();
            }
            CountDownLatch ready = new CountDownLatch(1);
            AppContext app = new AppContext(pool, database, queries, metrics, orderService, orchestrator, simulator, recovery,
                    sweeper, dashboard, menu, stock, coordinator, events, snapshots, snapshotTask, reports, ready);
            recovery.start(() -> {
                if (coordinator.isStarted()) {
                    return; // the application is already shutting down: start nothing
                }
                sweeper.start();
                snapshotTask.start();
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
            coordinator.register("close the order log after a failed start", () -> {
                if (openedLog[0] != null) {
                    openedLog[0].close();
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
                                              RecoveryService recovery, FeedSimulator simulator, OrphanSweeper sweeper, SnapshotTask snapshotTask,
                                              SnapshotService snapshots, OrderOrchestrator orchestrator, OrderEventLog events,
                                              HikariDataSource pool, AutoCloseable ownedResource) {
        coordinator.register("stop recovery", () -> {
            events.log(EventType.SHUTDOWN_STARTED);
            recovery.stop(); // first: nothing the recovery thread still starts can outlive the later steps
            return null;
        });
        coordinator.register("stop accepting orders", () -> {
            orderService.setAccepting(false);
            return null;
        });
        coordinator.register("stop the feed simulator", () -> simulatorStopNote(simulator));
        coordinator.register("stop the sweeper, the worker monitor and the snapshot task", () -> {
            snapshotTask.stop();
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
        // best effort (SDD F-17): a snapshot that cannot be written is reported in the note and the shutdown goes on. With the
        // embedded database and a JVM shutdown hook (Ctrl+C, SIGTERM) the database server may already be gone: MariaDB4j
        // destroys its process in its own hook, and the order of JVM hooks is not defined (SDD 7.16.5)
        coordinator.register("write the final snapshot", () -> {
            try {
                return "written " + snapshots.writeAndPrune().getFileName();
            } catch (SnapshotWriteException e) {
                String why = chain(e);
                LOG.warn("The final snapshot was not written ({}); the database and the last periodic snapshot remain the recovery sources", why);
                return "NOT WRITTEN (" + why + "); the database and the last periodic snapshot remain the recovery sources";
            }
        });
        coordinator.register("close the connection pool", () -> {
            pool.close();
            return null;
        });
        coordinator.register("close the event log", () -> {
            events.log(EventType.SHUTDOWN_COMPLETED);
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

    /** Class names of a failure and its causes, never messages (they may carry data). */
    private static String chain(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        Throwable cause = failure.getCause();
        for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
            text.append(" <- ").append(cause.getClass().getSimpleName());
        }
        return text.toString();
    }

    /**
     * Stops the simulator and describes the result for the shutdown record, or {@code null} if its thread ended.
     * {@link FeedSimulator#shutdown()} also returns {@code false} when the calling thread was interrupted (and then sets
     * its flag again), so the flag tells the two causes apart: the coordinator clears it before every step, so inside
     * the step it can only have been set by an interrupt during this step.
     */
    static String simulatorStopNote(FeedSimulator simulator) {
        if (simulator.shutdown()) {
            return null;
        }
        if (Thread.currentThread().isInterrupted()) {
            return "the shutdown caller was interrupted while the simulator was stopping; it is not known whether the simulator thread ended, the shutdown went on";
        }
        return "the simulator thread is still inside a blocked submission; it was interrupted and ends when that call returns, the shutdown went on";
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

    public NioOrderEventLog eventLog() {
        return eventLog;
    }

    public SnapshotService snapshots() {
        return snapshots;
    }

    public SnapshotTask snapshotTask() {
        return snapshotTask;
    }

    public ReportService reports() {
        return reports;
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
