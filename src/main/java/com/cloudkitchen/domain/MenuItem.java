package com.cloudkitchen.domain;

import com.cloudkitchen.util.MoneyUtil;
import java.math.BigDecimal;
import java.util.Objects;

/** A dish on one brand's menu. Orders copy its price at order time. */
public final class MenuItem {

    private final long id;
    private final long stationId;
    private final String name;
    private final BigDecimal price;
    private final boolean active;

    public MenuItem(long id, long stationId, String name, BigDecimal price, boolean active) {
        if (id <= 0 || stationId <= 0) {
            throw new IllegalArgumentException("id and stationId must be positive");
        }
        if (name == null || name.isBlank() || name.length() > OrderItem.MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("name must be 1-" + OrderItem.MAX_NAME_LENGTH + " characters");
        }
        Objects.requireNonNull(price, "price");
        if (!MoneyUtil.fitsTwoDecimals(price) || price.signum() < 0) {
            throw new IllegalArgumentException("price must be non-negative with at most two decimals");
        }
        this.id = id;
        this.stationId = stationId;
        this.name = name.strip();
        this.price = MoneyUtil.scale(price);
        this.active = active;
    }

    public long getId() {
        return id;
    }

    public long getStationId() {
        return stationId;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MenuItem that && id == that.id && stationId == that.stationId
                && name.equals(that.name) && price.compareTo(that.price) == 0 && active == that.active;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(id);
    }

    @Override
    public String toString() {
        return "MenuItem[id=" + id + ", stationId=" + stationId + ", price=" + price + ", active=" + active + "]";
    }
}
