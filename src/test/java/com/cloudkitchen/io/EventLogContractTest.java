package com.cloudkitchen.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class EventLogContractTest {

    @Test
    void theNullObjectAcceptsEveryEventAndCloseTwice() {
        OrderEventLog log = new NoOpOrderEventLog();
        assertDoesNotThrow(() -> {
            for (EventType type : EventType.values()) {
                log.log(type);
                log.log(type, 1L);
                log.log(type, 1L, "detail");
            }
            log.close();
            log.close();
        });
    }

    @Test
    void theEventCatalogueMatchesSddSection8() {
        assertEquals(39, EventType.values().length);
        assertEquals(EventType.values().length, new HashSet<>(Arrays.asList(EventType.values())).size());
        for (String required : new String[] {"ORDER_REJECTED", "ORDER_DUPLICATE", "ORDER_VALIDATED", "ORDER_ROUTED", "ORDER_DELIVERED",
                "QUEUE_FULL", "STOCK_RESTORED", "SNAPSHOT_REJECTED", "COMPENSATION_PENDING", "WORKER_DIED", "LEASE_RECLAIMED",
                "RECOVERY_COMPLETED", "SHUTDOWN_COMPLETED"}) {
            assertTrue(Arrays.stream(EventType.values()).anyMatch(t -> t.name().equals(required)), required);
        }
    }
}
