package com.cloudkitchen.service;

import com.cloudkitchen.dao.OrderDao;
import com.cloudkitchen.db.Database;
import com.cloudkitchen.domain.Order;
import com.cloudkitchen.domain.enums.OrderStatus;
import com.cloudkitchen.dto.OrderFilter;
import com.cloudkitchen.dto.OrderSummary;
import com.cloudkitchen.dto.Page;
import com.cloudkitchen.exception.CloudKitchenException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Read-only order queries on fresh connections (no transaction, no snapshot held open). */
public final class OrderQueryService {

    public static final int PAGE_SIZE = 20;

    private final Database database;
    private final OrderDao orders;

    public OrderQueryService(Database database, OrderDao orders) {
        this.database = Objects.requireNonNull(database, "database");
        this.orders = Objects.requireNonNull(orders, "orders");
    }

    /** One page of summaries, newest first; a page below 1 is read as page 1. */
    public Page<OrderSummary> list(OrderFilter filter, int page) throws CloudKitchenException {
        int safePage = Math.max(page, 1);
        OrderFilter effective = filter == null ? OrderFilter.any() : filter;
        return database.withConnection(c -> {
            long total = orders.count(c, effective);
            List<OrderSummary> rows = orders.findSummaries(c, effective, PAGE_SIZE, (safePage - 1) * PAGE_SIZE);
            return new Page<>(rows, safePage, PAGE_SIZE, total);
        });
    }

    /** The order with its lines and status history. */
    public Optional<Order> detail(long id) throws CloudKitchenException {
        return database.withConnection(c -> orders.findById(c, id));
    }

    public Optional<Order> byTrackingCode(String code) throws CloudKitchenException {
        return database.withConnection(c -> orders.findByTrackingCode(c, code));
    }

    public Map<OrderStatus, Long> countByStatus() throws CloudKitchenException {
        return database.withConnection(c -> orders.countByStatus(c));
    }
}
