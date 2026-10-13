package com.cloudkitchen.dto;

import com.cloudkitchen.domain.enums.OrderStatus;

/** The few facts recovery and the sweeper need about an order they may hand back to a station queue. */
public record RecoverableOrder(long id, String brandCode, OrderStatus status) {
}
