package com.cloudkitchen.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.SubmissionResult.Outcome;
import com.cloudkitchen.testsupport.TestData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DtoTest {

    @Test
    void orderRequestStripsStringsAndCopiesItsItems() {
        List<OrderItemRequest> items = new ArrayList<>(List.of(new OrderItemRequest(1, 1)));
        OrderRequest request = new OrderRequest(OrderSource.SWIGGY, "  SW-1 ", " BIRYANI ", "  Asha  ", BigDecimal.ONE,
                PaymentMode.PREPAID_AGGREGATOR, items);
        items.add(new OrderItemRequest(2, 2));
        assertEquals("SW-1", request.externalRef());
        assertEquals("BIRYANI", request.brandCode());
        assertEquals("Asha", request.customerName());
        assertEquals(1, request.items().size(), "later changes to the caller's list do not reach the request");
        assertThrows(UnsupportedOperationException.class, () -> request.items().clear());
        assertNull(new OrderRequest(null, null, null, null, null, null, null).items());
    }

    @Test
    void validationResultMergeAndValid() {
        assertTrue(ValidationResult.valid().isValid());
        ValidationResult a = new ValidationResult(List.of(new FieldError("a", "A", "m")));
        ValidationResult b = new ValidationResult(List.of(new FieldError("b", "B", "m")));
        ValidationResult merged = a.merge(b);
        assertFalse(merged.isValid());
        assertEquals(List.of("A", "B"), merged.errors().stream().map(FieldError::code).toList());
        assertThrows(UnsupportedOperationException.class, () -> merged.errors().clear());
    }

    @Test
    void submissionResultFactoriesSetTheRightFields() {
        SubmissionResult accepted = SubmissionResult.accepted(5);
        assertEquals(Outcome.ACCEPTED, accepted.outcome());
        assertEquals(5L, accepted.orderId());
        assertNull(accepted.reasonCode());

        SubmissionResult duplicate = SubmissionResult.duplicate(5, OrderStatus.ROUTED, false);
        assertEquals(Outcome.DUPLICATE, duplicate.outcome());
        assertEquals(OrderStatus.ROUTED, duplicate.existingStatus());

        SubmissionResult rejected = SubmissionResult.rejected(SubmissionResult.VALIDATION, List.of(new FieldError("f", "C", "m")));
        assertEquals(Outcome.REJECTED, rejected.outcome());
        assertNull(rejected.orderId(), "a rejection creates no order");
        assertEquals(1, rejected.errors().size());
        assertTrue(SubmissionResult.rejected(SubmissionResult.INSUFFICIENT_STOCK).errors().isEmpty());

        SubmissionResult compensated = SubmissionResult.queueFull(9, true);
        assertEquals(Outcome.QUEUE_FULL, compensated.outcome());
        assertTrue(compensated.compensated());
        assertFalse(compensated.compensationPending());
        SubmissionResult pending = SubmissionResult.queueFull(9, false);
        assertFalse(pending.compensated());
        assertTrue(pending.compensationPending(), "the durable PENDING marker was committed instead");

        SubmissionResult indeterminate = SubmissionResult.indeterminate(9);
        assertEquals(Outcome.INDETERMINATE, indeterminate.outcome());
    }

    @Test
    void aRejectionNeedsOneOfTheDocumentedReasons() {
        assertThrows(IllegalArgumentException.class, () -> SubmissionResult.rejected("BECAUSE"));
        assertThrows(IllegalArgumentException.class, () -> SubmissionResult.rejected(SubmissionResult.QUEUE_FULL), "queue full has its own outcome");
        assertThrows(IllegalArgumentException.class, () -> SubmissionResult.rejected(null));
    }

    @Test
    void pageComputesTotalPagesWithAtLeastOne() {
        assertEquals(1, new Page<>(List.of(), 1, 20, 0).totalPages());
        assertEquals(1, new Page<>(List.of(), 1, 20, 20).totalPages());
        assertEquals(2, new Page<>(List.of(), 1, 20, 21).totalPages());
        assertEquals(5, new Page<>(List.of(), 1, 20, 100).totalPages());
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 0, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Page<>(List.of(), 1, 20, -1));
        List<String> source = new ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(source, 1, 20, 1);
        source.add("b");
        assertEquals(1, page.items().size());
    }

    @Test
    void orderFilterNormalisesTextAndChecksTheRange() {
        assertNull(new OrderFilter(null, null, "   ", null, null).externalRefContains());
        assertEquals("SW", new OrderFilter(null, null, " SW ", null, null).externalRefContains());
        assertThrows(IllegalArgumentException.class, () -> new OrderFilter(null, null, "x".repeat(65), null, null));
        Instant now = TestData.NOW;
        assertThrows(IllegalArgumentException.class, () -> new OrderFilter(null, null, null, now, now.minusSeconds(1)));
        new OrderFilter(null, null, null, now, now);
        assertNull(OrderFilter.any().status());
    }

    @Test
    void fencingTokenRequiresAnOwner() {
        assertThrows(NullPointerException.class, () -> new FencingToken(1, null, 1));
        assertEquals(new FencingToken(1, "BIRYANI#1", 3), new FencingToken(1, "BIRYANI#1", 3));
    }

    @Test
    void metricsViewIsAPlainValue() {
        assertEquals(new MetricsView(1, 2, 3, 4, 5, 6.0), new MetricsView(1, 2, 3, 4, 5, 6.0));
    }
}
