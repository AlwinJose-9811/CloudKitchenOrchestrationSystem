package com.cloudkitchen.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.exception.SnapshotIntegrityException;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * FR-SER-04, SDD 9.2: a stream that carries a <em>correct</em> signature (an attacker who knows the key) is still limited
 * by the allowlist and the size limits. Every case here is validly signed, so the HMAC gate is passed and the filter is
 * the only thing between the stream and the program; a class that is rejected must not run any of its code.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SnapshotFilterTest {

    private static final long MAX = 5_242_880;

    /** A gadget stand-in: if the filter let it through, its {@code readObject} would run. */
    static final class Gadget implements Serializable {
        private static final long serialVersionUID = 1L;
        static final AtomicBoolean RAN = new AtomicBoolean();

        private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
            RAN.set(true);
            in.defaultReadObject();
        }
    }

    static final class Handler implements InvocationHandler, Serializable {
        private static final long serialVersionUID = 1L;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            return null;
        }
    }

    private ServiceHarness h;
    private SnapshotFixture f;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    private SnapshotFixture fixture() throws Exception {
        h.reset();
        Gadget.RAN.set(false);
        f = new SnapshotFixture(h, MAX, 10);
        return f;
    }

    private SnapshotIntegrityException rejected(Object payload) throws Exception {
        SnapshotFixture fx = f != null ? f : fixture();
        Path ser = fx.craftSigned(SnapshotFixture.serialise(payload));
        long before = fx.service.deserializationCount();
        SnapshotIntegrityException e = assertThrows(SnapshotIntegrityException.class, () -> fx.service.read(ser, SnapshotFixture.signatureOf(ser)));
        assertEquals(before + 1, fx.service.deserializationCount(), "the signature was fine, so the stream was opened, with the filter in place");
        assertFalse(Gadget.RAN.get(), "no code of a rejected class ran");
        return e;
    }

    @Test
    void thePatternIsTheDocumentedAllowlistWithItsLimits() {
        assertEquals("com.cloudkitchen.snapshot.OrderSnapshotDTO;com.cloudkitchen.snapshot.OrderSnapshotDTO$ItemLine;java.util.ArrayList;java.lang.String;"
                + "java.lang.Object;maxdepth=5;maxrefs=100000;maxbytes=1234;maxarray=100000;!*", SnapshotFilterFactory.pattern(1234));
    }

    @Test
    void aWellFormedSignedSnapshotPassesTheFilter() throws Exception {
        fixture();
        ArrayList<OrderSnapshotDTO> orders = new ArrayList<>(List.of(SnapshotFixture.dto(1, "OK-1", "ROUTED"), SnapshotFixture.dto(2, "OK-2", "PREPARING")));
        Path ser = f.craftSigned(SnapshotFixture.serialise(orders));
        assertEquals(2, f.service.read(ser, SnapshotFixture.signatureOf(ser)).size());
    }

    @Test
    void aClassThatIsNotOnTheListIsRejectedAndItsCodeNeverRuns() throws Exception {
        fixture();
        SnapshotIntegrityException e = rejected(new ArrayList<>(List.of(new Gadget())));
        assertInstanceOf(InvalidClassException.class, e.getCause());
        assertTrue(e.getCause().getMessage().contains("REJECTED") || e.getCause().getMessage().contains("filter"), e.getCause().getMessage());
    }

    @Test
    void otherJdkCollectionsAndValueClassesAreRejectedToo() throws Exception {
        fixture();
        rejected(new HashMap<String, String>());
        rejected(new ArrayList<>(List.of(new Date())));
        rejected(new ArrayList<>(List.of(Integer.valueOf(1))));
        rejected(new ArrayList<>(List.of(new java.util.LinkedList<String>())));
    }

    @Test
    void aProxyIsRejected() throws Exception {
        fixture();
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Runnable.class, Serializable.class}, new Handler());
        rejected(new ArrayList<>(List.of(proxy)));
    }

    @Test
    void aTooDeepObjectGraphIsRejected() throws Exception {
        fixture();
        ArrayList<Object> inner = new ArrayList<>();
        ArrayList<Object> current = inner;
        for (int i = 0; i < 12; i++) {
            ArrayList<Object> next = new ArrayList<>();
            current.add(next);
            current = next;
        }
        SnapshotIntegrityException e = rejected(inner);
        assertInstanceOf(InvalidClassException.class, e.getCause());
    }

    @Test
    void anArrayOverTheLimitIsRejected() throws Exception {
        fixture();
        rejected(new ArrayList<>(List.of((Object) new String[100_001])));
    }

    @Test
    void tooManyReferencesAreRejected() throws Exception {
        fixture();
        ArrayList<String> many = new ArrayList<>();
        for (int i = 0; i < 100_050; i++) {
            many.add(Integer.toString(i));
        }
        rejected(many);
    }

    @Test
    void aStreamThatIsNotAListOfOrdersIsRejected() throws Exception {
        fixture();
        assertTrue(rejected("just a string").getMessage().contains("not a list"));
        assertTrue(rejected(new ArrayList<>(List.of("a string, not an order"))).getMessage().contains("not an order"));
    }

    @Test
    void garbageAndTruncatedStreamsWithAValidSignatureAreRejected() throws Exception {
        fixture();
        Path garbage = f.craftSigned("this is not a java serialisation stream".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(SnapshotIntegrityException.class, () -> f.service.read(garbage, SnapshotFixture.signatureOf(garbage)));
        byte[] whole = SnapshotFixture.serialise(new ArrayList<>(List.of(SnapshotFixture.dto(1, "TR-1", "ROUTED"))));
        Path cut = f.craftSigned(java.util.Arrays.copyOf(whole, whole.length - 10));
        SnapshotIntegrityException e = assertThrows(SnapshotIntegrityException.class, () -> f.service.read(cut, SnapshotFixture.signatureOf(cut)));
        assertTrue(e.getMessage().contains("malformed or truncated"), e.getMessage());
    }

    @Test
    void aFileOverTheConfiguredSizeIsRejectedBeforeItIsReadOrOpened() throws Exception {
        fixture();
        SnapshotFixture small = new SnapshotFixture(h, 2048, 10);
        ArrayList<OrderSnapshotDTO> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(SnapshotFixture.dto(i + 1, "BIG-" + i, "ROUTED"));
        }
        Path ser = small.craftSigned(SnapshotFixture.serialise(many));
        long before = small.service.deserializationCount();
        SnapshotIntegrityException e = assertThrows(SnapshotIntegrityException.class, () -> small.service.read(ser, SnapshotFixture.signatureOf(ser)));
        assertTrue(e.getMessage().contains("larger than snapshot.max.bytes"), e.getMessage());
        assertEquals(before, small.service.deserializationCount(), "rejected on its size, no stream opened");
    }
}
