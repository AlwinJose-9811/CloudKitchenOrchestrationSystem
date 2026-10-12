package com.cloudkitchen.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.Role;
import com.cloudkitchen.dto.PendingCompensationView;
import com.cloudkitchen.dto.SessionUser;
import com.cloudkitchen.exception.CloudKitchenException;
import com.cloudkitchen.service.CompensationOperator;
import com.cloudkitchen.testsupport.PipelineHarness;
import com.cloudkitchen.testsupport.ServiceHarness;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** The narrow interface the compensation page uses (C4): the list of pending orders and the guarded retry. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PendingCompensationListTest {

    private static final SessionUser ADMIN = new SessionUser(1, "alice", Role.ADMIN, 1);
    private static final SessionUser COOK = new SessionUser(2, "cook", Role.KITCHEN_STAFF, 1);

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
    void theListHoldsExactlyTheOrdersAwaitingCompensationByIdWithTheirFacts() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            CompensationOperator operator = h.service;
            assertEquals(List.of(), operator.pendingCompensations());
            long first = p.pendingOrder("PL-1");
            long second = p.pendingOrder("PL-2");
            List<PendingCompensationView> rows = operator.pendingCompensations();
            assertEquals(List.of(first, second), rows.stream().map(PendingCompensationView::orderId).toList());
            PendingCompensationView row = rows.get(0);
            assertNotNull(row.brandCode());
            assertNotNull(row.reason());
            assertTrue(row.attempts() >= 1);
            assertEquals(0, row.ageSeconds(), "the fake clock has not moved");
            assertNotNull(row.createdAt());
            assertNotNull(row.nextAttemptAt(), "the harness leaves the next attempt 30 s ahead");
            assertEquals(h.service.pendingCompensationSummary()[0], rows.size(), "the same orders as the dashboard counts");

            assertEquals(CompensationOperator.Outcome.RESOLVED, operator.retry(first, ADMIN));
            assertEquals(List.of(second), operator.pendingCompensations().stream().map(PendingCompensationView::orderId).toList());
            assertEquals(CompensationOperator.Outcome.ALREADY_RESOLVED, operator.retry(first, ADMIN));
            assertEquals(CompensationOperator.Outcome.RESOLVED, operator.retry(second, ADMIN));
            assertEquals(List.of(), operator.pendingCompensations());
        } finally {
            p.stop();
        }
    }

    @Test
    void anOrdinaryOrderIsNeverListedAndANonAdministratorCannotRetry() throws Exception {
        PipelineHarness p = PipelineHarness.start(h, PipelineHarness.fast());
        try {
            long pending = p.pendingOrder("PL-3");
            CloudKitchenException e = assertThrows(CloudKitchenException.class, () -> h.service.retry(pending, COOK));
            assertEquals("FORBIDDEN", e.getCode());
            assertTrue(h.service.pendingCompensations().stream().anyMatch(r -> r.orderId() == pending), "still pending");
            assertEquals(CompensationOperator.Outcome.ALREADY_RESOLVED, h.service.retry(987654321L, ADMIN));
            assertFalse(h.service.pendingCompensations().isEmpty());
            assertEquals(CompensationOperator.Outcome.RESOLVED, h.service.retry(pending, ADMIN));
        } finally {
            p.stop();
        }
    }
}
