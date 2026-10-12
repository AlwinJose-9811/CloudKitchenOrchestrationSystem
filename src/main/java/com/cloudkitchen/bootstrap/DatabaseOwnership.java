package com.cloudkitchen.bootstrap;

import com.cloudkitchen.exception.CloudKitchenException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides who owns an embedded database directory, and cleans up after an owner that ended abruptly (Phase 8, P8-D2).
 *
 * <p>The embedded server is a child process of the application. When the JVM is killed ({@code kill -9}, out-of-memory killer)
 * nothing stops the child, and it keeps the data directory locked, so the next start used to fail. Ownership is an
 * <em>operating-system file lock</em> on {@value #FILE} next to the data directory, held for the life of the process and released
 * by the operating system when the process ends, however it ends. There is therefore no stale marker and no process-id
 * reuse to reason about, and two applications starting at the same moment cannot both pass the check.
 * <ul>
 *   <li>The lock cannot be taken: a live application owns the directory. Refuse with {@code DB_IN_USE} and touch nothing.</li>
 *   <li>The lock is taken and the file still holds the pid of a previous owner (it is emptied by a clean stop): that owner
 *       ended without stopping its server. The servers whose command line names exactly this data directory are stopped with
 *       SIGTERM (a clean InnoDB shutdown), and with a forced kill only after {@code stopWait}.</li>
 *   <li>The lock is taken and the file is empty or new: nothing is touched (a server started by hand is not ours to stop).</li>
 * </ul>
 */
final class DatabaseOwnership {

    static final String FILE = ".ck-db-owner";
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseOwnership.class);

    private final Path dataDir;
    private final Path lockFile;
    private final Duration stopWait;
    private FileChannel channel;
    private FileLock lock;

    DatabaseOwnership(Path dataDir, Duration stopWait) {
        this.dataDir = dataDir.toAbsolutePath().normalize();
        this.lockFile = this.dataDir.getParent().resolve(FILE);
        this.stopWait = stopWait;
    }

    /**
     * Takes ownership for this process and stops what a previous owner left behind.
     *
     * @return how many leftover servers were stopped
     */
    synchronized int acquire() throws CloudKitchenException {
        if (lock != null) {
            return 0;
        }
        FileChannel opened = null;
        try {
            Files.createDirectories(lockFile.getParent());
            opened = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            FileLock taken = tryLock(opened);
            if (taken == null) {
                String previous = read(opened);
                opened.close();
                throw new CloudKitchenException("DB_IN_USE", "The embedded database in " + dataDir + " is in use by a running application"
                        + (previous.isBlank() ? "" : " (process " + previous + ")") + ". Stop it first; nothing was changed.");
            }
            String previous = read(opened);
            int stopped = 0;
            if (!previous.isBlank()) {
                for (ProcessHandle server : serversOf(dataDir)) {
                    LOG.warn("Stopping database server {} for {} that the application process {} left behind (it ended without stopping it)",
                            server.pid(), dataDir, previous);
                    stop(server);
                    stopped++;
                }
            }
            opened.truncate(0);
            opened.write(ByteBuffer.wrap((ProcessHandle.current().pid() + "\n").getBytes(StandardCharsets.US_ASCII)), 0);
            opened.force(false);
            this.channel = opened;
            this.lock = taken;
            return stopped;
        } catch (IOException e) {
            closeQuietly(opened);
            throw new CloudKitchenException("DB_START_FAILED", "Cannot check the owner of the embedded database in " + dataDir + ": " + e, e);
        } catch (CloudKitchenException | RuntimeException e) {
            closeQuietly(opened);
            throw e;
        }
    }

    /** Gives ownership up after a clean stop of the server: the file is emptied, so the next start finds nothing to clean up. */
    synchronized void release() {
        if (lock == null) {
            return;
        }
        try {
            channel.truncate(0);
            lock.release();
            channel.close();
        } catch (IOException e) {
            LOG.warn("Could not release the database owner lock {}: {}", lockFile, e.toString());
        } finally {
            lock = null;
            channel = null;
        }
    }

    private static FileLock tryLock(FileChannel channel) throws IOException {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException sameProcess) {
            return null; // another DbBootstrap of this very JVM holds it
        }
    }

    private static String read(FileChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64);
        int read = channel.read(buffer, 0);
        return read <= 0 ? "" : new String(buffer.array(), 0, read, StandardCharsets.US_ASCII).trim();
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing more to do
            }
        }
    }

    /** Database servers whose command line names exactly this data directory. */
    static List<ProcessHandle> serversOf(Path dataDir) {
        String argument = "--datadir=" + dataDir.toAbsolutePath().normalize();
        return ProcessHandle.allProcesses()
                .filter(handle -> handle.info().commandLine().map(line -> isServerOf(line, argument)).orElse(false))
                .toList();
    }

    static boolean isServerOf(String commandLine, String datadirArgument) {
        if (!commandLine.contains("mariadbd") && !commandLine.contains("mysqld")) {
            return false;
        }
        int at = commandLine.indexOf(datadirArgument);
        while (at >= 0) {
            int end = at + datadirArgument.length();
            if (end == commandLine.length() || commandLine.charAt(end) == ' ') {
                return true; // not a longer path that merely starts the same way
            }
            at = commandLine.indexOf(datadirArgument, end);
        }
        return false;
    }

    private void stop(ProcessHandle server) throws CloudKitchenException {
        server.destroy();
        try {
            server.onExit().get(stopWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("Database server {} did not stop within {} s; forcing it", server.pid(), stopWait.toSeconds());
            server.destroyForcibly();
            try {
                server.onExit().get(10, TimeUnit.SECONDS);
            } catch (TimeoutException | ExecutionException late) {
                throw new CloudKitchenException("DB_START_FAILED", "Database server " + server.pid() + " left behind cannot be stopped", late);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CloudKitchenException("DB_START_FAILED", "Interrupted while stopping database server " + server.pid(), interrupted);
            }
        } catch (ExecutionException e) {
            throw new CloudKitchenException("DB_START_FAILED", "Database server " + server.pid() + " left behind cannot be stopped", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudKitchenException("DB_START_FAILED", "Interrupted while stopping database server " + server.pid(), e);
        }
    }
}
