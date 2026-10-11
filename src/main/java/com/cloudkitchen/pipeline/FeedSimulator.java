package com.cloudkitchen.pipeline;

import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import com.cloudkitchen.service.MenuService;
import com.cloudkitchen.service.OrderService;
import com.cloudkitchen.service.RoutingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Produces simulated orders for all three sources at an adjustable rate (SDD 7.5, FR-RES-01). Every order goes through
 * {@link OrderService#submitOrder}, so validation, stock deduction, idempotency and queueing are exactly those of a
 * real order; the simulator has no shortcut and no persistence of its own.
 *
 * <p>Thread ownership: one single-thread scheduler, thread {@code feed-simulator}, created by this class and ended by
 * {@link #shutdown()}. The task is scheduled with a fixed delay, so ticks never overlap and the work per minute is
 * bounded by the rate (itself bounded by {@code simulator.max.rate.per.minute}). A tick catches every exception: an
 * uncaught one would silently cancel the schedule. {@link #stop()} cancels the schedule and waits for a tick that is
 * already running, so that after it returns no new tick starts.
 *
 * <p>Bounded stop: a tick can be blocked inside {@code submitOrder} (a database stall, a full queue). {@link #stop()}
 * waits for it at most {@code stopWait} (default 2 s); if the tick is still running then, {@code stop()} returns anyway
 * (no new tick starts, because {@code running} is already false), logs a warning and {@link #isTickRunning()} stays
 * true. {@link #shutdown()} then interrupts the thread ({@code shutdownNow}) and waits at most {@code stopWait} more;
 * it returns {@code false} if the thread is still alive. Such a tick can finish its one submission later: the
 * coordinator has stopped accepting orders before this step, so that submission is answered {@code SHUTTING_DOWN}
 * unless it already passed that check, in which case its transaction either commits (the order is recovered by the
 * next start) or fails because the pool is closed (no order is created). The thread ends as soon as the call returns.
 */
public final class FeedSimulator {

    private static final Logger LOG = LoggerFactory.getLogger(FeedSimulator.class);
    private static final Duration DEFAULT_STOP_WAIT = Duration.ofSeconds(2);

    /** The test seam that decides what the next simulated order looks like. */
    @FunctionalInterface
    public interface OrderGenerator {
        /** The next request, or empty if none can be built now (for example no active menu item). */
        Optional<OrderRequest> next(long sequence) throws CloudKitchenException;
    }

    /** What the simulator did so far; an immutable copy. */
    public record Stats(long produced, long accepted, long notAccepted, long errors, Instant lastTickAt) {
    }

    private final OrderService orders;
    private final OrderGenerator generator;
    private final int maxRatePerMinute;
    private final Clock clock;
    private final OrderEventLog events;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("feed-simulator", false));
    private final ReentrantLock tickLock = new ReentrantLock();
    private final Duration stopWait;
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong produced = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong notAccepted = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    private volatile boolean running;
    private volatile int ratePerMinute;
    private volatile Instant lastTickAt;
    private ScheduledFuture<?> task;

    public FeedSimulator(OrderService orders, OrderGenerator generator, int defaultRatePerMinute, int maxRatePerMinute,
                         Clock clock, OrderEventLog events) {
        this(orders, generator, defaultRatePerMinute, maxRatePerMinute, clock, events, DEFAULT_STOP_WAIT);
    }

    /** As above with an explicit bound for {@link #stop()} and {@link #shutdown()} (tests use a short one). */
    public FeedSimulator(OrderService orders, OrderGenerator generator, int defaultRatePerMinute, int maxRatePerMinute,
                         Clock clock, OrderEventLog events, Duration stopWait) {
        if (stopWait == null || stopWait.isNegative() || stopWait.isZero()) {
            throw new IllegalArgumentException("stopWait must be positive");
        }
        this.stopWait = stopWait;
        this.orders = Objects.requireNonNull(orders, "orders");
        this.generator = Objects.requireNonNull(generator, "generator");
        this.maxRatePerMinute = maxRatePerMinute;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        this.ratePerMinute = checkRate(defaultRatePerMinute);
    }

    /** The standard generator: random source, active brand and 1-3 active menu items of that brand (SDD 7.5). */
    public static OrderGenerator randomGenerator(RoutingService routing, MenuService menu, Random random) {
        return seq -> {
            List<Station> stations = routing.stations();
            if (stations.isEmpty()) {
                return Optional.empty();
            }
            OrderSource source = OrderSource.values()[random.nextInt(OrderSource.values().length)];
            Station station = stations.get(random.nextInt(stations.size()));
            List<MenuItem> items = new ArrayList<>(menu.activeForStation(station.getCode()));
            if (items.isEmpty()) {
                return Optional.empty();
            }
            Collections.shuffle(items, random);
            List<OrderItemRequest> lines = new ArrayList<>();
            int count = 1 + random.nextInt(Math.min(3, items.size()));
            for (int i = 0; i < count; i++) {
                lines.add(new OrderItemRequest(items.get(i).getId(), 1 + random.nextInt(3)));
            }
            PaymentMode mode = source == OrderSource.IN_HOUSE
                    ? new PaymentMode[] {PaymentMode.CASH, PaymentMode.UPI, PaymentMode.CARD}[random.nextInt(3)]
                    : PaymentMode.PREPAID_AGGREGATOR;
            String ref = String.format("SIM-%s-%d-%08x", source.name(), seq, random.nextInt());
            BigDecimal distance = BigDecimal.valueOf(50 + random.nextInt(1151), 2); // 0.50 - 12.00 km
            return Optional.of(new OrderRequest(source, ref, station.getCode(), "Sim Customer " + seq, distance, mode, lines));
        };
    }

    private int checkRate(int rate) {
        if (rate < 1 || rate > maxRatePerMinute) {
            throw new IllegalArgumentException("rate must be between 1 and " + maxRatePerMinute + " orders per minute");
        }
        return rate;
    }

    private static long periodMillis(int rate) {
        return Math.max(1, 60_000L / rate);
    }

    /** Starts producing at {@code rate} orders per minute; {@code false} if it is already running. */
    public synchronized boolean start(int rate) {
        checkRate(rate);
        if (running) {
            return false;
        }
        ratePerMinute = rate;
        running = true;
        try {
            task = scheduler.scheduleWithFixedDelay(this::tick, 0, periodMillis(rate), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            running = false; // the scheduler was shut down: a simulator cannot be restarted after shutdown()
            return false;
        }
        events.log(EventType.SIMULATOR_STARTED, 0L, "rate=" + rate);
        return true;
    }

    /**
     * Stops producing and waits, for at most the stop bound, for a tick that is already running; {@code false} if it was
     * not running. Returns after the bound even if the tick is still blocked (see the class comment).
     */
    public boolean stop() {
        ScheduledFuture<?> cancelled;
        synchronized (this) {
            if (!running) {
                return false;
            }
            running = false;
            cancelled = task;
            task = null;
        }
        if (cancelled != null) {
            cancelled.cancel(false);
        }
        awaitRunningTick();
        events.log(EventType.SIMULATOR_STOPPED, 0L, "produced=" + produced.get());
        return true;
    }

    /** Changes the rate; takes effect at once if the simulator is running (the schedule is replaced). */
    public synchronized void setRate(int rate) {
        checkRate(rate);
        ratePerMinute = rate;
        if (running) {
            if (task != null) {
                task.cancel(false);
            }
            try {
                task = scheduler.scheduleWithFixedDelay(this::tick, periodMillis(rate), periodMillis(rate), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                running = false; // shut down meanwhile
            }
        }
    }

    public boolean isRunning() {
        return running;
    }

    /** Whether a tick is executing right now (it is still blocked if this stays true after {@link #stop()} returned). */
    public boolean isTickRunning() {
        return tickLock.isLocked();
    }

    /** A tick that had already started has finished once the lock is free; later ticks see {@code running == false}. */
    private void awaitRunningTick() {
        try {
            if (tickLock.tryLock(stopWait.toMillis(), TimeUnit.MILLISECONDS)) {
                tickLock.unlock();
            } else {
                LOG.warn("A simulator tick is still running after {} ms; stopping without it", stopWait.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getRate() {
        return ratePerMinute;
    }

    /** Orders the simulator tried to submit so far. */
    public long produced() {
        return produced.get();
    }

    public Stats stats() {
        return new Stats(produced.get(), accepted.get(), notAccepted.get(), errors.get(), lastTickAt);
    }

    /** One scheduled production step; runs on the simulator thread, one at a time, and never throws. */
    void tick() {
        tickLock.lock();
        try {
            if (running) {
                produceOne();
            }
        } finally {
            tickLock.unlock();
        }
    }

    /** Builds and submits one order; package-visible so that tests can drive single steps. Never throws. */
    void produceOne() {
        try {
            lastTickAt = clock.instant();
            Optional<OrderRequest> request = generator.next(sequence.incrementAndGet());
            if (request.isEmpty()) {
                return;
            }
            produced.incrementAndGet();
            SubmissionResult result = orders.submitOrder(request.get());
            if (result.outcome() == SubmissionResult.Outcome.ACCEPTED) {
                accepted.incrementAndGet();
            } else {
                notAccepted.incrementAndGet(); // limited stock, queue full, shutting down: all expected, none is an error
            }
        } catch (CloudKitchenException | RuntimeException e) {
            errors.incrementAndGet();
            LOG.warn("Simulator tick failed: {}", e.getClass().getName());
        }
    }

    /**
     * Ends the scheduler thread; the simulator cannot be started afterwards. Takes at most twice the stop bound and
     * restores the interrupt flag if interrupted.
     *
     * @return {@code true} if the simulator thread has ended; {@code false} if a tick ignored the interrupt (the thread
     *         ends when that call returns) or the caller was interrupted
     */
    public boolean shutdown() {
        stop();
        scheduler.shutdownNow();
        try {
            if (scheduler.awaitTermination(stopWait.toMillis(), TimeUnit.MILLISECONDS)) {
                return true;
            }
            LOG.warn("The feed simulator thread did not end within {} ms", stopWait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }
}
