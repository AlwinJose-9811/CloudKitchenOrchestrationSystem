package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.SubmissionResult;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.FakeDispatchers;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.io.ObjectStreamClass;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-SER-01, FR-SER-02, FR-RES-05, SDD 9.4: what a snapshot holds, how the files are named and kept, and that writes never overlap. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotRoundTripTest {

    private static final Set<Class<?>> PLAIN = Set.of(int.class, long.class, boolean.class, String.class, java.util.ArrayList.class);

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @Test
    void theDtoHoldsPlainFieldsOnlyAndDeclaresItsSerialVersionUid() throws Exception {
        for (Class<?> type : List.of(OrderSnapshotDTO.class, OrderSnapshotDTO.ItemLine.class)) {
            Field uid = type.getDeclaredField("serialVersionUID");
            assertTrue(Modifier.isStatic(uid.getModifiers()) && Modifier.isFinal(uid.getModifiers()), type + " declares a static final serialVersionUID");
            assertEquals(1L, ObjectStreamClass.lookup(type).getSerialVersionUID());
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                boolean plain = PLAIN.contains(field.getType());
                assertTrue(plain, type.getSimpleName() + "." + field.getName() + " has the non-plain type " + field.getType());
                assertFalse(Modifier.isTransient(field.getModifiers()), "nothing is hidden from the format");
            }
        }
        assertEquals(OrderSnapshotDTO.ItemLine.class,
                ((java.lang.reflect.ParameterizedType) OrderSnapshotDTO.class.getDeclaredField("items").getGenericType()).getActualTypeArguments()[0]);
    }

    @Test
    void writesTheInFlightOrdersOnlyAndReadsThemBackUnchanged() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long routed = f.submit("RT-1", "BIRYANI", "Chicken Biryani", 2, "Raita", 1);
        long second = f.submit("RT-2", "BURGER", "Classic Burger", 1);
        long cancelled = f.submit("RT-3", "BIRYANI", "Raita", 1);
        assertTrue(h.service.cancelOrder(cancelled, "staff"));
        h.dispatcher.use(new FakeDispatchers.AlwaysFull());
        h.stockFaults.failAlways("restore", new java.sql.SQLTransientConnectionException("outage", "08S01", 0));
        SubmissionResult pending = h.service.submitOrder(h.t.request(OrderSource.ZOMATO, "RT-P", "BIRYANI", "Raita", 1));
        assertFalse(pending.compensated(), "this order is awaiting compensation");
        h.stockFaults.clearFaults();
        h.dispatcher.use(new FakeDispatchers.Accepting());

        Path ser = f.service.writeSnapshot();
        Path hmac = SnapshotFixture.signatureOf(ser);
        assertTrue(ser.getFileName().toString().matches("snapshot-\\d{8}T\\d{9}Z\\.ser"), ser.getFileName().toString());
        assertTrue(Files.exists(hmac));
        assertTrue(Files.readString(hmac, StandardCharsets.UTF_8).matches("[0-9a-f]{64}\n"), "the signature file holds 64 lowercase hex digits and a line break");
        assertEquals(1, h.events.count(EventType.SNAPSHOT_WRITTEN));

        List<OrderSnapshotDTO> read = f.service.read(ser, hmac);
        assertEquals(Set.of(routed, second), idsOf(read), "terminal and pending-compensation orders are not in a snapshot");
        OrderSnapshotDTO dto = read.stream().filter(d -> d.orderId() == routed).findFirst().orElseThrow();
        assertEquals(OrderSnapshotDTO.CURRENT_FORMAT_VERSION, dto.formatVersion());
        assertEquals("SWIGGY", dto.source());
        assertEquals("RT-1", dto.externalRef());
        assertEquals("ROUTED", dto.status());
        assertEquals("BIRYANI", dto.brandCode());
        assertEquals("Asha Rao", dto.customerName());
        assertEquals("6.10", dto.deliveryDistanceKm());
        assertEquals("547.00", dto.totalAmount());
        assertTrue(new java.math.BigDecimal(dto.platformFee()).signum() > 0, "the platform fee is part of the snapshot");
        assertTrue(dto.stockDeducted());
        assertEquals(2, dto.items().size());
        assertEquals(2, dto.items().get(0).quantity());
        assertEquals("249.00", dto.items().get(0).unitPrice());
        assertEquals(h.clock.instant().toEpochMilli(), dto.createdAtEpochMillis());
        assertNotNull(dto.trackingCode());
        assertEquals(Optional.of(read.size()), f.service.readLatestValid().map(List::size));
    }

    private static Set<Long> idsOf(List<OrderSnapshotDTO> dtos) {
        Set<Long> ids = new HashSet<>();
        dtos.forEach(d -> ids.add(d.orderId()));
        return ids;
    }

    @Test
    void anUnfinishedWriteWithoutASignatureIsIgnoredAndTheOlderCompleteSnapshotIsUsed() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long id = f.submit("UW-1", "BIRYANI", "Raita", 1);
        Path good = f.service.writeSnapshot();
        Path orphan = good.resolveSibling("snapshot-20990101T000000000Z.ser");
        Files.write(orphan, Files.readAllBytes(good));
        Optional<List<OrderSnapshotDTO>> read = f.service.readLatestValid();
        assertEquals(Set.of(id), idsOf(read.orElseThrow()));
        assertEquals(1, h.events.count(EventType.SNAPSHOT_REJECTED), "the unfinished file was reported");
    }

    @Test
    void namesAreUniqueEvenWhenTheClockDoesNotMoveAndOnlyTheNewestPairsAreKept() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 3);
        f.submit("PR-1", "BIRYANI", "Raita", 1);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 5; i++) { // the fake clock never advances: five writes in the same millisecond
            names.add(f.service.writeAndPrune().getFileName().toString());
        }
        assertEquals(5, new HashSet<>(names).size(), "five different names");
        List<Path> kept = f.serFiles();
        assertEquals(names.subList(2, 5), kept.stream().map(p -> p.getFileName().toString()).toList(), "the newest three pairs remain");
        for (Path ser : kept) {
            assertTrue(Files.exists(SnapshotFixture.signatureOf(ser)));
        }
        try (var stream = Files.list(f.dir)) {
            assertEquals(6, stream.count(), "three .ser and three .hmac, no temporary file");
        }
    }

    @Test
    void aClockSetBackNeverGivesANewSnapshotAnOlderNameThanAStaleOne() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long first = f.submit("CB-1", "BIRYANI", "Raita", 1);
        Path before = f.service.writeSnapshot();
        long second = f.submit("CB-2", "BURGER", "Classic Burger", 1);
        h.clock.set(h.clock.instant().minus(java.time.Duration.ofHours(1)));
        Path after = f.service.writeSnapshot();
        assertTrue(after.getFileName().toString().compareTo(before.getFileName().toString()) > 0, "the newer snapshot sorts last: " + before + " / " + after);
        assertEquals(Set.of(first, second), idsOf(f.service.readLatestValid().orElseThrow()), "readers use the newer content");
    }

    @RepeatedTest(20)
    void concurrentWritesNeverOverlapAndEveryFileIsComplete() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 100);
        f.submit("CW-1", "BIRYANI", "Raita", 1);
        f.submit("CW-2", "BURGER", "Classic Burger", 1);
        int writers = 6;
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        AtomicInteger arrived = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Thread> owner = new java.util.concurrent.atomic.AtomicReference<>();
        CountDownLatch ownerInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        f.service.setDuringWrite(() -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            if (first.compareAndSet(false, true)) {
                owner.set(Thread.currentThread());
                ownerInside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            inside.decrementAndGet();
        });
        List<Thread> threads = new ArrayList<>();
        List<Path> files = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < writers; i++) {
            Thread t = new Thread(() -> {
                try {
                    arrived.incrementAndGet();
                    files.add(f.service.writeSnapshot());
                } catch (Throwable e) {
                    errors.add(e);
                }
            }, "test-snapshot-writer-" + i);
            threads.add(t);
        }
        try {
            threads.forEach(Thread::start);
            assertTrue(ownerInside.await(30, TimeUnit.SECONDS), "one writer is inside, holding the write lock");
            // every other writer has arrived and is parked on the lock, none is inside
            assertTrue(com.cloudkitchen.testsupport.PipelineHarness.awaitCondition(java.time.Duration.ofSeconds(30), () -> arrived.get() == writers
                    && threads.stream().filter(t -> t != owner.get()).allMatch(t -> t.getState() == Thread.State.WAITING)),
                    "the other five writers wait for the lock");
            assertEquals(1, maxInside.get(), "while the first writer is busy nobody else entered");
            release.countDown();
            for (Thread t : threads) {
                t.join(60_000);
            }
            assertEquals(List.of(), errors);
            assertEquals(writers, new HashSet<>(files).size(), "six different files");
            assertEquals(1, maxInside.get(), "never two writers at the same time");
            for (Path ser : files) {
                assertEquals(2, f.service.read(ser, SnapshotFixture.signatureOf(ser)).size(), "every snapshot is complete and consistent");
            }
        } finally {
            release.countDown();
        }
    }
}
