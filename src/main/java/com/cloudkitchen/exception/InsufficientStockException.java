package com.cloudkitchen.exception;

/** A conditional stock deduction changed no row: the item does not have enough stock. */
public class InsufficientStockException extends CloudKitchenException {

    private static final long serialVersionUID = 1L;

    private final long itemId;

    public InsufficientStockException(long itemId) {
        super("INSUFFICIENT_STOCK", "Not enough stock for item " + itemId);
        this.itemId = itemId;
    }

    public long getItemId() {
        return itemId;
    }
}
