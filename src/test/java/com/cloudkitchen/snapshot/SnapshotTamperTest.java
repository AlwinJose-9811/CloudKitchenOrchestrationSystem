package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudkitchen.exception.SnapshotIntegrityException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** FR-SER-03: any modified, truncated, wrongly signed or malformed snapshot is rejected before an ObjectInputStream exists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotTamperTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private SnapshotFixture fixtureWithOneSnapshot() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        f.submit("TP-1", "BIRYANI", "Chicken Biryani", 2, "Raita", 1);
        f.submit("TP-2", "BURGER", "Classic Burger", 1);
        f.service.writeSnapshot();
        return f;
    }

    private void assertRejectedWithoutDeserialising(SnapshotFixture f, Path ser, String what) {
        long before = f.service.deserializationCount();
        assertThrows(SnapshotIntegrityException.class, () -> f.service.read(ser, SnapshotFixture.signatureOf(ser)), what);
        assertEquals(before, f.service.deserializationCount(), what + ": no ObjectInputStream was created");
    }

    @Test
    void aSingleChangedByteAnywhereInTheSnapshotIsRejectedBeforeDeserialisation() throws Exception {
        SnapshotFixture f = fixtureWithOneSnapshot();
        Path ser = f.serFiles().get(0);
        byte[] original = Files.readAllBytes(ser);
        assertEquals(1, f.service.read(ser, SnapshotFixture.signatureOf(ser)).stream().filter(d -> d.externalRef().equals("TP-1")).count(), "the untouched file is fine");
        long reads = f.service.deserializationCount();
        for (int index : new int[] {0, 1, 4, original.length / 2, original.length - 1}) {
            byte[] changed = original.clone();
            changed[index] ^= 0x01;
            Files.write(ser, changed);
            assertRejectedWithoutDeserialising(f, ser, "byte " + index);
        }
        assertEquals(reads, f.service.deserializationCount());
    }

    @Test
    void aTruncatedExtendedOrEmptySnapshotFileIsRejected() throws Exception {
        SnapshotFixture f = fixtureWithOneSnapshot();
        Path ser = f.serFiles().get(0);
        byte[] original = Files.readAllBytes(ser);
        Files.write(ser, Arrays.copyOf(original, original.length - 1));
        assertRejectedWithoutDeserialising(f, ser, "one byte missing");
        Files.write(ser, Arrays.copyOf(original, original.length / 2));
        assertRejectedWithoutDeserialising(f, ser, "half of the file");
        Files.write(ser, new byte[0]);
        assertRejectedWithoutDeserialising(f, ser, "empty file");
        byte[] longer = Arrays.copyOf(original, original.length + 1);
        Files.write(ser, longer);
        assertRejectedWithoutDeserialising(f, ser, "one byte appended");
    }

    @Test
    void aChangedMalformedOrMissingSignatureIsRejected() throws Exception {
        SnapshotFixture f = fixtureWithOneSnapshot();
        Path ser = f.serFiles().get(0);
        Path hmac = SnapshotFixture.signatureOf(ser);
        String good = Files.readString(hmac, StandardCharsets.UTF_8).trim();
        char first = good.charAt(0);
        for (String bad : new String[] {(first == '0' ? '1' : '0') + good.substring(1), good.substring(1), good + "00", good.toUpperCase(), "", "zz" + good.substring(2),
                good.substring(0, 32)}) {
            Files.writeString(hmac, bad + "\n", StandardCharsets.UTF_8);
            assertRejectedWithoutDeserialising(f, ser, "signature " + bad);
        }
        Files.write(hmac, new byte[2048]);
        assertRejectedWithoutDeserialising(f, ser, "an oversized signature file");
        Files.writeString(hmac, good + "\n", StandardCharsets.UTF_8);
        assertEquals(2, f.service.read(ser, hmac).size(), "restored, it verifies again");
    }

    @Test
    void aSnapshotSignedWithAnotherKeyIsRejected() throws Exception {
        SnapshotFixture f = fixtureWithOneSnapshot();
        Path ser = f.serFiles().get(0);
        SnapshotService otherKey = new SnapshotService(h.db.database(), h.t.orders, h.t.history, f.dir,
                new HmacSigner(SnapshotFixture.SECRET + "-another"), 5_242_880, 10, h.clock, h.events);
        long before = otherKey.deserializationCount();
        assertThrows(SnapshotIntegrityException.class, () -> otherKey.read(ser, SnapshotFixture.signatureOf(ser)));
        assertEquals(before, otherKey.deserializationCount());
        assertEquals(Optional.empty(), otherKey.readLatestValid(), "nothing in the directory verifies with this key");
    }

    @Test
    void readLatestValidReportsEveryRejectedFileAndNeverDeserialisesIt() throws Exception {
        SnapshotFixture f = fixtureWithOneSnapshot();
        Path ser = f.serFiles().get(0);
        byte[] changed = Files.readAllBytes(ser);
        changed[changed.length / 2] ^= 0x10;
        Files.write(ser, changed);
        long before = f.service.deserializationCount();
        assertEquals(Optional.empty(), f.service.readLatestValid());
        assertEquals(before, f.service.deserializationCount());
        assertEquals(1, h.events.count(EventType.SNAPSHOT_REJECTED));
        assertTrue(h.events.all().stream().anyMatch(e -> e.type() == EventType.SNAPSHOT_REJECTED && e.detail().contains("signature")), h.events.all().toString());
    }

    @Test
    void anEmptyOrMissingDirectoryIsNotAnError() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        assertEquals(Optional.empty(), f.service.readLatestValid());
        assertEquals(List.of(), f.serFiles());
        Files.delete(f.dir);
        assertEquals(Optional.empty(), f.service.readLatestValid(), "a directory removed after start-up is reported, not thrown");
    }
}
