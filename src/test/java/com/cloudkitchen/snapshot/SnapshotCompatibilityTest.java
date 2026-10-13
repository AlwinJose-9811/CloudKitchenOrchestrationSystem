package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.SnapshotIntegrityException;
import com.cloudkitchen.io.EventType;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** SDD 9.3: the documented policy for older, newer and incompatible snapshots, proved with real signed streams. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotCompatibilityTest {

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private static OrderSnapshotDTO withVersion(int version) {
        OrderSnapshotDTO base = SnapshotFixture.dto(1, "V-" + version, "ROUTED");
        return new OrderSnapshotDTO(version, base.orderId(), base.source(), base.externalRef(), base.trackingCode(), base.stationId(), base.brandCode(),
                base.status(), base.customerName(), base.deliveryDistanceKm(), base.paymentMode(), base.paymentStatus(), base.platformFee(),
                base.totalAmount(), base.createdAtEpochMillis(), base.estimatedDeliveryAtEpochMillis(), base.stockDeducted(), base.items());
    }

    @Test
    void anOlderOrNewerFormatVersionIsRejectedNotMigrated() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        for (int version : new int[] {0, 2, 99, -1}) {
            Path ser = f.craftSigned(SnapshotFixture.serialise(new ArrayList<>(List.of(withVersion(version)))));
            SnapshotIntegrityException e = assertThrows(SnapshotIntegrityException.class, () -> f.service.read(ser, SnapshotFixture.signatureOf(ser)));
            assertTrue(e.getMessage().contains("unsupported snapshot format version " + version), e.getMessage());
        }
        Path current = f.craftSigned(SnapshotFixture.serialise(new ArrayList<>(List.of(withVersion(OrderSnapshotDTO.CURRENT_FORMAT_VERSION)))));
        assertEquals(1, f.service.read(current, SnapshotFixture.signatureOf(current)).size(), "the current version is accepted");
    }

    @Test
    void aStreamWithAnotherSerialVersionUidIsRejectedByTheJavaSerialisationCheck() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        byte[] stream = SnapshotFixture.serialise(new ArrayList<>(List.of(SnapshotFixture.dto(1, "UID-1", "ROUTED"))));
        byte[] name = "com.cloudkitchen.snapshot.OrderSnapshotDTO".getBytes(StandardCharsets.UTF_8);
        int at = indexOf(stream, name);
        assertTrue(at > 0, "the class descriptor is in the stream");
        int uidAt = at + name.length; // the 8 bytes after the class name are the serialVersionUID of the writer
        byte[] expected = {0, 0, 0, 0, 0, 0, 0, 1};
        for (int i = 0; i < 8; i++) {
            assertEquals(expected[i], stream[uidAt + i], "the writer's serialVersionUID is 1");
        }
        stream[uidAt + 7] = 2; // a writer with serialVersionUID 2L, i.e. a class that changed incompatibly
        Path ser = f.craftSigned(stream);
        SnapshotIntegrityException e = assertThrows(SnapshotIntegrityException.class, () -> f.service.read(ser, SnapshotFixture.signatureOf(ser)));
        assertTrue(e.getMessage().contains("incompatible") || e.getMessage().contains("allowlist"), e.getMessage());
        assertTrue(e.getCause() instanceof java.io.InvalidClassException, String.valueOf(e.getCause()));
    }

    @Test
    void anIncompatibleNewestFileIsReportedAndTheNewestCompatibleOneIsUsed() throws Exception {
        h.reset();
        SnapshotFixture f = new SnapshotFixture(h, 5_242_880, 10);
        long id = f.submit("OLD-1", "BIRYANI", "Raita", 1);
        f.service.writeSnapshot();
        f.craftSigned(SnapshotFixture.serialise(new ArrayList<>(List.of(withVersion(7)))));
        Optional<List<OrderSnapshotDTO>> read = f.service.readLatestValid();
        assertEquals(id, read.orElseThrow().get(0).orderId(), "fell back to the older, compatible snapshot");
        assertEquals(1, h.events.count(EventType.SNAPSHOT_REJECTED));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
