package com.cloudkitchen.dto;

import java.math.BigDecimal;

/** One menu item with the brand it belongs to, for the administrator's menu page. */
public record MenuItemRow(long itemId, String brandCode, String brandName, String name, BigDecimal price, boolean active) {
}
