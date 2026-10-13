package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.FencingToken;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** RW-6, rules L-1..L-3: the lease predicates of acquire, renew and release, driven with the fake clock. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaseFencingTest {

    private static final String ROW = "SELECT CONCAT_WS('|', status, claim_epoch, IFNULL(claimed_by, '-'), IFNULL(lease_expires_at, '-'))"
            + " FROM orders WHERE id = ?";

    private ServiceHarness h;

    @BeforeAll
    void start() throws Exception {
        h = ServiceHarness.create();
    }

    @AfterAll
    void stop() {
        h.close();
    }

    @BeforeEach
    void clean() throws Exception {
        h.reset();
    }

    private long routed(String ref) throws Exception {
        return h.service.submitOrder(h.t.request(OrderSource.SWIGGY, ref, "BIRYANI", "Chicken Biryani", 2, "Raita", 1)).orderId();
    }

    @Test
    void aRenewalAfterExpiryFailsAndChangesNothing() throws Exception {
        long id = routed("LF-1");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        String before = h.t.text(ROW, id);
        h.clock.advance(Duration.ofSeconds(31));
        assertFalse(h.service.renewLease(token), "an expired lease is not usable");
        assertEquals(before, h.t.text(ROW, id), "and the failed renewal wrote nothing");
    }

    @Test
    void aRenewalAfterTheMonitorClearedTheLeaseFailsAndDoesNotReviveIt() throws Exception {
        long id = routed("LF-2");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        assertEquals(List.of(id), h.service.reclaimLeasesOf("BIRYANI#1"));
        assertFalse(h.service.renewLease(token));
        assertNull(h.t.text("SELECT claimed_by FROM orders WHERE id = ?", id), "a cleared lease stays cleared (F-B1)");
        assertNull(h.t.text("SELECT lease_expires_at FROM orders WHERE id = ?", id));
        assertEquals(1, h.t.number("SELECT claim_epoch FROM orders WHERE id = ?", id), "the epoch is kept, so the old owner stays fenced");
    }

    @Test
    void aSupersededOwnerCannotRenewOrReleaseAndTheNewOwnersLeaseIsUntouched() throws Exception {
        long id = routed("LF-3");
        FencingToken old = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        h.clock.advance(Duration.ofSeconds(31));
        FencingToken fresh = h.service.acquireLease(id, "BIRYANI#2").orElseThrow();
        String before = h.t.text(ROW, id);
        assertFalse(h.service.renewLease(old));
        h.service.releaseLease(old);
        assertEquals(before, h.t.text(ROW, id));
        assertTrue(h.service.renewLease(fresh));
    }

    @Test
    void theDeadZoneBeforeExpiryIsNotUsableByTheOwnerAndNotFreeForOthers() throws Exception {
        long id = routed("LF-4");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        h.clock.advance(Duration.ofMillis(29_900)); // 100 ms before expiry, margin is 200 ms
        assertFalse(h.service.renewLease(token), "usable means expiry > stamp + margin");
        assertTrue(h.service.acquireLease(id, "BIRYANI#2").isEmpty(), "but the lease is still valid for everybody else");
        FencingToken again = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        assertEquals(2, again.epoch(), "the same owner string may take a new epoch (lost reply, FI-6)");
    }

    @Test
    void aRenewalJustInsideTheUsableWindowSucceeds() throws Exception {
        long id = routed("LF-5");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        h.clock.advance(Duration.ofMillis(29_700)); // expiry - stamp = 300 ms > margin 200 ms
        assertTrue(h.service.renewLease(token));
    }

    @Test
    void aClearedLeaseCanBeTakenAtOnceByAnotherOwnerWithAHigherEpoch() throws Exception {
        long id = routed("LF-6");
        h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        h.service.reclaimLeasesOf("BIRYANI#1");
        FencingToken next = h.service.acquireLease(id, "BIRYANI#2").orElseThrow();
        assertEquals(2, next.epoch(), "NULL lease columns count as free");
    }

    @Test
    void anotherOwnersValidLeaseCannotBeTaken() throws Exception {
        long id = routed("LF-7");
        h.service.acquireLease(id, "BIRYANI#1").orElseThrow();
        assertTrue(h.service.acquireLease(id, "BIRYANI#2").isEmpty());
        assertEquals(1, h.t.number("SELECT claim_epoch FROM orders WHERE id = ?", id));
    }

    @Test
    void reclaimMatchesTheExactOwnerStringOnly() throws Exception {
        long id = routed("LF-8");
        FencingToken token = h.service.acquireLease(id, "BIRYANI#12").orElseThrow();
        assertEquals(List.of(), h.service.reclaimLeasesOf("BIRYANI#1"), "BIRYANI#1 is not a prefix match for BIRYANI#12");
        assertTrue(h.service.renewLease(token));
    }
}
