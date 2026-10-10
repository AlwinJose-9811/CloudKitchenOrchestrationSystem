package com.cloudkitchen.domain;

/** Stock of one menu item at its station, with the level at or below which it counts as low. */
public final class StockLevel {

    private final long itemId;
    private final int stockQty;
    private final int reorderLevel;

    public StockLevel(long itemId, int stockQty, int reorderLevel) {
        if (itemId <= 0) {
            throw new IllegalArgumentException("itemId must be positive");
        }
        if (stockQty < 0 || reorderLevel < 0) {
            throw new IllegalArgumentException("stockQty and reorderLevel must not be negative");
        }
        this.itemId = itemId;
        this.stockQty = stockQty;
        this.reorderLevel = reorderLevel;
    }

    public long getItemId() {
        return itemId;
    }

    public int getStockQty() {
        return stockQty;
    }

    public int getReorderLevel() {
        return reorderLevel;
    }

    /** Low when the stock is at or below the reorder level. */
    public boolean isLow() {
        return stockQty <= reorderLevel;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StockLevel that && itemId == that.itemId && stockQty == that.stockQty
                && reorderLevel == that.reorderLevel;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(itemId) * 31 + stockQty;
    }

    @Override
    public String toString() {
        return "StockLevel[itemId=" + itemId + ", stockQty=" + stockQty + ", reorderLevel=" + reorderLevel + "]";
    }
}
