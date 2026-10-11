package com.cloudkitchen.pipeline;

import com.cloudkitchen.domain.Station;
import com.cloudkitchen.domain.enums.WorkerState;
import com.cloudkitchen.dto.StationView;
import com.cloudkitchen.dto.WorkerView;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Everything one station owns at run time (SDD 7.2): its bounded queue of order ids, its current worker generation and
 * the hint sets. The queue and the hint sets are thread-safe; the generation, the future and the restart history are
 * changed only under {@link #lock()}. The hint sets are optimisations: ownership of an order is the database lease
 * (SDD 7.11.2), so nothing here is ever proof that an order is or is not being processed.
 */
public final class StationRuntime {

    private final Station station;
    private final ArrayBlockingQueue<Long> queue;
    private final Object lock = new Object();
    private final Set<Long> queuedIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, Long> activeIds = new ConcurrentHashMap<>();
    private final ArrayDeque<Instant> restartTimes = new ArrayDeque<>();

    private volatile StationWorker worker;
    private volatile Future<?> future;
    private volatile int generation;
    private volatile int restarts;
    private volatile boolean paused;
    private volatile boolean dead;
    private volatile boolean closed;

    public StationRuntime(Station station, int capacity) {
        this.station = Objects.requireNonNull(station, "station");
        this.queue = new ArrayBlockingQueue<>(capacity, false);
    }

    public Station station() {
        return station;
    }

    public String code() {
        return station.getCode();
    }

    /** The lock that serialises restarts of this station's worker. */
    Object lock() {
        return lock;
    }

    ArrayBlockingQueue<Long> queue() {
        return queue;
    }

    Set<Long> queuedIds() {
        return queuedIds;
    }

    ConcurrentHashMap<Long, Long> activeIds() {
        return activeIds;
    }

    ArrayDeque<Instant> restartTimes() {
        return restartTimes;
    }

    /** Offers the id, waiting up to {@code timeoutMs}; {@code false} if the queue stayed full. Nothing is inserted if it throws. */
    public boolean tryEnqueue(long orderId, long timeoutMs) throws InterruptedException {
        boolean inserted = queue.offer(orderId, timeoutMs, TimeUnit.MILLISECONDS);
        if (inserted) {
            queuedIds.add(orderId);
        }
        return inserted;
    }

    /** Blocks until there is room (startup recovery). */
    public void putBlocking(long orderId) throws InterruptedException {
        queue.put(orderId);
        queuedIds.add(orderId);
    }

    /** Non-blocking offer used by repair and parking. */
    boolean offerNow(long orderId) {
        boolean inserted = queue.offer(orderId);
        if (inserted) {
            queuedIds.add(orderId);
        }
        return inserted;
    }

    public int size() {
        return queue.size();
    }

    public int capacity() {
        return queue.size() + queue.remainingCapacity();
    }

    /** A copy of the physical queue contents, taken under the queue's own lock (never the hint set, SDD 7.11.14). */
    Set<Long> physicalSnapshot() {
        Set<Long> copy = new HashSet<>();
        for (Object id : queue.toArray()) {
            copy.add((Long) id);
        }
        return copy;
    }

    StationWorker worker() {
        return worker;
    }

    Future<?> future() {
        return future;
    }

    /** A worker and the future of the task that runs it, read together. */
    record Observed(StationWorker worker, Future<?> future) {
    }

    /**
     * The current worker and its future as one consistent pair. {@code OrderOrchestrator.restartWorker} installs the
     * new worker and attaches its future one after the other while it holds {@link #lock()}; reading the two fields
     * separately could pair the new worker with the old, finished future, and a repair would then take the healthy new
     * worker for a dead one. This method takes the same lock, so it never sees the pair half replaced.
     */
    Observed observe() {
        synchronized (lock) {
            return new Observed(worker, future);
        }
    }

    /** Publishes the worker and its generation before the worker is submitted, so it never sees itself as stale. */
    void install(StationWorker next) {
        this.worker = next;
        this.generation = next.generation();
    }

    void attach(Future<?> nextFuture) {
        this.future = nextFuture;
    }

    /** The generation of the current worker; a worker of an older generation is retired (rule L-6). */
    public int currentGeneration() {
        return generation;
    }

    public int restarts() {
        return restarts;
    }

    void countRestart() {
        restarts++;
    }

    public boolean isPaused() {
        return paused;
    }

    void setPaused(boolean value) {
        this.paused = value;
    }

    public boolean isDead() {
        return dead;
    }

    void markDead() {
        this.dead = true;
    }

    void revive() {
        this.dead = false;
    }

    public boolean isClosed() {
        return closed;
    }

    void close() {
        this.closed = true;
    }

    StationView view(long nowMillis) {
        StationWorker current = worker;
        WorkerView workerView = current == null
                ? new WorkerView(code(), dead ? WorkerState.DEAD : WorkerState.STOPPED, null, 0, 0, generation, restarts, 0, null)
                : current.view(nowMillis, dead, restarts);
        return new StationView(code(), station.getBrandName(), queue.size(), capacity(), paused, workerView);
    }
}
