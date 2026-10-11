package com.cloudkitchen.io;

import com.cloudkitchen.exception.EventLogException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The append-only structured order log (SDD 8.1, FR-FIL-01): one UTF-8 line per event,
 * {@code ts=<UTC, millis> event=<TYPE> [orderId=<n>] [detail="<text>"]}, in the file configured as
 * {@code log.orders.path}. Only ids, codes, statuses and counts are ever passed in; customer names, addresses,
 * passwords, hashes, secrets and session ids never reach this class (SDD 8.1, {@code NoPiiInLogsTest}).
 *
 * <p><b>Concurrency.</b> One {@link ReentrantLock} guards "stamp the time, format the line, write it". The line is
 * written with a loop of {@link FileChannel#write} calls on a channel opened with {@code APPEND}, so a record is
 * never interleaved with another record, and the timestamps in the file never go backwards. The lock is held only
 * for that write; this class calls nothing outside itself while holding it, so it cannot take part in a deadlock.
 * Callers log after their transaction has committed and hold none of their own locks.
 *
 * <p><b>Interrupts.</b> A {@code FileChannel} is closed for every thread if a thread is interrupted while it uses
 * it. A caller whose interrupt flag is set (a worker being stopped, a shutdown thread) would therefore kill the log
 * for everybody. {@link #log} clears the caller's flag around the write and sets it again afterwards; if the channel
 * is closed anyway (an interrupt delivered during the write), the write reopens the file and retries once.
 *
 * <p><b>Failures.</b> Opening fails with {@link EventLogException} (path is a directory, parent cannot be created, no
 * permission); the application then does not start. A failing write never throws into the business flow (logging must
 * not change behaviour): the event is counted as lost ({@link #failureCount()}), the next call tries to reopen the
 * file, and the failure is reported on the <em>application</em> logger (never through this log: no recursion), the
 * first time and then every {@value #REPORT_EVERY}th time.
 *
 * <p><b>Lifecycle.</b> {@link #close()} is idempotent and owned by the shutdown coordinator (the last file step).
 * Events logged after {@code close} are dropped and counted ({@link #droppedAfterCloseCount()}). The file is never
 * truncated or rewritten; rotation is out of scope.
 */
public final class NioOrderEventLog implements OrderEventLog {

    private static final Logger LOG = LoggerFactory.getLogger(NioOrderEventLog.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final int MAX_DETAIL = 200;
    private static final long REPORT_EVERY = 1000;

    private final Path path;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    private FileChannel channel; // guarded by lock
    private boolean closed;      // guarded by lock
    private volatile String lastFailure;
    private volatile Runnable beforeWrite = () -> { };

    /** Creates missing parent directories and opens the file for appending. */
    public NioOrderEventLog(Path path, Clock clock) throws EventLogException {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.channel = open();
    }

    private FileChannel open() throws EventLogException {
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        } catch (IOException | RuntimeException e) {
            throw new EventLogException("Cannot open the order log " + path + " for appending (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    public Path path() {
        return path;
    }

    /** Test seam: runs inside the lock right before each write, for example to deliver an interrupt "during" the write. */
    void setBeforeWrite(Runnable hook) {
        this.beforeWrite = Objects.requireNonNull(hook, "hook");
    }

    /** The open channel, for a test that simulates a channel closed by an interrupt; not part of the API. */
    FileChannel channelForTest() {
        lock.lock();
        try {
            return channel;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void log(EventType type) {
        append(type, 0L, null);
    }

    @Override
    public void log(EventType type, long orderId) {
        append(type, orderId, null);
    }

    @Override
    public void log(EventType type, long orderId, String detail) {
        append(type, orderId, detail);
    }

    private void append(EventType type, long orderId, String detail) {
        Objects.requireNonNull(type, "type");
        boolean wasInterrupted = Thread.interrupted(); // see "Interrupts" in the class comment
        lock.lock();
        try {
            if (closed) {
                dropped.incrementAndGet();
                return;
            }
            byte[] line = format(type, orderId, detail);
            try {
                beforeWrite.run();
                writeFully(line);
                written.incrementAndGet();
            } catch (IOException first) {
                // an interrupt delivered during the write closed the channel and left the flag set: the retry would fail the same way
                wasInterrupted |= Thread.interrupted();
                retryOnce(line, first);
            }
        } finally {
            lock.unlock();
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void retryOnce(byte[] line, IOException first) {
        try {
            reopen();
            writeFully(line);
            written.incrementAndGet();
        } catch (IOException second) {
            recordFailure(second);
        } catch (EventLogException reopenFailed) {
            recordFailure(first);
        }
    }

    private void reopen() throws EventLogException {
        closeQuietly();
        channel = open();
    }

    private void writeFully(byte[] line) throws IOException {
        if (channel == null || !channel.isOpen()) {
            throw new IOException("the order log channel is closed");
        }
        ByteBuffer buffer = ByteBuffer.wrap(line);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private byte[] format(EventType type, long orderId, String detail) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("ts=").append(TS.format(clock.instant())).append(" event=").append(type.name());
        if (orderId > 0) {
            sb.append(" orderId=").append(orderId);
        }
        if (detail != null && !detail.isEmpty()) {
            sb.append(" detail=\"");
            appendSanitized(sb, detail);
            sb.append('"');
        }
        return sb.append('\n').toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Control characters become a space, backslash and quote are escaped, the value is cut at 200 characters. */
    private static void appendSanitized(StringBuilder sb, String detail) {
        int limit = Math.min(detail.length(), MAX_DETAIL);
        for (int i = 0; i < limit; i++) {
            char c = detail.charAt(i);
            if (c < 0x20 || c == 0x7F || c == 0x85 || c == 0x2028 || c == 0x2029) {
                sb.append(' ');
            } else if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
    }

    private void recordFailure(IOException e) {
        long n = failed.incrementAndGet();
        lastFailure = e.getClass().getSimpleName();
        if (n == 1 || n % REPORT_EVERY == 0) {
            LOG.error("The order log {} could not be written ({}); {} event(s) lost so far", path, lastFailure, n);
        }
    }

    /** Events written successfully. */
    public long writtenCount() {
        return written.get();
    }

    /** Events lost because a write (and its one retry after reopening) failed. */
    public long failureCount() {
        return failed.get();
    }

    /** Events logged after {@link #close()}; dropped on purpose. */
    public long droppedAfterCloseCount() {
        return dropped.get();
    }

    /** Simple class name of the most recent write failure, or {@code null}. */
    public String lastFailure() {
        return lastFailure;
    }

    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            closeQuietly();
        } finally {
            lock.unlock();
        }
    }

    private void closeQuietly() {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException e) {
                LOG.warn("Closing the order log {} failed ({})", path, e.getClass().getSimpleName());
            }
            channel = null;
        }
    }
}
