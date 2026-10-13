package com.cloudkitchen.dto;

/** One row of the stock list; {@code low} is true when the stock is at or below the reorder level. */
public record StockRow(long itemId, String itemName, String brandCode, int stockQty, int reorderLevel, boolean low) {
}
