package com.cloudkitchen.snapshot;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.dao.StatusHistoryDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.db.SqlErrors;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderFactory;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.StatusHistoryEntry;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.exception.SnapshotIntegrityException;
import com.cloudkitchen.exception.SnapshotWriteException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.io.OrderEventLog;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Signed snapshots of the in-flight orders and their restoration (SDD 9.4, FR-SER-02..05, FR-RES-05, FR-RES-09).
 *
 * <h2>Files</h2>
 * {@code <snapshot.dir>/snapshot-<yyyyMMdd'T'HHmmssSSS'Z'>.ser} holds the Java-serialised {@code ArrayList} of
 * {@link OrderSnapshotDTO}; {@code ....ser.hmac} holds the signature text of {@link HmacSigner}. Each is written to a
 * {@code .tmp} file, forced to disk and moved into place with {@code ATOMIC_MOVE} (a plain replacing move where the file
 * system cannot); the {@code .ser} is moved first and the {@code .hmac} last, so a {@code .ser} without a signature is
 * an unfinished write and is ignored. {@link #pruneOld()} keeps the newest {@code snapshot.retain.count} complete pairs and
 * deletes older pairs and stale temporary files; it does not delete orphaned {@code .ser} or {@code .hmac} files.
 *
 * <h2>Writing</h2>
 * {@link #writeSnapshot()} reads the non-terminal orders that are not awaiting compensation in one transaction (one
 * consistent view), so the snapshot is exactly the set that recovery would re-queue (SDD 7.10.9). Writes never overlap:
 * a lock covers read, serialise, sign and move, and the file name is made unique under that lock. A write that fails
 * ({@link SnapshotWriteException}: disk full, permissions, database unreadable) leaves no final file and does not
 * change any order; the scheduled task logs it and tries again at the next interval, and at shutdown a failed final
 * snapshot is recorded but never blocks the shutdown (SDD F-17).
 *
 * <h2>Reading</h2>
 * {@link #read} order of operations, each step a gate: (1) the file size is checked against {@code snapshot.max.bytes};
 * (2) the bytes and the signature text are read; (3) the HMAC is verified in constant time; <b>only then</b> (4) an
 * {@link ObjectInputStream} is created, given the {@link SnapshotFilterFactory} allowlist before the first
 * {@code readObject}, and the result is checked to be an {@code ArrayList} of {@link OrderSnapshotDTO} of format
 * {@link OrderSnapshotDTO#CURRENT_FORMAT_VERSION}. Any failure is a {@link SnapshotIntegrityException}; nothing is
 * partly accepted. {@link #readLatestValid()} tries the newest pair first and falls back to older ones. This code never
 * deserialises anything that did not come from a file in {@code snapshot.dir} (no web input, no upload).
 *
 * <h2>Restoration</h2>
 * {@link #recoverMissingOrders()} runs first in {@code RecoveryService}. For each order of the newest valid snapshot:
 * the order is skipped if its status is terminal or its content does not validate; if the database already has an
 * order with the same {@code (source, external_ref)} the database row <b>wins</b> and is never changed (a different
 * status is counted as a conflict); otherwise, in its own transaction, the order, its lines and a reconstructed history
 * are inserted with {@code stock_deducted = 0} (the stock state of a lost database cannot be known, so no restore will
 * ever add stock for it). The history is the lifecycle path from {@code PLACED} to the snapshot status; every row has
 * note {@code RECOVERED_FROM_SNAPSHOT}, the first row is stamped with the creation time and the others with the time of
 * recovery. A failure for one order (unknown station or item, tracking code used by another order, database error) skips
 * that order and does not affect the others. The method never throws for a bad file or a bad order, so a failed
 * restoration never prevents the application from starting; it is idempotent because identity is the unique
 * {@code (source, external_ref)}.
 */
public final class SnapshotService {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final String PREFIX = "snapshot-";
    private static final String SER = ".ser";
    private static final String HMAC = ".ser.hmac";
    private static final long MAX_SIGNATURE_FILE_BYTES = 1024;
    private static final String RECOVERY_NOTE = "RECOVERED_FROM_SNAPSHOT";

    /** What the last {@link #recoverMissingOrders()} did. */
    public record Recovery(boolean snapshotFound, int inserted, int alreadyPresent, int conflicts, int skipped) {
    }

    private final Database database;
    private final OrderDao orders;
    private final StatusHistoryDao history;
    private final Path dir;
    private final HmacSigner signer;
    private final long maxBytes;
    private final int retain;
    private final Clock clock;
    private final OrderEventLog events;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final AtomicLong deserializations = new AtomicLong();
    private volatile Recovery lastRecovery = new Recovery(false, 0, 0, 0, 0);
    private volatile Runnable duringWrite = () -> { };

    /** Creates the snapshot directory if it is missing; fails if that is not possible. */
    public SnapshotService(Database database, OrderDao orders, StatusHistoryDao history, Path dir, HmacSigner signer,
                           long maxBytes, int retain, Clock clock, OrderEventLog events) throws SnapshotWriteException {
        this.database = Objects.requireNonNull(database, "database");
        this.orders = Objects.requireNonNull(orders, "orders");
        this.history = Objects.requireNonNull(history, "history");
        this.dir = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
        this.signer = Objects.requireNonNull(signer, "signer");
        this.maxBytes = maxBytes;
        this.retain = retain;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.events = Objects.requireNonNull(events, "events");
        try {
            Files.createDirectories(this.dir);
        } catch (IOException | RuntimeException e) {
            throw new SnapshotWriteException("Cannot create the snapshot directory " + this.dir + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    public Path directory() {
        return dir;
    }

    public Recovery lastRecovery() {
        return lastRecovery;
    }

    /** How many times an {@code ObjectInputStream} was created; a test uses it to prove that rejected files never reach one. */
    long deserializationCount() {
        return deserializations.get();
    }

    /** Test seam: runs while the write lock is held, between reading the orders and writing the files. */
    void setDuringWrite(Runnable hook) {
        this.duringWrite = Objects.requireNonNull(hook, "hook");
    }

    // ------------------------------------------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------------------------------------------

    /** {@link #writeSnapshot()} followed by {@link #pruneOld()}: the job of the periodic task and of the final snapshot. */
    public Path writeAndPrune() throws SnapshotWriteException {
        Path written = writeSnapshot();
        pruneOld();
        return written;
    }

    /** Writes one signed snapshot of the in-flight orders and returns the {@code .ser} file. */
    public Path writeSnapshot() throws SnapshotWriteException {
        writeLock.lock();
        try {
            List<OrderSnapshotDTO> dtos = collect();
            duringWrite.run();
            byte[] data = serialise(dtos);
            if (data.length > maxBytes) {
                throw new SnapshotWriteException("The snapshot of " + dtos.size() + " orders (" + data.length
                        + " bytes) exceeds snapshot.max.bytes and could not be read back", null);
            }
            String signature = signer.signHex(data);
            Instant at = monotonic(clock.instant());
            Path ser = dir.resolve(PREFIX + STAMP.format(at) + SER);
            Path hmac = dir.resolve(ser.getFileName() + ".hmac");
            while (Files.exists(ser) || Files.exists(hmac)) {
                at = at.plusMillis(1);
                ser = dir.resolve(PREFIX + STAMP.format(at) + SER);
                hmac = dir.resolve(ser.getFileName() + ".hmac");
            }
            publish(ser, hmac, data, signature);
            events.log(EventType.SNAPSHOT_WRITTEN, 0L, "orders=" + dtos.size() + " file=" + ser.getFileName());
            return ser;
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * The file name carries the time and the newest name wins when snapshots are read, so a new name must sort after every
     * existing one: after a pruned file's name became free again, or after the clock was set back, the same instant would
     * otherwise give a new snapshot an <em>older</em> name than a stale one.
     */
    private Instant monotonic(Instant candidate) {
        try {
            for (Path newest : listSerFiles()) {
                String name = newest.getFileName().toString();
                try {
                    Instant newestAt = Instant.from(STAMP.parse(name.substring(PREFIX.length(), name.length() - SER.length())));
                    return candidate.isAfter(newestAt) ? candidate : newestAt.plusMillis(1);
                } catch (java.time.DateTimeException | IndexOutOfBoundsException e) {
                    continue; // a file that does not follow the naming scheme does not take part in the ordering
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("The snapshot directory could not be listed to order the new file name ({})", e.getClass().getSimpleName());
        }
        return candidate;
    }

    private List<OrderSnapshotDTO> collect() throws SnapshotWriteException {
        try {
            return database.inTransaction(c -> {
                List<OrderSnapshotDTO> out = new ArrayList<>();
                for (Order order : orders.findNonTerminalForProcessing(c)) {
                    boolean deducted = orders.findState(c, order.getId()).map(OrderDao.OrderState::stockDeducted).orElse(false);
                    out.add(toDto(order, deducted));
                }
                return out;
            });
        } catch (CloudKitchenException | RuntimeException e) {
            throw new SnapshotWriteException("The in-flight orders could not be read for the snapshot (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static OrderSnapshotDTO toDto(Order order, boolean stockDeducted) {
        var payment = order.processPayment();
        List<OrderSnapshotDTO.ItemLine> lines = new ArrayList<>();
        for (OrderItem item : order.getItems()) {
            lines.add(new OrderSnapshotDTO.ItemLine(item.getItemId(), item.getItemName(), item.getUnitPrice().toPlainString(), item.getQuantity()));
        }
        return new OrderSnapshotDTO(OrderSnapshotDTO.CURRENT_FORMAT_VERSION, order.getId(), order.getSource().name(), order.getExternalRef(),
                order.getTrackingCode(), order.getStationId(), order.getBrandCode(), order.getStatus().name(), order.getCustomerName(),
                order.getDeliveryDistanceKm().toPlainString(), order.getPaymentMode().name(), payment.status().name(),
                payment.platformFee().toPlainString(), order.getSubtotal().toPlainString(), order.getCreatedAt().toEpochMilli(),
                order.getEstimatedDeliveryAt() == null ? 0L : order.getEstimatedDeliveryAt().toEpochMilli(), stockDeducted, lines);
    }

    static byte[] serialise(List<OrderSnapshotDTO> dtos) throws SnapshotWriteException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new ArrayList<>(dtos));
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new SnapshotWriteException("The snapshot could not be serialised", e);
        }
    }

    private void publish(Path ser, Path hmac, byte[] data, String signature) throws SnapshotWriteException {
        Path serTmp = dir.resolve(ser.getFileName() + ".tmp");
        Path hmacTmp = dir.resolve(hmac.getFileName() + ".tmp");
        boolean serMoved = false;
        try {
            Files.createDirectories(dir);
            writeDurably(serTmp, data);
            writeDurably(hmacTmp, (signature + "\n").getBytes(StandardCharsets.UTF_8));
            move(serTmp, ser);
            serMoved = true;
            move(hmacTmp, hmac);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(serTmp);
            deleteQuietly(hmacTmp);
            if (serMoved) {
                deleteQuietly(ser); // never leave a signed-looking file without its signature
            }
            throw new SnapshotWriteException("The snapshot " + ser.getFileName() + " could not be written (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static void writeDurably(Path file, byte[] bytes) throws IOException {
        Files.write(file, bytes);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.warn("Could not remove {} ({})", file.getFileName(), e.getClass().getSimpleName());
        }
    }

    /**
     * Keeps the newest {@code snapshot.retain.count} complete pairs (a {@code .ser} with its {@code .hmac}) and deletes only older
     * complete pairs and stale {@code snapshot-*.tmp} files. It does <em>not</em> remove orphans: a {@code .ser} without a
     * {@code .hmac} (an unfinished write) or a {@code .hmac} without a {@code .ser} may remain until an operator deletes it.
     */
    public void pruneOld() {
        writeLock.lock();
        try {
            List<Path> sers = listSerFiles();
            List<Path> complete = new ArrayList<>();
            for (Path ser : sers) {
                if (Files.exists(signatureOf(ser))) {
                    complete.add(ser);
                }
            }
            for (int i = retain; i < complete.size(); i++) {
                deleteQuietly(complete.get(i));
                deleteQuietly(signatureOf(complete.get(i)));
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*.tmp")) {
                for (Path tmp : stream) {
                    deleteQuietly(tmp); // under the write lock, so no write of ours is in progress
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Pruning the snapshot directory failed ({})", e.getClass().getSimpleName());
        } finally {
            writeLock.unlock();
        }
    }

    private static Path signatureOf(Path ser) {
        return ser.resolveSibling(ser.getFileName() + ".hmac");
    }

    /** All {@code .ser} files, newest name first. */
    private List<Path> listSerFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SER)) {
            for (Path p : stream) {
                files.add(p);
            }
        }
        files.sort(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed());
        return files;
    }

    // ------------------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------------------

    /** The orders of the newest snapshot that passes every check, or empty; each rejected file is logged as {@code SNAPSHOT_REJECTED}. */
    public Optional<List<OrderSnapshotDTO>> readLatestValid() {
        List<Path> files;
        try {
            files = listSerFiles();
        } catch (IOException | RuntimeException e) {
            LOG.warn("The snapshot directory {} could not be listed ({})", dir, e.getClass().getSimpleName());
            return Optional.empty();
        }
        for (Path ser : files) {
            String name = ser.getFileName().toString();
            Path signature = signatureOf(ser);
            if (!Files.exists(signature)) {
                events.log(EventType.SNAPSHOT_REJECTED, 0L, "file=" + name + " reason=no signature file (unfinished write)");
                continue;
            }
            try {
                return Optional.of(read(ser, signature));
            } catch (SnapshotIntegrityException e) {
                events.log(EventType.SNAPSHOT_REJECTED, 0L, "file=" + name + " reason=" + e.getMessage());
                LOG.warn("Snapshot {} rejected: {}", name, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Verifies and deserialises one snapshot pair; see the class comment for the order of the gates. */
    List<OrderSnapshotDTO> read(Path ser, Path signature) throws SnapshotIntegrityException {
        byte[] data;
        String signatureText;
        try {
            if (Files.size(ser) > maxBytes) {
                throw new SnapshotIntegrityException("snapshot file larger than snapshot.max.bytes");
            }
            if (Files.size(signature) > MAX_SIGNATURE_FILE_BYTES) {
                throw new SnapshotIntegrityException("signature file too large");
            }
            data = Files.readAllBytes(ser);
            signatureText = new String(Files.readAllBytes(signature), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SnapshotIntegrityException("snapshot file unreadable (" + e.getClass().getSimpleName() + ")", e);
        }
        if (!signer.verifyHex(data, signatureText)) {
            throw new SnapshotIntegrityException("signature missing, malformed or not matching the content");
        }
        return deserialise(data);
    }

    private List<OrderSnapshotDTO> deserialise(byte[] data) throws SnapshotIntegrityException {
        deserializations.incrementAndGet();
        Object result;
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            in.setObjectInputFilter(SnapshotFilterFactory.create(maxBytes));
            result = in.readObject();
        } catch (InvalidClassException e) {
            throw new SnapshotIntegrityException("class rejected by the allowlist or incompatible (" + simpleReason(e) + ")", e);
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            throw new SnapshotIntegrityException("snapshot content malformed or truncated (" + e.getClass().getSimpleName() + ")", e);
        }
        if (!(result instanceof ArrayList<?> list)) {
            throw new SnapshotIntegrityException("snapshot content is not a list of orders");
        }
        List<OrderSnapshotDTO> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof OrderSnapshotDTO dto)) {
                throw new SnapshotIntegrityException("snapshot list holds an element that is not an order");
            }
            if (dto.formatVersion() != OrderSnapshotDTO.CURRENT_FORMAT_VERSION) {
                throw new SnapshotIntegrityException("unsupported snapshot format version " + dto.formatVersion()
                        + " (this code reads only " + OrderSnapshotDTO.CURRENT_FORMAT_VERSION + ")");
            }
            out.add(dto);
        }
        return out;
    }

    /** The exception message names classes only (never content); this keeps just its first sentence. */
    private static String simpleReason(InvalidClassException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.length() > 120 ? m.substring(0, 120) : m;
    }

    // ------------------------------------------------------------------------------------------------------
    // Restoration
    // ------------------------------------------------------------------------------------------------------

    private enum Outcome { INSERTED, PRESENT, CONFLICT }

    /**
     * Inserts the orders of the newest valid snapshot that the database lacks and returns how many were inserted. Never
     * throws for a missing, rejected or partly unusable snapshot (policy in the class comment).
     */
    public int recoverMissingOrders() {
        Optional<List<OrderSnapshotDTO>> found = readLatestValid();
        if (found.isEmpty()) {
            lastRecovery = new Recovery(false, 0, 0, 0, 0);
            return 0;
        }
        int inserted = 0;
        int present = 0;
        int conflicts = 0;
        int skipped = 0;
        for (OrderSnapshotDTO dto : found.get()) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                switch (insertIfMissing(dto)) {
                    case INSERTED -> inserted++;
                    case PRESENT -> present++;
                    case CONFLICT -> conflicts++;
                }
            } catch (IllegalArgumentException | NullPointerException | IllegalStateException | ArithmeticException e) {
                skipped++;
                LOG.warn("Snapshot order {} skipped, its content is not valid ({})", dto.orderId(), e.getClass().getSimpleName());
            } catch (CloudKitchenException e) {
                skipped++;
                LOG.warn("Snapshot order {} skipped, it could not be stored ({})", dto.orderId(), e.getCode());
            }
        }
        lastRecovery = new Recovery(true, inserted, present, conflicts, skipped);
        LOG.info("Snapshot recovery: {} inserted, {} already present, {} conflicting, {} skipped", inserted, present, conflicts, skipped);
        return inserted;
    }

    private Outcome insertIfMissing(OrderSnapshotDTO dto) throws CloudKitchenException {
        OrderSource source = OrderSource.valueOf(dto.source());
        OrderStatus status = OrderStatus.valueOf(dto.status());
        if (status.isTerminal()) {
            throw new IllegalArgumentException("a snapshot holds only in-flight orders");
        }
        List<OrderItem> items = new ArrayList<>();
        for (OrderSnapshotDTO.ItemLine line : dto.items()) {
            items.add(new OrderItem(line.itemId(), line.itemName(), new BigDecimal(line.unitPrice()), line.quantity()));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        Order order = OrderFactory.rehydrate(null, source, dto.externalRef(), dto.trackingCode(), dto.stationId(), dto.brandCode(), status,
                dto.customerName(), new BigDecimal(dto.deliveryDistanceKm()), PaymentMode.valueOf(dto.paymentMode()), items, List.of(),
                Instant.ofEpochMilli(dto.createdAtEpochMillis()));
        Instant now = clock.instant();
        try {
            return database.inTransaction(c -> {
                var existing = orders.findIdBySourceAndRef(c, source, dto.externalRef());
                if (existing.isPresent()) {
                    OrderStatus current = orders.findState(c, existing.get()).map(OrderDao.OrderState::status).orElse(null);
                    return current == status ? Outcome.PRESENT : Outcome.CONFLICT;
                }
                long id = orders.insertRecovered(c, order);
                orders.insertItems(c, id, items);
                history.insert(c, new StatusHistoryEntry(id, null, OrderStatus.PLACED, order.getCreatedAt(), "SYSTEM", RECOVERY_NOTE));
                OrderStatus from = OrderStatus.PLACED;
                for (OrderStatus next : pathTo(status)) {
                    history.insert(c, new StatusHistoryEntry(id, from, next, now, "SYSTEM", RECOVERY_NOTE));
                    from = next;
                }
                return Outcome.INSERTED;
            });
        } catch (CloudKitchenException e) {
            if (SqlErrors.isDuplicateKey(e)) {
                // another writer inserted the same order, or the tracking code belongs to a different order
                boolean sameOrder = Boolean.TRUE.equals(database.withConnection(c -> orders.findIdBySourceAndRef(c, source, dto.externalRef()).isPresent()));
                return sameOrder ? Outcome.PRESENT : Outcome.CONFLICT;
            }
            throw e;
        }
    }

    /** The lifecycle statuses after {@code PLACED} up to and including {@code target} (the in-flight statuses are a straight line). */
    private static List<OrderStatus> pathTo(OrderStatus target) {
        List<OrderStatus> path = new ArrayList<>();
        for (OrderStatus s : List.of(OrderStatus.VALIDATED, OrderStatus.ROUTED, OrderStatus.PREPARING, OrderStatus.PACKED, OrderStatus.OUT_FOR_DELIVERY)) {
            if (target == OrderStatus.PLACED) {
                break;
            }
            path.add(s);
            if (s == target) {
                break;
            }
        }
        return path;
    }
}
