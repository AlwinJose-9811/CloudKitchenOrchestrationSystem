package com.cloudkitchen.service;

import com.cloudkitchen.domain.MenuItem;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.OrderItem;
import com.cloudkitchen.domain.Station;
import com.cloudkitchen.domain.enums.OrderSource;
import com.cloudkitchen.dto.FieldError;
import com.cloudkitchen.dto.OrderItemRequest;
import com.cloudkitchen.dto.OrderRequest;
import com.cloudkitchen.dto.ValidationResult;
import com.cloudkitchen.exception.ValidationException;
import com.cloudkitchen.util.MoneyUtil;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation of an {@link OrderRequest} against the rules VR-01..VR-14 of SDD section 11. It performs no I/O and
 * collects every violation instead of stopping at the first. Stock and uniqueness are not checked here: the order
 * transaction enforces them.
 */
public final class OrderValidator {

    public static final BigDecimal MAX_DISTANCE_KM = new BigDecimal("25.00");
    public static final BigDecimal MAX_SUBTOTAL = new BigDecimal("50000.00");

    private static final Pattern EXTERNAL_REF = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /** Checks the request; never throws for bad input. */
    public ValidationResult validate(OrderRequest request, MenuCatalog catalog) {
        if (request == null) {
            return new ValidationResult(List.of(new FieldError("request", "REQUEST_NULL", "The order request is missing.")));
        }
        List<FieldError> errors = new ArrayList<>();
        checkSource(request, errors);
        checkExternalRef(request, errors);
        Optional<Station> station = checkBrand(request, catalog, errors);
        checkCustomerName(request, errors);
        checkDistance(request, errors);
        checkPaymentMode(request, errors);
        checkItems(request, station, catalog, errors);
        return new ValidationResult(errors);
    }

    /** Same checks, but failing with a {@link ValidationException} that carries all errors. */
    public void requireValid(OrderRequest request, MenuCatalog catalog) throws ValidationException {
        ValidationResult result = validate(request, catalog);
        if (!result.isValid()) {
            throw new ValidationException(result.errors());
        }
    }

    private static void checkSource(OrderRequest request, List<FieldError> errors) {
        if (request.source() == null) {
            errors.add(new FieldError("source", "SOURCE_INVALID", "Choose Swiggy, Zomato or In-house."));
        }
    }

    private static void checkExternalRef(OrderRequest request, List<FieldError> errors) {
        String ref = request.externalRef();
        boolean blank = ref == null || ref.isBlank();
        if (blank) {
            if (request.source() == OrderSource.SWIGGY || request.source() == OrderSource.ZOMATO) {
                errors.add(new FieldError("externalRef", "EXTERNAL_REF_INVALID", "The order reference is required."));
            }
            return;
        }
        if (!EXTERNAL_REF.matcher(ref).matches()) {
            errors.add(new FieldError("externalRef", "EXTERNAL_REF_INVALID",
                    "The order reference must be 1-64 letters, digits, '.', '_', ':' or '-'."));
        }
    }

    private static Optional<Station> checkBrand(OrderRequest request, MenuCatalog catalog, List<FieldError> errors) {
        Optional<Station> station = catalog.station(request.brandCode()).filter(Station::isActive);
        if (station.isEmpty()) {
            errors.add(new FieldError("brandCode", "BRAND_UNKNOWN", "This brand is not available."));
        }
        return station;
    }

    private static void checkCustomerName(OrderRequest request, List<FieldError> errors) {
        String name = request.customerName() == null ? "" : request.customerName().strip();
        boolean control = name.codePoints().anyMatch(Character::isISOControl);
        if (name.isEmpty() || name.length() > Order.MAX_CUSTOMER_NAME_LENGTH || control) {
            errors.add(new FieldError("customerName", "CUSTOMER_NAME_INVALID",
                    "The customer name must be 1-" + Order.MAX_CUSTOMER_NAME_LENGTH + " characters without control characters."));
        }
    }

    private static void checkDistance(OrderRequest request, List<FieldError> errors) {
        BigDecimal km = request.deliveryDistanceKm();
        boolean ok = km != null && km.signum() >= 0 && km.compareTo(MAX_DISTANCE_KM) <= 0 && MoneyUtil.fitsTwoDecimals(km);
        if (!ok) {
            errors.add(new FieldError("deliveryDistanceKm", "DISTANCE_INVALID",
                    "The distance must be between 0.00 and 25.00 km with at most two decimals."));
        }
    }

    private static void checkPaymentMode(OrderRequest request, List<FieldError> errors) {
        if (request.paymentMode() == null
                || (request.source() != null && !request.paymentMode().allowedFor(request.source()))) {
            errors.add(new FieldError("paymentMode", "PAYMENT_MODE_INVALID",
                    "This payment mode is not allowed for the order source."));
        }
    }

    private static void checkItems(OrderRequest request, Optional<Station> station, MenuCatalog catalog,
                                   List<FieldError> errors) {
        List<OrderItemRequest> lines = request.items();
        if (lines == null || lines.isEmpty() || lines.size() > Order.MAX_ITEMS) {
            errors.add(new FieldError("items", "ITEMS_COUNT_INVALID",
                    "An order needs between 1 and " + Order.MAX_ITEMS + " item lines."));
            return;
        }
        Set<Long> seen = new HashSet<>();
        List<BigDecimal> lineTotals = new ArrayList<>(lines.size());
        boolean linesUsable = true;
        for (int i = 0; i < lines.size(); i++) {
            OrderItemRequest line = lines.get(i);
            String prefix = "items[" + i + "].";
            if (line == null) {
                errors.add(new FieldError("items[" + i + "]", "ITEM_UNKNOWN", "This item line is empty."));
                linesUsable = false;
                continue;
            }
            boolean quantityOk = line.quantity() >= OrderItem.MIN_QUANTITY && line.quantity() <= OrderItem.MAX_QUANTITY;
            if (!quantityOk) {
                errors.add(new FieldError(prefix + "quantity", "QUANTITY_INVALID",
                        "The quantity must be between " + OrderItem.MIN_QUANTITY + " and " + OrderItem.MAX_QUANTITY + "."));
                linesUsable = false;
            }
            if (!seen.add(line.itemId())) {
                errors.add(new FieldError(prefix + "itemId", "ITEM_DUPLICATE", "This item appears more than once."));
                linesUsable = false;
                continue;
            }
            Optional<MenuItem> item = catalog.item(line.itemId());
            if (item.isEmpty()) {
                errors.add(new FieldError(prefix + "itemId", "ITEM_UNKNOWN", "This item does not exist."));
                linesUsable = false;
                continue;
            }
            if (!item.get().isActive()) {
                errors.add(new FieldError(prefix + "itemId", "ITEM_INACTIVE", "This item is not available."));
                linesUsable = false;
            }
            if (station.isPresent() && item.get().getStationId() != station.get().getId()) {
                errors.add(new FieldError(prefix + "itemId", "ITEM_WRONG_BRAND", "This item is not sold by the chosen brand."));
                linesUsable = false;
            }
            if (quantityOk) {
                lineTotals.add(MoneyUtil.times(item.get().getPrice(), line.quantity()));
            }
        }
        if (linesUsable) {
            BigDecimal subtotal = MoneyUtil.sum(lineTotals);
            if (subtotal.signum() <= 0 || subtotal.compareTo(MAX_SUBTOTAL) > 0) {
                errors.add(new FieldError("items", "TOTAL_OUT_OF_RANGE",
                        "The order total must be above 0.00 and at most " + MAX_SUBTOTAL.toPlainString() + "."));
            }
        }
    }
}
