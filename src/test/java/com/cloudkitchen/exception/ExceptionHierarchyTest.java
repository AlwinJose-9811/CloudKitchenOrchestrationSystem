package com.cloudkitchen.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.FieldError;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExceptionHierarchyTest {

    @Test
    void everyDomainExceptionIsACheckedCloudKitchenExceptionWithAStableCode() {
        List<CloudKitchenException> all = List.of(
                new ValidationException(List.of(new FieldError("f", "C", "m"))),
                new InvalidStatusTransitionException(OrderStatus.PLACED, OrderStatus.PACKED),
                new InsufficientStockException(3),
                new DuplicateOrderException(9L),
                new QueueFullException("PIZZA", QueueFullException.Reason.QUEUE_FULL),
                new PersistenceException("db down", new RuntimeException("cause")),
                new LeaseStampStaleException("slow"),
                new AuthenticationException(),
                new AccountLockedException(),
                new SnapshotIntegrityException("bad hmac"));
        for (CloudKitchenException e : all) {
            assertTrue(Exception.class.isAssignableFrom(e.getClass()));
            assertFalse(RuntimeException.class.isAssignableFrom(e.getClass()), e.getClass() + " must be checked");
            assertFalse(e.getCode().isBlank());
            assertEquals(e.getCode(), e.getCode().toUpperCase(), "codes are UPPER_SNAKE");
        }
        assertEquals(9, all.stream().map(CloudKitchenException::getCode).distinct().count(),
                "AuthenticationException and AccountLockedException deliberately share one code");
    }

    @Test
    void configurationExceptionIsUnchecked() {
        assertTrue(RuntimeException.class.isAssignableFrom(ConfigurationException.class));
        assertFalse(CloudKitchenException.class.isAssignableFrom(ConfigurationException.class));
    }

    @Test
    void theSpecialisedTypesExtendTheRightParents() {
        assertTrue(AuthenticationException.class.isAssignableFrom(AccountLockedException.class));
        assertTrue(PersistenceException.class.isAssignableFrom(LeaseStampStaleException.class));
    }

    @Test
    void aLockedAccountLooksExactlyLikeABadPassword() {
        AuthenticationException bad = new AuthenticationException();
        AccountLockedException locked = new AccountLockedException();
        assertEquals(bad.getMessage(), locked.getMessage());
        assertEquals(bad.getCode(), locked.getCode());
        assertEquals("Invalid credentials or account temporarily locked.", AuthenticationException.GENERIC_MESSAGE);
    }

    @Test
    void exceptionsCarryTheirContext() {
        InvalidStatusTransitionException transition = new InvalidStatusTransitionException(OrderStatus.DELIVERED, OrderStatus.PLACED);
        assertEquals(OrderStatus.DELIVERED, transition.getFrom());
        assertEquals(OrderStatus.PLACED, transition.getTo());
        assertEquals(3, new InsufficientStockException(3).getItemId());
        assertEquals(9L, new DuplicateOrderException(9L).getExistingOrderId());
        assertNull(new DuplicateOrderException(null).getExistingOrderId());
        QueueFullException full = new QueueFullException("PIZZA", QueueFullException.Reason.INTERRUPTED, new InterruptedException());
        assertEquals("PIZZA", full.getStationCode());
        assertEquals(QueueFullException.Reason.INTERRUPTED, full.getReason());
        assertTrue(full.getCause() instanceof InterruptedException);
        Throwable cause = new IllegalStateException("x");
        assertSame(cause, new PersistenceException("m", cause).getCause());
    }

    @Test
    void validationExceptionKeepsAnUnmodifiableCopyOfTheErrors() {
        List<FieldError> errors = new java.util.ArrayList<>(List.of(new FieldError("f", "C", "m")));
        ValidationException e = new ValidationException(errors);
        errors.clear();
        assertEquals(1, e.getErrors().size());
        assertThrows(UnsupportedOperationException.class, () -> e.getErrors().clear());
        assertEquals("VALIDATION", e.getCode());
    }

    @Test
    void aBlankCodeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CloudKitchenException(" ", "m"));
        assertThrows(IllegalArgumentException.class, () -> new CloudKitchenException(null, "m"));
    }

    @Test
    void messagesNeverContainCustomerData() {
        assertFalse(new DuplicateOrderException(1L).getMessage().contains("1"), "no ids or refs in user-facing text");
    }
}
