package com.cloudkitchen.domain;

import com.cloudkitchen.util.MoneyUtil;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of an order. The name and unit price are a snapshot taken when the order was placed, so later menu changes
 * do not alter old orders.
 */
public final class OrderItem {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 20;
    public static final int MAX_NAME_LENGTH = 100;

    private final long itemId;
    private final String itemName;
    private final BigDecimal unitPrice;
    private final int quantity;

    public OrderItem(long itemId, String itemName, BigDecimal unitPrice, int quantity) {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId must be positive");
        }
        if (itemName == null || itemName.isBlank() || itemName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("itemName must be 1-" + MAX_NAME_LENGTH + " characters");
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (!MoneyUtil.fitsTwoDecimals(unitPrice) || unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unitPrice must be non-negative with at most two decimals");
        }
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity must be " + MIN_QUANTITY + "-" + MAX_QUANTITY);
        }
        this.itemId = itemId;
        this.itemName = itemName;
        this.unitPrice = MoneyUtil.scale(unitPrice);
        this.quantity = quantity;
    }

    public long getItemId() {
        return itemId;
    }

    public String getItemName() {
        return itemName;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    /** Unit price times quantity, two decimals. */
    public BigDecimal lineTotal() {
        return MoneyUtil.times(unitPrice, quantity);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof OrderItem that && itemId == that.itemId && quantity == that.quantity
                && itemName.equals(that.itemName) && unitPrice.compareTo(that.unitPrice) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(itemId, itemName, unitPrice.stripTrailingZeros(), quantity);
    }

    @Override
    public String toString() {
        return "OrderItem[itemId=" + itemId + ", quantity=" + quantity + ", unitPrice=" + unitPrice + "]";
    }
}
