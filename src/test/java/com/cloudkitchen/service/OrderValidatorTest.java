package com.cloudkitchen.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.domain.enums.PaymentMode;
import com.cloudkitchen.dto.FieldError;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.ValidationResult;
import com.cloudkitchen.exception.ValidationException;
import com.cloudkitchen.testsupport.TestData;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class OrderValidatorTest {

    private final OrderValidator validator = new OrderValidator();
    private final MenuCatalog catalog = TestData.catalog();

    private List<String> codes(OrderRequest request) {
        return validator.validate(request, catalog).errors().stream().map(FieldError::code).collect(Collectors.toList());
    }

    private void assertOnly(String code, OrderRequest request) {
        assertEquals(List.of(code), codes(request));
    }

    @Test
    void aValidRequestHasNoErrors() {
        assertTrue(validator.validate(TestData.request().build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().inHouse().build(), catalog).isValid(), "blank in-house ref is fine");
    }

    @Test
    void aNullRequestGivesOneRequestNullError() {
        assertEquals(List.of("REQUEST_NULL"), codes(null));
    }

    @Test
    void vr01SourceIsRequired() {
        assertOnly("SOURCE_INVALID", TestData.request().source(null).build());
    }

    @Test
    void vr02ExternalRef() {
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().externalRef("").build());
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().externalRef(null).build());
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().source(OrderSource.ZOMATO).externalRef("   ").build());
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().externalRef("has space").build());
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().externalRef("semi;colon").build());
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().externalRef("x".repeat(65)).build());
        assertOnly("EXTERNAL_REF_INVALID", TestData.request().inHouse().externalRef("bad ref").build());
        assertTrue(validator.validate(TestData.request().externalRef("x".repeat(64)).build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().externalRef("A.b_c:d-9").build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().externalRef("a").build(), catalog).isValid());
    }

    @Test
    void vr03BrandMustBeAnActiveStation() {
        assertOnly("BRAND_UNKNOWN", TestData.request().brandCode("SUSHI").build());
        assertOnly("BRAND_UNKNOWN", TestData.request().brandCode(null).build());
        assertOnly("BRAND_UNKNOWN", TestData.request().brandCode("biryani").build());
        assertTrue(codes(TestData.request().brandCode("DESSERT").items(new OrderItemRequest(401, 1)).build())
                .contains("BRAND_UNKNOWN"), "inactive station");
    }

    @Test
    void vr04CustomerName() {
        assertOnly("CUSTOMER_NAME_INVALID", TestData.request().customerName("").build());
        assertOnly("CUSTOMER_NAME_INVALID", TestData.request().customerName("   ").build());
        assertOnly("CUSTOMER_NAME_INVALID", TestData.request().customerName(null).build());
        assertOnly("CUSTOMER_NAME_INVALID", TestData.request().customerName("a".repeat(101)).build());
        assertOnly("CUSTOMER_NAME_INVALID", TestData.request().customerName("Bad\nName").build());
        assertOnly("CUSTOMER_NAME_INVALID", TestData.request().customerName("Tab\there").build());
        assertTrue(validator.validate(TestData.request().customerName("a".repeat(100)).build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().customerName("A").build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().customerName("  Zoë Ñandú  ").build(), catalog).isValid());
    }

    @Test
    void vr05Distance() {
        assertOnly("DISTANCE_INVALID", TestData.request().distance(null).build());
        assertOnly("DISTANCE_INVALID", TestData.request().distance("-0.01").build());
        assertOnly("DISTANCE_INVALID", TestData.request().distance("25.01").build());
        assertOnly("DISTANCE_INVALID", TestData.request().distance("1.005").build());
        assertTrue(validator.validate(TestData.request().distance("0").build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().distance("0.00").build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().distance("25.00").build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().distance("25").build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().distance("1.500").build(), catalog).isValid(), "trailing zero is fine");
    }

    @Test
    void vr06PaymentModeMatchesSource() {
        assertOnly("PAYMENT_MODE_INVALID", TestData.request().paymentMode(null).build());
        assertOnly("PAYMENT_MODE_INVALID", TestData.request().paymentMode(PaymentMode.CASH).build());
        assertOnly("PAYMENT_MODE_INVALID", TestData.request().source(OrderSource.ZOMATO).paymentMode(PaymentMode.UPI).build());
        assertOnly("PAYMENT_MODE_INVALID", TestData.request().inHouse().paymentMode(PaymentMode.PREPAID_AGGREGATOR).build());
        for (PaymentMode mode : List.of(PaymentMode.CASH, PaymentMode.UPI, PaymentMode.CARD)) {
            assertTrue(validator.validate(TestData.request().inHouse().paymentMode(mode).build(), catalog).isValid(), mode.name());
        }
    }

    @Test
    void vr07ItemCount() {
        assertOnly("ITEMS_COUNT_INVALID", TestData.request().items((List<OrderItemRequest>) null).build());
        assertOnly("ITEMS_COUNT_INVALID", TestData.request().items(List.of()).build());
        List<OrderItemRequest> twentyOne = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            twentyOne.add(new OrderItemRequest(1000 + i, 1));
        }
        assertOnly("ITEMS_COUNT_INVALID", TestData.request().items(twentyOne).build());
        assertTrue(validator.validate(TestData.request().items(new OrderItemRequest(101, 1)).build(), catalog).isValid(), "one line");
    }

    @Test
    void vr08Quantity() {
        assertOnly("QUANTITY_INVALID", TestData.request().items(new OrderItemRequest(101, 0)).build());
        assertOnly("QUANTITY_INVALID", TestData.request().items(new OrderItemRequest(101, -1)).build());
        assertOnly("QUANTITY_INVALID", TestData.request().items(new OrderItemRequest(101, 21)).build());
        assertTrue(validator.validate(TestData.request().items(new OrderItemRequest(101, 1)).build(), catalog).isValid());
        assertTrue(validator.validate(TestData.request().items(new OrderItemRequest(101, 20)).build(), catalog).isValid());
    }

    @Test
    void vr09DuplicateItemOnSecondOccurrence() {
        ValidationResult result = validator.validate(TestData.request()
                .items(new OrderItemRequest(101, 1), new OrderItemRequest(102, 1), new OrderItemRequest(101, 2)).build(), catalog);
        assertEquals(1, result.errors().size());
        assertEquals("ITEM_DUPLICATE", result.errors().get(0).code());
        assertEquals("items[2].itemId", result.errors().get(0).field());
    }

    @Test
    void vr10To12ItemExistenceActivityAndBrand() {
        assertOnly("ITEM_UNKNOWN", TestData.request().items(new OrderItemRequest(999, 1)).build());
        assertOnly("ITEM_INACTIVE", TestData.request().items(new OrderItemRequest(104, 1)).build());
        assertOnly("ITEM_WRONG_BRAND", TestData.request().items(new OrderItemRequest(201, 1)).build());
    }

    @Test
    void vr13SubtotalBoundaries() {
        // 20 x 2500.00 = 50000.00 exactly: allowed.
        assertTrue(validator.validate(TestData.request().brandCode("PIZZA").items(new OrderItemRequest(302, 20)).build(), catalog).isValid());
        // plus one Margherita (299.00) = 50299.00: too high.
        assertOnly("TOTAL_OUT_OF_RANGE", TestData.request().brandCode("PIZZA")
                .items(new OrderItemRequest(302, 20), new OrderItemRequest(301, 1)).build());
    }

    @Test
    void vr13ZeroSubtotalIsRejected() {
        var free = new com.cloudkitchen.domain.MenuItem(105, 1, "Free Sample", new java.math.BigDecimal("0.00"), true);
        MenuCatalog withFree = MenuCatalog.of(List.of(TestData.BIRYANI), List.of(free));
        ValidationResult result = validator.validate(TestData.request().items(new OrderItemRequest(105, 1)).build(), withFree);
        assertEquals(List.of("TOTAL_OUT_OF_RANGE"), result.errors().stream().map(FieldError::code).toList());
    }

    @Test
    void vr14AllViolationsAreCollectedTogether() {
        OrderRequest bad = new OrderRequest(null, "bad ref", "SUSHI", "", new java.math.BigDecimal("99"), null,
                List.of(new OrderItemRequest(999, 0)));
        List<String> codes = codes(bad);
        assertTrue(codes.containsAll(List.of("SOURCE_INVALID", "EXTERNAL_REF_INVALID", "BRAND_UNKNOWN",
                "CUSTOMER_NAME_INVALID", "DISTANCE_INVALID", "PAYMENT_MODE_INVALID", "QUANTITY_INVALID", "ITEM_UNKNOWN")), codes.toString());
        assertEquals(8, codes.size());
    }

    @Test
    void requireValidThrowsWithEveryError() {
        OrderRequest bad = TestData.request().customerName("").distance("30").build();
        ValidationException e = assertThrows(ValidationException.class, () -> validator.requireValid(bad, catalog));
        assertEquals(2, e.getErrors().size());
        assertEquals("VALIDATION", e.getCode());
        assertDoesNotThrowOnValid();
    }

    private void assertDoesNotThrowOnValid() {
        try {
            validator.requireValid(TestData.request().build(), catalog);
        } catch (ValidationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void messagesNeverEchoTheCustomerInput() {
        OrderRequest request = TestData.request().customerName("Secret\nName").externalRef("bad ref!").build();
        for (FieldError error : validator.validate(request, catalog).errors()) {
            assertFalse(error.message().contains("Secret"), error.message());
            assertFalse(error.message().contains("bad ref"), error.message());
        }
    }

    @Test
    void validationDoesNotChangeTheRequest() {
        OrderRequest request = TestData.request().build();
        validator.validate(request, catalog);
        assertEquals(TestData.request().build(), request);
    }
}
