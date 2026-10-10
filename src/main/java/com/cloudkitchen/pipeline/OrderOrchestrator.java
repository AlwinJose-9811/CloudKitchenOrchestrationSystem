package com.cloudkitchen.pipeline;

import com.cloudkitchen.config.AppConfig;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.QueueFullException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.RoutingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the station queues, the worker executor and the worker health monitor (SDD 7.2, 7.11). It is the only
 * implementation of {@link OrderDispatcher}: {@code OrderService} hands it an order id after the order is stored, and
 * it puts the id on the bounded queue of the order's station. The queue is a hint list; the database is the source of
 * truth, so a lost or duplicated entry never changes an outcome (SDD 7.8.1).
 *
 * <p>Thread ownership: this class creates the {@link ThreadPoolExecutor} (threads {@code station-<code>-worker}) and
 * the monitor's scheduler (thread {@code worker-monitor}) and shuts both down in {@link #shutdownGracefully}. Callers
 * of {@link #dispatch} are request or simulator threads and only touch the thread-safe queue.
 */
public final class OrderOrchestrator implements OrderDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(OrderOrchestrator.class);

    private final RoutingService routing;
    private final PipelineSettings cfg;
    private final Clock clock;
    private final OrderEventLog events;
    private final MetricsRegistry metrics;
    private final AtomicReference<WorkerHook> hook = new AtomicReference<>(WorkerHook.NO_OP);
    private final Map<String, StationRuntime> runtimes = new ConcurrentSkipListMap<>();

    private volatile OrderService service;
    private volatile ThreadPoolExecutor executor;
    private volatile WorkerMonitor monitor;
    private volatile boolean started;
    private volatile boolean stopping;

    public OrderOrchestrator(RoutingService routing, PipelineSettings cfg, Clock clock, OrderEventLog events, MetricsRegistry metrics) {
        this.routing = Objects.requireNonNull(routing, "routing");
        this.cfg = Objects.requireNonNull(cfg, "cfg");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public static OrderOrchestrator from(AppConfig config, RoutingService routing, Clock clock, OrderEventLog events, MetricsRegistry metrics) {
        return new OrderOrchestrator(routing, PipelineSettings.from(config), clock, events, metrics);
    }

    /** Installs a test hook (SDD 7.11.10); production code never calls this. */
    public void setWorkerHook(WorkerHook next) {
        hook.set(Objects.requireNonNull(next, "next"));
    }

    // ------------------------------------------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------------------------------------------

    /** Creates a runtime and a worker per active station and starts the monitor. */
    public void start(OrderService orderService) {
        start(orderService, true);
    }

    /** As {@link #start(OrderService)}; tests pass {@code false} and call {@link WorkerMonitor#runOnce()} themselves. */
    public synchronized void start(OrderService orderService, boolean scheduleMonitor) {
        if (started) {
            throw new IllegalStateException("The orchestrator is already started");
        }
        this.service = Objects.requireNonNull(orderService, "orderService");
        List<Station> stations = routing.stations();
        int core = Math.max(1, stations.size());
        ThreadPoolExecutor pool = new ThreadPoolExecutor(core, core + cfg.restartMax(), 60, TimeUnit.SECONDS,
                new SynchronousQueue<>(), new NamedThreadFactory("station-pool", true));
        this.executor = pool;
        for (Station station : stations) {
            StationRuntime runtime = new StationRuntime(station, cfg.queueCapacity());
            runtimes.put(station.getCode(), runtime);
            launch(runtime, 1);
        }
        this.monitor = new WorkerMonitor(this, clock, cfg.healthIntervalSeconds());
        started = true;
        if (scheduleMonitor) {
            monitor.start();
        }
    }

    private void launch(StationRuntime runtime, int generation) {
        StationWorker worker = new StationWorker(runtime, generation, service, cfg, clock, events, metrics, hook);
        runtime.install(worker);
        runtime.attach(executor.submit(worker));
    }

    // ------------------------------------------------------------------------------------------------------
    // Dispatch (RR-11)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Returns normally if and only if the id is in the queue (RR-11). {@link BlockingQueue#offer} with a timeout either
     * inserts the id or throws/returns without inserting, so the caller can compensate exactly when this throws.
     */
    @Override
    public void dispatch(long orderId, String brandCode) throws QueueFullException {
        StationRuntime runtime = brandCode == null ? null : runtimes.get(brandCode);
        if (runtime == null) {
            throw new QueueFullException(String.valueOf(brandCode), QueueFullException.Reason.STATION_UNAVAILABLE);
        }
        if (stopping || runtime.isClosed()) {
            throw new QueueFullException(brandCode, QueueFullException.Reason.QUEUE_CLOSED);
        }
        try {
            if (!runtime.tryEnqueue(orderId, cfg.offerTimeoutMs())) {
                throw new QueueFullException(brandCode, QueueFullException.Reason.QUEUE_FULL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueueFullException(brandCode, QueueFullException.Reason.INTERRUPTED, e);
        }
    }

    /** Startup recovery: blocks until the queue has room. */
    public void enqueueRecovered(long orderId, String brandCode) throws InterruptedException {
        StationRuntime runtime = brandCode == null ? null : runtimes.get(brandCode);
        if (runtime == null) {
            throw new IllegalStateException("No runtime for station " + brandCode);
        }
        runtime.putBlocking(orderId);
    }

    // ------------------------------------------------------------------------------------------------------
    // Operator controls and views
    // ------------------------------------------------------------------------------------------------------

    public void pause(String code) {
        StationRuntime runtime = runtimes.get(code);
        if (runtime != null) {
            runtime.setPaused(true);
            events.log(EventType.WORKER_PAUSED, 0L, "station=" + code);
        }
    }

    public void resume(String code) {
        StationRuntime runtime = runtimes.get(code);
        if (runtime != null) {
            runtime.setPaused(false);
            events.log(EventType.WORKER_RESUMED, 0L, "station=" + code);
        }
    }

    public List<StationView> stationViews() {
        long now = clock.millis();
        List<StationView> views = new ArrayList<>();
        for (StationRuntime runtime : runtimes.values()) {
            views.add(runtime.view(now));
        }
        return views;
    }

    public StationRuntime runtime(String code) {
        return runtimes.get(code);
    }

    public WorkerMonitor monitor() {
        return monitor;
    }

    public boolean isStopping() {
        return stopping;
    }

    // ------------------------------------------------------------------------------------------------------
    // Repair: health, restart, reclaim, reconcile (SDD 7.11.4, 7.11.5, 7.11.14, 7.13.6)
    // ------------------------------------------------------------------------------------------------------

    /** One repair pass over every station; the monitor calls it on a schedule, tests call it directly. */
    public void repairAll() {
        for (StationRuntime runtime : runtimes.values()) {
            repairStation(runtime);
        }
    }

    /** Evaluates the worker on demand, replaces it if it is dead or stalled, then reconciles the queue with the database. */
    public void repairStation(StationRuntime runtime) {
        if (stopping || !started || runtime.isDead()) {
            return;
        }
        StationWorker worker = runtime.worker();
        Future<?> future = runtime.future();
        boolean done = future != null && future.isDone();
        boolean healthy = !done && worker.isHealthy(clock.millis(), cfg.stallMs());
        if (!healthy) {
            String reason = done ? "DIED " + causeOf(future) : "STALLED";
            events.log(done ? EventType.WORKER_DIED : EventType.WORKER_STALLED, 0L, "station=" + runtime.code());
            LOG.error("Worker {} is {}", worker.owner(), reason);
            restartWorker(runtime.code(), runtime.currentGeneration(), reason);
        }
        StationWorker current = runtime.worker();
        if (!runtime.isDead() && current.isHealthy(clock.millis(), cfg.stallMs())) {
            reconcileStation(runtime);
        }
    }

    /**
     * Replaces the worker of one station if {@code expectedGeneration} is still current (compare-and-set under the
     * runtime lock, so concurrent repairs restart once): retires the old generation, clears its leases, starts the next
     * generation on the same queue and re-offers the orders it had leased.
     *
     * @return {@code true} if this call started a new generation
     */
    public boolean restartWorker(String code, int expectedGeneration, String reason) {
        StationRuntime runtime = runtimes.get(code);
        if (runtime == null || stopping || !started) {
            return false;
        }
        synchronized (runtime.lock()) {
            if (runtime.currentGeneration() != expectedGeneration || runtime.isDead()) {
                return false;
            }
            Instant now = clock.instant();
            var times = runtime.restartTimes();
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minusSeconds(cfg.restartWindowSeconds()))) {
                times.pollFirst();
            }
            if (times.size() >= cfg.restartMax()) {
                runtime.markDead();
                events.log(EventType.WORKER_GAVE_UP, 0L, "station=" + code);
                LOG.error("Worker of station {} gave up after {} restarts", code, times.size());
                return false;
            }
            StationWorker old = runtime.worker();
            old.retire();
            Future<?> oldFuture = runtime.future();
            if (oldFuture != null) {
                oldFuture.cancel(true);
            }
            List<Long> reclaimed = List.of();
            try {
                reclaimed = service.reclaimLeasesOf(old.owner());
            } catch (CloudKitchenException e) {
                LOG.warn("Could not clear the leases of {}; they expire by themselves ({})", old.owner(), e.getCode());
            }
            runtime.activeIds().values().removeIf(g -> g == old.generation());
            times.addLast(now);
            runtime.countRestart();
            launch(runtime, old.generation() + 1);
            for (long id : reclaimed) {
                runtime.offerNow(id);
            }
            events.log(EventType.WORKER_RESTARTED, 0L, "station=" + code + " generation=" + (old.generation() + 1)
                    + " reclaimed=" + reclaimed.size());
            return true;
        }
    }

    /**
     * Re-offers orders that are non-terminal, not pending compensation, whose lease is free and that are absent from
     * the physical queue (SDD 7.11.14). It reads the database and the queue and writes nothing but queue entries.
     */
    void reconcileStation(StationRuntime runtime) {
        try {
            List<Long> candidates = service.findReconcilableIds(runtime.station().getId(),
                    Duration.ofSeconds(cfg.dispatchWindowSeconds()), cfg.reconcileBatch());
            Set<Long> present = runtime.physicalSnapshot();
            int offered = 0;
            for (long id : candidates) {
                if (present.contains(id)) {
                    continue;
                }
                if (!runtime.offerNow(id)) {
                    break;
                }
                offered++;
            }
            if (offered > 0) {
                events.log(EventType.QUEUE_RECONCILED, 0L, "station=" + runtime.code() + " count=" + offered);
            }
        } catch (CloudKitchenException e) {
            LOG.warn("Reconcile of station {} skipped: {}", runtime.code(), e.getCode());
        }
    }

    private static String causeOf(Future<?> future) {
        try {
            future.get(0, TimeUnit.MILLISECONDS);
            return "(returned)";
        } catch (ExecutionException e) {
            return e.getCause() == null ? "(unknown)" : e.getCause().getClass().getName();
        } catch (CancellationException e) {
            return "(cancelled)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "(interrupted)";
        } catch (java.util.concurrent.TimeoutException e) {
            return "(running)";
        }
    }

    // ------------------------------------------------------------------------------------------------------
    // Stop (SDD 7.6 steps 4-6; the coordinator that calls it arrives in Phase 5)
    // ------------------------------------------------------------------------------------------------------

    /**
     * Stops accepting new ids, lets the workers drain their queues for up to {@code drain}, then asks them to stop and
     * waits up to {@code await}, and finally interrupts what is left. Orders not finished stay non-terminal in the
     * database and are recovered at the next start.
     *
     * @return {@code true} if every worker thread ended
     */
    public boolean shutdownGracefully(Duration drain, Duration await) {
        ThreadPoolExecutor pool = executor;
        if (pool == null) {
            return true;
        }
        stopping = true;
        if (monitor != null) {
            monitor.stop();
        }
        for (StationRuntime runtime : runtimes.values()) {
            runtime.setPaused(false);
            runtime.worker().requestDrain();
        }
        pool.shutdown();
        try {
            boolean done = pool.awaitTermination(drain.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                for (StationRuntime runtime : runtimes.values()) {
                    runtime.worker().requestStop();
                }
                done = pool.awaitTermination(await.toMillis(), TimeUnit.MILLISECONDS);
            }
            if (!done) {
                pool.shutdownNow();
                done = pool.awaitTermination(2, TimeUnit.SECONDS);
            }
            return done;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            return false;
        } finally {
            for (StationRuntime runtime : runtimes.values()) {
                runtime.close();
            }
        }
    }
}
