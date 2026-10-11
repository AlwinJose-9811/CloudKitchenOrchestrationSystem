package com.cloudkitchen.snapshot;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.testsupport.AppFixture;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A snapshot service over the shared test database and a private directory, plus helpers to craft (validly signed) files. */
final class SnapshotFixture {

    static final String SECRET = "unit-test-secret-0123456789abcdef-0123";

    final ServiceHarness h;
    final Path dir;
    final HmacSigner signer = new HmacSigner(SECRET);
    final SnapshotService service;
    private int crafted;

    SnapshotFixture(ServiceHarness h, long maxBytes, int retain) throws CloudKitchenException {
        this(h, h.t.orders, maxBytes, retain);
    }

    SnapshotFixture(ServiceHarness h, com.cloudkitchen.dao.OrderDao orders, long maxBytes, int retain) throws CloudKitchenException {
        this.h = h;
        this.dir = AppFixture.scratchDirectory().resolve("snapshots");
        this.service = new SnapshotService(h.db.database(), orders, h.t.history, dir, signer, maxBytes, retain, h.clock, h.events);
    }

    long submit(String ref, String brand, Object... itemsAndQuantities) throws CloudKitchenException {
        return h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, brand, itemsAndQuantities)).orderId();
    }

    /** Names of the {@code .ser} files, oldest first. */
    List<Path> serFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (var stream = Files.newDirectoryStream(dir, "snapshot-*.ser")) {
            stream.forEach(out::add);
        }
        out.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    static Path signatureOf(Path ser) {
        return ser.resolveSibling(ser.getFileName() + ".hmac");
    }

    static byte[] serialise(Object payload) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(payload);
        }
        return bytes.toByteArray();
    }

    /** Writes a pair named later than every earlier one, signed correctly with the real key: an attacker who holds the key. */
    Path craftSigned(byte[] content) throws IOException {
        return craft(content, signer.signHex(content));
    }

    Path craft(byte[] content, String signatureText) throws IOException {
        Files.createDirectories(dir);
        // names sort by time; 2099 sorts after every real snapshot of the test clock, and the counter keeps the crafted ones ordered
        Path ser = dir.resolve(String.format("snapshot-20990101T000000%03dZ.ser", ++crafted));
        Files.write(ser, content);
        Files.write(signatureOf(ser), (signatureText + "\n").getBytes(StandardCharsets.UTF_8));
        return ser;
    }

    static OrderSnapshotDTO dto(long id, String ref, String status) {
        return new OrderSnapshotDTO(OrderSnapshotDTO.CURRENT_FORMAT_VERSION, id, "SWIGGY", ref, String.format("TRK%09d", id), 1L, "BIRYANI", status, "Test Person",
                "6.10", "PREPAID_AGGREGATOR", "PAID", "34.85", "697.00", 1_760_000_000_000L, 1_760_001_000_000L, true,
                List.of(new OrderSnapshotDTO.ItemLine(1L, "Chicken Biryani", "250.00", 2), new OrderSnapshotDTO.ItemLine(2L, "Veg Biryani", "199.00", 1)));
    }
}
