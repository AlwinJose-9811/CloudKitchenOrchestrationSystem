package com.cloudkitchen.dto;

/** One requested line: which menu item and how many. Prices are never part of a request. */
public record OrderItemRequest(long itemId, int quantity) {
}
